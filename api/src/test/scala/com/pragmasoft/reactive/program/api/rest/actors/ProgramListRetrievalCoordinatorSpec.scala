package com.pragmasoft.reactive.program.api.rest.actors

import akka.testkit.{TestActorRef, TestProbe, TestKit}
import akka.actor._
import scala.concurrent.duration._
import org.scalatest._
import org.scalatest.mock.MockitoSugar
import rx.lang.scala.{Subscription, Observer}
import com.pragmasoft.reactive.program.api.ProgramDBService
import ProgramDBService.ProgramID
import org.joda.time.DateTime
import org.mockito.Mockito._
import com.escalatesoft.subcut.inject.NewBindingModule._
import com.pragmasoft.reactive.program.api.rest.RestProgramInfoService._
import scala.concurrent.duration.Duration
import com.escalatesoft.subcut.inject.{BindingModule, NewBindingModule}
import NewBindingModule._
import com.escalatesoft.subcut.inject.config._
import com.pragmasoft.reactive.program.api.rest.actors.ProgramListRetrievalCoordinator.{ReaderPoolFactory, PublisherFactory}
import org.mockito.{Matchers => MockitoMatchers}
import MockitoMatchers._

class ProgramListRetrievalCoordinatorSpec extends TestKit(ActorSystem("ProgramListCoordinatorTest")) with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  behavior of "A ProgramListRetrievalCoordinator"


  it should "send work to be done to a ready actor if asked to and work is available" in new WithOneActorInPool(0, 2, 1) {
    poolActor.send(coordinator, Ready)

    poolActor.expectMsg( ProgramListPageRetrieve(0, 1, earliestUpdate) )
  }

  it should "terminate actors in pool when no job to be done is left" in new WithOneActorInPool(0, 1, 1) {
    poolActor.send(coordinator, Ready)
    poolActor.ignoreMsg {
      case ProgramListPageRetrieve(_, _, _) => true
    }

    poolActor.send(coordinator, Ready)

    poolWatch expectTerminated poolActor.ref
  }

  it should "stop when started and close observable if no work should be done" in new WithOneActorInPool(0, 0, 1) {
    coordinatorWatch expectTerminated coordinator

    verify(observer).onCompleted()
  }

  it should "stop if unsubscribed" in new WithOneActorInPool(100, 500, 100) {
    poolActor.send(coordinator, Ready)

    when (subscription.isUnsubscribed) thenReturn true

    poolActor.send(coordinator, Ready)

    coordinatorWatch expectTerminated coordinator
  }

  it should "tell publisher to complete observable when last reader finished its job" in  {
    val poolActor = TestActorRef(new Actor {
      def receive = {
        case _ =>
      }
    })

    val publisher = TestProbe()

    new WithGivenPublisherAndPool(0, 1, 1, publisher.ref, Set(poolActor)) {
      coordinator ! Ready

      publisher expectMsg Done
    }
  }

  it should "tell publisher to complete observable in a more complex case" in {
    val poolActor = TestActorRef(new Actor {
      def receive = {
        case _ => //Ignore
      }
    })

    val publisher = TestProbe()

    new WithGivenPublisherAndPool(100, 500, 100, publisher.ref, Set(poolActor)) {
      coordinator ! Ready
      coordinator ! Ready
      coordinator ! Ready
      coordinator ! Ready
      coordinator ! Ready

      publisher expectMsg Done
    }
  }

  it should "tell publisher to complete observable with error if failed batch" in new WithOneActorInPool(0, 1, 1) {
    val exception = new Exception("test exception")
    poolActor.send(coordinator, FailedBatch(ProgramListPageRetrieve(0, 1, new DateTime()), exception))

    publisher.expectMsg( PublishError(exception) )
  }

  it should "watch pool actors and notifying error to observer when no actor is left working" in {
    val poolActor = TestActorRef(new Actor {
      def receive = {
        case _ => //Ignore
      }
    })

    new WithGivenPool(0, 1, 1, Set(poolActor)) {
      system stop poolActor

      publisher expectMsgAnyClassOf classOf[PublishError]
    }
  }

  it should "watch publisher and terminate when it dies notifying error to observer" in {
    val poolActor = TestActorRef(new Actor {
      def receive = {
        case _ => //Ignore
      }
    })

    val publisherActor = TestActorRef(new Actor {
      def receive = {
        case _ => //Ignore
      }
    })

    new WithGivenPublisherAndPool(0, 1, 1, publisherActor, Set(poolActor)) {
      system stop publisherActor

      coordinatorWatch expectTerminated coordinator
      poolWatch expectTerminated poolActor

      verify(observer).onError(any(classOf[IllegalStateException]))
    }
  }

  it should "shut down when no work is left to be done and all actors finished their work" in {
    val poolActor = TestActorRef(new Actor {
      def receive = {
        case _ => //Ignore
      }
    })

    val publisherActor = TestActorRef(new Actor {
      def receive = {
        case _ => //Ignore
      }
    })

    new WithGivenPublisherAndPool(0, 1, 1, publisherActor, Set(poolActor)) {
      coordinator ! Ready

      system stop poolActor
      system stop publisherActor

      coordinatorWatch expectTerminated coordinator

      // No failure, this is a normal end case
      verify(observer, never).onError(any(classOf[IllegalStateException]))
    }
  }

  it should "shut down with error notification if publisher dies before last reader finished its job" in {
    val poolActor = TestActorRef(new Actor {
      def receive = {
        case _ => //Ignore
      }
    })

    val publisherActor = TestActorRef(new Actor {
      def receive = {
        case _ => //Ignore
      }
    })

    new WithGivenPublisherAndPool(0, 1, 1, publisherActor, Set(poolActor)) {
      system stop publisherActor

      coordinator ! Ready

      system stop poolActor

      coordinatorWatch expectTerminated coordinator

      // Failure, this is NOT a normal end case
      verify(observer).onError(any(classOf[IllegalStateException]))
    }
  }


  class WithGivenPublisherAndPool(val startIndex: Int, val endIndex: Int, val batchSize: Int, val publisherActor : ActorRef, val actorPool: Set[ActorRef]) extends WithControllerEnvironment

  class WithGivenPool(val startIndex: Int, val endIndex: Int, val batchSize: Int, val actorPool: Set[ActorRef]) extends WithControllerEnvironment {
    lazy val publisher = TestProbe()

    override def publisherActor: ActorRef = publisher.ref
  }

  class WithOneActorInPool(val startIndex: Int, val endIndex: Int, val batchSize: Int) extends WithControllerEnvironment {
    lazy val publisher = TestProbe()
    lazy val poolActor = TestProbe()

    override def publisherActor: ActorRef = publisher.ref

    override def actorPool: Set[ActorRef] = Set(poolActor.ref)
  }

  trait WithControllerEnvironment {

    def startIndex: Int
    def endIndex: Int
    def batchSize: Int
    def actorPool : Set[ActorRef]
    def publisherActor : ActorRef

    val observer = mock[Observer[ProgramID]]
    val subscription = mock[Subscription]

    val earliestUpdate = new DateTime()

    when (subscription.isUnsubscribed) thenReturn false

    val properties : Map[String, String] = Map(
      "programdb.api.timeout.seconds" -> "10",
      "programdb.api.parallelRead" -> "2",
      "programdb.api.pageSize" -> batchSize.toString,
      "programdb.api.maxRequestsPerMinute" -> "60"
    )

    val publisherFactory : PublisherFactory = (context : ActorContext, observer : Observer[ProgramID]) => publisherActor
    val readerPoolFactory : ReaderPoolFactory = (context : ActorContext, size: Int, coordinator : ActorRef, publisher: ActorRef, maxRead: Int,
                                                 bindingModule: BindingModule) => actorPool

    val bindingModule = newBindingModuleWithConfig( PropertiesConfigPropertySource { properties } )

    val coordinator = system.actorOf(
      ProgramListRetrievalCoordinator.props(startIndex, endIndex, earliestUpdate, observer, subscription,
        publisherFactory, readerPoolFactory
      )(bindingModule),
      "Coordinator" + System.currentTimeMillis
    )

    val publisherWatch = TestProbe()
    val poolWatch = TestProbe()
    val coordinatorWatch = TestProbe()

    publisherWatch watch publisherActor
    actorPool foreach { poolWatch watch _ }
    coordinatorWatch watch coordinator
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
}
