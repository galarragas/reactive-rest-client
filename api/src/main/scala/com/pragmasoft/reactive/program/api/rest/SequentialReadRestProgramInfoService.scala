package com.pragmasoft.reactive.program.api.rest

import com.escalatesoft.subcut.inject.BindingModule
import org.joda.time.DateTime
import rx.lang.scala.{Subscription, Observer}
import scala.util.{Failure, Success}

import RestProgramInfoService._
import com.pragmasoft.reactive.program.api.ProgramDBService
import ProgramDBService.ProgramID
import com.pragmasoft.reactive.program.api.data.ProgramList

class SequentialReadRestProgramInfoService(implicit bindingModule: BindingModule) extends RestProgramInfoService {
  def collectEntriesMoreRecentThan(startIndex: Int, updatedAfter: DateTime, observer: Observer[ProgramID], subscription: Subscription, endIndexOption: Option[Int]): Unit = {
    var finishedBrowsing = false
    var rangeStart = startIndex

    while(!subscription.isUnsubscribed && !finishedBrowsing) {
      finishedBrowsing = readProgramIdsInRange(updatedAfter, rangeStart, programListPagingSize, observer)
      rangeStart += programListPagingSize

      endIndexOption foreach { endIndex =>
        if(rangeStart > endIndex) {
          log.info("Passed end index {} processing up to entry {}. Stopping reading", endIndex, rangeStart)
          finishedBrowsing = true
        }
      }
    }
    if(finishedBrowsing) {
      log.info("Finished retrieving info")
      observer onCompleted()
    } else if(subscription.isUnsubscribed) {
      log.info("Stopped retrieving info because unsubscribed")
    }
  }

  protected def readProgramIdsInRange(earliestUpdated: DateTime, rangeStart: Int, rangeSize: Int, observer: Observer[ProgramID]) : Boolean = {
    log.info(s"Reading a batch of $rangeSize program IDs from count $rangeStart filtering with min date $earliestUpdated ")
    val programListTry = tryGet[ProgramList] { retrieveAllProgramIdsInRange(rangeStart, rangeSize) }

    val lastBatch = programListTry match {
      case Success(programList) =>
        publishRecentIdsFromList(programList, earliestUpdated, observer)

      case Failure(ex) =>
        log.warn(s"Error trying to retrieve program list at '${programDbServiceBaseAddress + getProgramListRelativeUrl(rangeStart, rangeSize)} ending retrieval", ex)
        observer.onError(ex)
        true
    }

    log.info(s"DONE Reading batch of $rangeSize program IDs from count $rangeStart filtering with min date $earliestUpdated ")

    lastBatch
  }
}
