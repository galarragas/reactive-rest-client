package com.pragmasoft.reactive.program.api.rest.actors

import akka.actor._
import math._
import List._
import akka.actor.SupervisorStrategy.{Escalate, Stop, Restart, Resume}
import scala.concurrent.duration._
import com.pragmasoft.reactive.program.api.rest.RestProgramInfoService._
import com.escalatesoft.subcut.inject.{BindingModule, Injectable}
import rx.lang.scala.{Subscription, Observer}
import com.pragmasoft.reactive.program.api.ProgramDBService
import ProgramDBService.ProgramID
import spray.client.pipelining._
import akka.actor.Terminated
import akka.actor.AllForOneStrategy
import com.pragmasoft.reactive.program.api.rest.RestProgramInfoService._
import org.joda.time.DateTime
import com.pragmasoft.reactive.program.api.rest.actors.ProgramListRetrievalCoordinator.{ReaderPoolFactory, PublisherFactory}
import com.pragmasoft.reactive.program.api.config.ConfigConversions._

// Messages
case class ProgramListPageRetrieve(startIndex : Int, pageSize : Int, earliestUpdated : DateTime)
object Ready
object Done
case class FailedBatch(batch: ProgramListPageRetrieve, error : Throwable)
case class PublishId(programId: String)
object ResetCounter

object ProgramListRetrievalCoordinator {
  type PublisherFactory = (ActorContext, Observer[ProgramID]) => ActorRef
  type ReaderPoolFactory = (ActorContext, Int, ActorRef, ActorRef, Int, BindingModule) => Set[ActorRef]

  val readerPoolFactory : ReaderPoolFactory = createReaderPool
  val publisherFactory : PublisherFactory = createPublisher

  private def createReaderPool(context : ActorContext, poolSize: Int, coordinatorActor: ActorRef, publisherActor: ActorRef, maxReadPerMinute: Int, bindingModule : BindingModule) : Set[ActorRef] = {
    implicit val _ = bindingModule

    range(0, poolSize) map { index => context.actorOf(ProgramListReader.props(coordinatorActor, publisherActor, maxReadPerMinute/poolSize), s"Reader_$index") } toSet
  }

  private def createPublisher(context : ActorContext, observer: Observer[ProgramID]) : ActorRef = context.actorOf(Publisher.props(observer), "Publisher")

  def props(startIndex: Int, endIndex: Int, earliestUpdated : DateTime,
                  programIdObserver : Observer[ProgramID], subscription: Subscription)(implicit bindingModule : BindingModule) : Props = {
    Props(classOf[ProgramListRetrievalCoordinator], startIndex, endIndex, earliestUpdated, programIdObserver, subscription, publisherFactory, readerPoolFactory, bindingModule)
  }

  def props(startIndex: Int, endIndex: Int, earliestUpdated : DateTime,
                  programIdObserver : Observer[ProgramID], subscription: Subscription,
                  customPublisherFactory: PublisherFactory, customReaderPoolFactory : ReaderPoolFactory )(implicit bindingModule : BindingModule) : Props = {
    Props(classOf[ProgramListRetrievalCoordinator], startIndex, endIndex, earliestUpdated, programIdObserver, subscription, customPublisherFactory, customReaderPoolFactory, bindingModule)
  }
}

