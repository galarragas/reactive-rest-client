package com.pragmasoft.reactive.program.api.rest.actors

import akka.actor._
import com.escalatesoft.subcut.inject.{Injectable, BindingModule}
import com.pragmasoft.reactive.program.api.rest.RestProgramInfoService._
import scala.concurrent.duration._
import spray.client.pipelining._
import scala.util.{Success, Try}

import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import com.pragmasoft.reactive.program.api.data.jsonconversions.ProgramDatabaseJsonProtocol._

import com.pragmasoft.reactive.program.api.config.ConfigConversions._
import scala.util.Failure
import scala.util.Success
import com.pragmasoft.reactive.program.api.data.ProgramList

object ProgramListReader {
  def props(coordinator : ActorRef, publisher : ActorRef, maxReadPerMinute: Int)(implicit bindingModule : BindingModule) : Props =
    Props(classOf[ProgramListReader], coordinator, publisher, maxReadPerMinute, 1 minute, bindingModule)

  def props(coordinator : ActorRef, publisher : ActorRef, maxReadPerInterval: Int, intervalDuration : FiniteDuration)(implicit bindingModule : BindingModule) : Props =
    Props(classOf[ProgramListReader], coordinator, publisher, maxReadPerInterval, intervalDuration, bindingModule)
}

// I am not using the context.parent ActorRef but passing the coordinator actorRef to allow unit testing
class ProgramListReader(coordinator : ActorRef, publisher : ActorRef, maxReadPerInterval: Int, intervalDuration : FiniteDuration)(implicit val bindingModule : BindingModule) extends Actor with ActorLogging with Stash with Injectable {
  val programDbServiceBaseAddress = injectProperty[String]("programdb.api.base-address")
  val programDbApiKey = injectProperty[String]("programdb.api.key")
  val programDbApiVersion = injectProperty[String]("programdb.api.version")

  implicit val timeout = injectProperty[Duration]("programdb.api.timeout.seconds")

  import context.dispatcher

  var readInLastInterval : Int = 0
  val programListRetrievalPipeline = addHeader(HEADER_API_KEY, programDbApiKey) ~> addHeader(HEADER_API_VERSION, programDbApiVersion) ~> sendReceive ~> unmarshal[ProgramList]

  var publishedMessagesCount = 0

  context.system.scheduler.schedule(intervalDuration, intervalDuration, self, ResetCounter)

  coordinator ! Ready

  override def receive: Actor.Receive = working

  def working : Actor.Receive = {
    case ResetCounter =>
      readInLastInterval = 0

    case retrieveReq@ProgramListPageRetrieve(_, _, _) =>
      readInLastInterval = readInLastInterval + 1
      log.info("Retrieving batch {}", retrieveReq)
      readNextBatch(retrieveReq)

      log.info(s"Batch finished, total of $publishedMessagesCount messages published")

      if(readInLastInterval >= maxReadPerInterval) {
        log.debug("Too much work in last minute, going into sleep mode for a while")
        context become onHold
      } else {
        coordinator ! Ready
      }
  }

  def onHold : Actor.Receive =  {
    case ResetCounter =>
      readInLastInterval = 0
      unstashAll()
      coordinator ! Ready
      context become working

    case ProgramListPageRetrieve(_, _, _) =>
      stash()
  }


  def readNextBatch(retrieveReq : ProgramListPageRetrieve) = {
    val url = programDbServiceBaseAddress + getProgramListRelativeUrl(retrieveReq.startIndex, retrieveReq.pageSize)
    val programListTry : Try[ProgramList] = tryGet { programListRetrievalPipeline { Get { url } } }

    programListTry match {
      case Success(programList) =>
        programList._links.items filter { item => item.lastUpdated isAfter retrieveReq.earliestUpdated } foreach {
          item =>
            log.debug("Publishing item {}", item)
            publisher ! PublishProgramID(extractIdFromUrl(item.href))
            publishedMessagesCount += 1
        }

      case Failure(throwable) =>
        log.warning(s"Error trying to retrieve program list at '${programDbServiceBaseAddress + getProgramListRelativeUrl(retrieveReq.startIndex, retrieveReq.pageSize)} ending retrieval. Exception $throwable")
        coordinator ! FailedBatch(retrieveReq, throwable)
    }

  }
}
