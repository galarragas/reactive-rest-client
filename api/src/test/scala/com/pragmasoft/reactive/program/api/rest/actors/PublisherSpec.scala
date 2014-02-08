package com.pragmasoft.reactive.program.api.rest.actors

import akka.testkit.{TestProbe, TestActorRef, TestKit}
import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, Matchers, FlatSpecLike}
import org.scalatest.mock.MockitoSugar
import rx.lang.scala.Observer
import com.pragmasoft.reactive.program.api.ProgramDBService
import ProgramDBService.ProgramID
import org.mockito.Mockito._
import org.mockito.{Matchers => MockitoMatchers}
import MockitoMatchers._
import MockitoMatchers.{eq => argEq}

class PublisherSpec extends TestKit(ActorSystem("ProgramListCoordinatorTest")) with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  behavior of "Publisher"

  it should "publish to observer any new programID" in {
    val observer = mock[Observer[ProgramID]]
    val publisher = TestActorRef(Publisher.props(observer))

    publisher ! PublishProgramID("id")

    verify(observer).onNext(argEq("id"))
  }

  it should "publish to observer any error" in {
    val observer = mock[Observer[ProgramID]]
    val publisher = TestActorRef(Publisher.props(observer))

    val exception = new IllegalArgumentException("Test exception")

    publisher ! PublishError(exception)

    verify(observer).onError(same(exception))
  }

  it should "publish to observer completion" in {
    val observer = mock[Observer[ProgramID]]
    val publisher = TestActorRef(Publisher.props(observer))

    publisher ! Done

    verify(observer).onCompleted()
  }

  it should "shutdown on completion" in {
    val observer = mock[Observer[ProgramID]]
    val publisher = TestActorRef(Publisher.props(observer))

    val publisherDeathWatch = TestProbe()

    publisherDeathWatch watch publisher

    publisher ! Done

    publisherDeathWatch expectTerminated publisher
  }

  it should "shutdown after error" in {
    val observer = mock[Observer[ProgramID]]
    val publisher = TestActorRef(Publisher.props(observer))

    val publisherDeathWatch = TestProbe()

    publisherDeathWatch watch publisher

    val exception = new IllegalArgumentException("Test exception")

    publisher ! PublishError(exception)

    publisherDeathWatch expectTerminated publisher
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
}
