package com.pragmasoft.reactive.program.api.rest.actors

import com.pragmasoft.reactive.program.api.ProgramDBService
import ProgramDBService.ProgramID
import akka.actor.{ActorLogging, Actor, Props}
import rx.lang.scala.Observer

case class PublishProgramID(programId : ProgramID )
case class PublishError(throwable: Throwable)

object Publisher {
  def props(observer: Observer[ProgramID]) = {
    Props(classOf[Publisher], observer)
  }
}

class Publisher(observer: Observer[ProgramID]) extends Actor with ActorLogging {
  var messageCount = 0

  override def receive: Actor.Receive = {
    case PublishProgramID(programId) =>
      log.debug("Publishing {}", programId)
      observer.onNext(programId)
      messageCount += 1
    case PublishError(throwable) =>
      log.info( s"Publishing error $throwable. Stopping" )
      observer.onError(throwable)
      context stop self
    case Done =>
       log.info(s"Completed. Stopping. Published $messageCount messages")
       observer.onCompleted()
       context stop self
  }
}