class ProgramListRetrievalCoordinator(startIndex: Int, endIndex: Int, earliestUpdated : DateTime,
                                      programIdObserver : Observer[ProgramID], subscription: Subscription,
                                      publisherFactory : PublisherFactory,
                                      readerPoolFactory : ReaderPoolFactory,
                                      implicit val bindingModule : BindingModule)
  extends Actor with ActorLogging with Injectable {



  implicit val timeout = injectProperty[Duration]("programdb.api.timeout.seconds")
  val parallelReaderCount = injectProperty[Int]("programdb.api.parallelRead")
  val programListPagingSize = injectProperty[Int]("programdb.api.pageSize")
  val maxReadPerMinute = injectProperty[Int]("programdb.api.maxRequestsPerMinute")

  val ___retrieveRequests : List[ProgramListPageRetrieve] = range(startIndex, endIndex, programListPagingSize) map { pageStartIndex => ProgramListPageRetrieve(pageStartIndex, programListPagingSize, earliestUpdated) }

  val creationTime = System.currentTimeMillis

  val publisher : ActorRef = publisherFactory(context, programIdObserver)

  if(___retrieveRequests.isEmpty) {
    log.warning("No work to be done for this request")
    programIdObserver.onCompleted()
    context stop self
  }

  val originActorPool = readerPoolFactory(context, parallelReaderCount, self, publisher, maxReadPerMinute, bindingModule)

  context watch publisher
  originActorPool foreach { context watch _ }

  override def receive: Actor.Receive = withWorkToDo(___retrieveRequests, originActorPool)

  override val supervisorStrategy =
    AllForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case _: Exception                => Resume
    }

  def handleFailure : Actor.Receive = {
    case FailedBatch(batch, throwable) =>
      log.error(s"Failure processing batch ${batch}")
      publisher ! PublishError(throwable)
      log.info("Stopping")
      context stop self
  }


  def withWorkToDo( leftToRetrieve : List[ProgramListPageRetrieve], actorPool : Set[ActorRef]) : Actor.Receive = ( {
    case Ready =>
      if(subscription.isUnsubscribed) {
        log.info("Subscription has been unsubscribed. Shutting down")
        context stop self
      } else {
        val batchToDo = leftToRetrieve.head
        log.info(s"Actor ${sender.path.name} available to process new batch, asking it to do batch $batchToDo")
        sender ! batchToDo
        if(leftToRetrieve.tail.isEmpty) {
          log.info(s"Finished batches to be retrieved. Sending poison pill to active actors $actorPool")
          actorPool foreach { actor => actor ! PoisonPill }

          context become noWorkLeft(actorPool)
        } else {
          log.debug(s"Work left to do ${leftToRetrieve.tail}")
          context become withWorkToDo(leftToRetrieve.tail, actorPool)
        }
      }
    case Terminated(actor) if (actor == publisher) =>
      log.error("Publisher actor died. Stopping publishing. Notifying observer of error and shutting down")
      programIdObserver.onError(new IllegalStateException("Publisher died"))
      actorPool foreach { context stop _ }
      context stop self

    case Terminated(poolActor) =>
      val remainingPool = actorPool - poolActor
      log.warning(s"Actor ${poolActor.path.name} terminated. Removing it from pool. Pool is now composed by actors ${remainingPool}")
      if(remainingPool isEmpty) {
        log.error("Empty pool left. Notifying publisher about error and waiting for termination")
        publisher ! PublishError(new IllegalStateException("No actors left in the pool"))
        context become noWorkLeft(Set.empty)
      } else {
        context become withWorkToDo(leftToRetrieve, remainingPool)
      }

  } : Receive ) orElse handleFailure

  def noWorkLeft(activeActors : Set[ActorRef]) : Actor.Receive = ( {
    case Ready =>
      log.debug(s"Actor ${sender.path.name} available to process but no work left to do. Ignoring it ")

    case Terminated(actor) if (actor == publisher) =>
      log.error("Publisher actor died before sending error event to observer!")
      programIdObserver.onError( new IllegalStateException("Publisher actor died before sending close event on observer!"))
      activeActors foreach { context stop _ }
      context stop self

    case Terminated(poolActor) =>
      log.info(s"Actor ${poolActor.path.name} finished its work. Removing from list of left ones")
      val remainingActors = activeActors - poolActor

      if(remainingActors.isEmpty) {
        log.info(s"All actors finished work. Waiting for the publisher to finish")
        log.debug(s"Telling to publisher that retrieval is completed")
        publisher ! Done
        context become waitForPublisherDeath
      } else {
        log.debug(s"Waiting notifications from actors: $remainingActors")
        context become noWorkLeft(remainingActors)
      }

  } : Receive )  orElse handleFailure


  def waitForPublisherDeath : Actor.Receive = {
    case Terminated(actor) if (actor == publisher) =>
      log.info(s"All actors finished work. Stopping. Retrieved ${endIndex - startIndex + programListPagingSize} messages in ${System.currentTimeMillis - creationTime} millis")
      context stop self
  }
}





