package com.pragmasoft.reactive.program.api.rest

import com.escalatesoft.subcut.inject.{Injectable, BindingId, BindingModule}
import org.joda.time.DateTime
import rx.lang.scala.{Subscription, Observer}
import scala.util.{Failure, Success}
import math._
import List._

import RestProgramInfoService._
import com.pragmasoft.reactive.program.api.ProgramDBService
import ProgramDBService.ProgramID
import akka.actor.ActorSystem
import com.pragmasoft.reactive.program.api.rest.actors.ProgramListRetrievalCoordinator
import com.pragmasoft.reactive.program.api.data.ProgramList

class ParallelReadRestProgramInfoService(implicit bindingModule: BindingModule) extends RestProgramInfoService with Injectable {

  // They are here to make the service creation fail instead of the actor creation fail, just for early failure..
  val parallelReaderCount = injectProperty[Int]("programdb.api.parallelRead")
  val maxReadPerMinute = injectProperty[Int]("programdb.api.maxRequestsPerMinute")

  def collectEntriesMoreRecentThan(startIndex: Int, earliestUpdated: DateTime, observer: Observer[ProgramID], subscription: Subscription, endIndexOption: Option[Int]): Unit = {
    //read first batch, determine total size and then read in parallel the total amount of batches to read
    // limit the number of parallel executors and throttle the number of read requests per second
    val firstBatchTry = tryGet[ProgramList] { retrieveAllProgramIdsInRange(startIndex, programListPagingSize) }

    // Publishing this batch if successful
    firstBatchTry match {
      case Success(programList) =>
        publishRecentIdsFromList(programList, earliestUpdated, observer)
        val readUntil = startIndex + programListPagingSize
        val availableItemsInDB = programList._metadata.total
        val lastIndex = endIndexOption map {  endIndex => min(endIndex, availableItemsInDB) } getOrElse availableItemsInDB

        // now read the rest in parallel
        // creating a pool of requester. Using the throttler code to implement max request per second. Can't limit
        // the number of messages upfront...
        actorSystem.actorOf(ProgramListRetrievalCoordinator.props(readUntil, lastIndex.toInt, earliestUpdated, observer, subscription), "coordinator")

      case Failure(ex) =>
        observer.onError(ex)
    }
  }

  override def shutdown() {
    //stop actors

    super.shutdown()
  }
}
