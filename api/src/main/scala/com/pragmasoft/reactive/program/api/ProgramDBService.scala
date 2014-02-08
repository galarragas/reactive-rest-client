package com.pragmasoft.reactive.program.api


import org.joda.time.DateTime
import scala.util.Try
import scala.concurrent.Future
import rx.lang.scala.Observable
import com.pragmasoft.reactive.program.api.data.ProgramInfo

object ProgramDBService {
  type ProgramID = String
}

trait ProgramDBService {
  import ProgramDBService._

  def getIdOfEntriesEarlierThan(earliestUpdated: DateTime, startIndex: Int = 0, endIndex: Option[Int] = None) : Observable[ProgramID]

  // The start index could have been managed using skip on the observable but that would have generated the calls for the first entries anyway.
  def getEntriesEarlierThan(earliestUpdated: DateTime, startIndex: Int = 0, endIndex: Option[Int] = None) : Observable[Try[ProgramInfo]]
  def requestEntriesMoreRecentThan(earliestUpdated: DateTime, startIndex: Int = 0, endIndex: Option[Int] = None ) : Observable[Future[ProgramInfo]]

  def getByProgramID(id: ProgramID) : Try[ProgramInfo]
  def requestByProgramID(id: ProgramID) : Future[ProgramInfo]

  def getByProgramIDList(ids: Seq[ProgramID]) : Seq[Try[ProgramInfo]]

  def getBySourceAndId(source: String, id: String) : Try[ProgramInfo]
  def requestBySourceAndId(source: String, id: String) : Future[ProgramInfo]

  def shutdown() : Unit
}

