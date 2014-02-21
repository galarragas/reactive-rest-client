package com.pragmasoft.reactive.program.client.service

import com.escalatesoft.subcut.inject.{BindingId, Injectable, BindingModule}
import org.slf4j.LoggerFactory
import org.joda.time.DateTime
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.util.Try
import com.pragmasoft.reactive.program.api.ProgramDBService
import com.pragmasoft.reactive.program.api.config.ConfigConversions._

object ProgramDBAcquirer {
  object MaxProgramExecutionTime extends BindingId
}

import ProgramDBAcquirer._

class ProgramDBAcquirer(implicit val bindingModule: BindingModule) extends Injectable {
  val log = LoggerFactory.getLogger(classOf[ProgramDBAcquirer])

  val programInfoService = inject[ProgramDBService]
  val pendingProcessesCompletionInterval : Duration = injectProperty[Duration]("pending.processes.completion.interval.millis")
  val programDbHbasePersistence = inject[ProgramDBPersistence]
  val maxProgramExecutionTime = inject[Duration](MaxProgramExecutionTime)

  def acquire(earliestDate: DateTime, maxNumberOfRetrievededEntries: Option[Int], startIndex: Int) : Try[Long] =
    withinTimeLimit{ acquireEntries(earliestDate, maxNumberOfRetrievededEntries, startIndex) }

  private def withinTimeLimit[T] ( block : => Future[T] ) : Try[T] = Try { Await.result( block, maxProgramExecutionTime) }

  private def acquireEntries(earliestUpdated: DateTime, maxNumberOfAcquiredEntries: Option[Int], startIndex: Int = 0) : Future[Long] = {
    def letPendingProcessesFinish() : Unit = {
      log.info("Sleeping for {} millis to let pending processes finish", pendingProcessesCompletionInterval)
      Thread.sleep(pendingProcessesCompletionInterval.toMillis)
    }

    val promise = Promise[Long]
    val entriesObservable = programInfoService.requestEntriesMoreRecentThan(earliestUpdated, startIndex, maxNumberOfAcquiredEntries map { _ + startIndex} )

    var acquiredEntries = 0

    entriesObservable subscribe (
      onNext = (programInfoFuture) => {
        programInfoFuture onSuccess {
          case programInfo =>
            log.debug("Received program info {}. Persisting it", programInfo.uuid)

            programDbHbasePersistence.saveProgramInfo(programInfo)
            programDbHbasePersistence.addIdLookupEntry(programInfo)

            acquiredEntries += 1
        }
        programInfoFuture onFailure {
          case error => log.warn(s"Program info not retrieved because of error: ${error}", error)
        }
      },
      onError = (error) => {
        log.warn(s"ERROR $error")
        letPendingProcessesFinish()
        promise.failure(error)
      },
      onCompleted = () => {
        log.info("DONE BROWSING")
        letPendingProcessesFinish()
        promise.success(acquiredEntries)
      }
     )

    promise.future
  }

}
