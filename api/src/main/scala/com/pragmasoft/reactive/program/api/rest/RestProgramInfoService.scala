package com.pragmasoft.reactive.program.api.rest

import scala.util._
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.io.IO
import spray.json._
import spray.can.Http
import spray.httpx.{unmarshalling, SprayJsonSupport}
import spray.client.pipelining._
import spray.util._
import scala.concurrent.{Await, Future}
import scala.util.Failure
import spray.client.pipelining
import spray.http._
import spray.http.HttpHeaders.{Location => LocationHeader}
import spray.httpx._
import org.apache.commons.lang.StringUtils._
import Future._
import org.slf4j.LoggerFactory
import java.io.IOException
import com.typesafe.config.ConfigFactory
import com.pragmasoft.reactive.program.api.data._
import org.joda.time.{DateTimeZone, DateTime}
import scala.concurrent._
import rx.lang.scala.{Subscription, Observable, Observer, Scheduler}
import rx.lang.scala.schedulers._
import scala.util.Left
import scala.util.Failure
import scala.Some
import spray.http.HttpResponse
import scala.util.Success
import spray.http.HttpRequest
import scala.util.Right
import com.pragmasoft.reactive.program.api.data.jsonconversions.ProgramDatabaseJsonProtocol
import rx.lang.scala.subscriptions.CompositeSubscription
import com.escalatesoft.subcut.inject.{BindingId, Injectable}
import com.escalatesoft.subcut.inject._
import java.lang.System
import com.pragmasoft.reactive.program.api.config.ConfigConversions._
import com.pragmasoft.reactive.program.api.data._
import scala.util.Left
import scala.util.Failure
import scala.Some
import spray.http.HttpResponse
import scala.util.Success
import com.pragmasoft.reactive.program.api.data.ProgramListMetadata
import spray.http.HttpRequest
import scala.util.Right
import com.pragmasoft.reactive.program.api.ProgramDBService

object RestProgramInfoService {
  val HEADER_API_KEY = "apikey"
  val HEADER_API_VERSION = "apiversion"

  def getBySourceAndIdRelativeUrl(source: String, id: String) = s"/programmes?src=$source&srcid=$id"

  def getByProgramIdRelativeUrl(programId: String) = s"/programmes/$programId"
  def getProgramListRelativeUrl(offset: Int, limit: Int) = s"/programmes/?offset=$offset&limit=$limit"

  def extractIdFromUrl(href: String) : String = href.split("/").last

  def tryGet[T](future : Future[T])(implicit timeout : Duration) = Try { Await.result( future, timeout) }
}

import ProgramDatabaseJsonProtocol._
import SprayJsonSupport._
import spray.httpx.unmarshalling._
import RestProgramInfoService._

abstract class RestProgramInfoService(implicit val bindingModule: BindingModule) extends ProgramDBService with Serializable with Injectable {
  import  ProgramDBService._

  val log = LoggerFactory.getLogger(classOf[SequentialReadRestProgramInfoService])

  var isInternalActorSystem = false

  val programDbServiceBaseAddress = injectProperty[String]("programdb.api.base-address")
  val programDbApiKey = injectProperty[String]("programdb.api.key")
  val programDbApiVersion = injectProperty[String]("programdb.api.version")
  val programListPagingSize = injectProperty[Int]("programdb.api.pageSize")
  implicit val timeout = injectProperty[Duration]("programdb.api.timeout.seconds")
  val sprayConfFile = injectProperty[String]("spray.config.file.name")

  implicit val actorSystem = injectOptional[ActorSystem] getOrElse(createActorSystem())


  require( timeout.toMillis > 0, s"Invalid value $timeout for timeout" )
  require( isNotEmpty(programDbServiceBaseAddress), "Missing value for program DB service base address" )
  require( isNotEmpty(programDbApiKey), "Missing value for program DB API key" )
  require( isNotEmpty(programDbApiVersion), "Missing value for program DB API version" )

  log.info("Configuring ProgramDB client connecting programDB at {}", programDbServiceBaseAddress)

  import actorSystem.dispatcher // execution context for futures below

  val basePipeline = addHeader(HEADER_API_KEY, programDbApiKey) ~> addHeader(HEADER_API_VERSION, programDbApiVersion) ~> sendReceive
  val programInfoRetrievalPipeline = basePipeline ~> redirectPropagatingHeaders(3) ~> decodeProgramInfo
  val programListRetrievalPipeline = basePipeline ~> redirectPropagatingHeaders(3) ~> unmarshal[ProgramList]
  val programSourcesPipeline = basePipeline ~> unmarshal[ProgramSources]
  val programSeasonsPipeline = basePipeline ~> unmarshal[ProgramSeasonInfo]

  val asyncBrowsingScheduler : Scheduler = ThreadPoolForIOScheduler()

  def getIdOfEntriesEarlierThan(earliestUpdated: DateTime, startIndex: Int = 0, endIndex: Option[Int] = None): Observable[ProgramID] = Observable.create( (observer: Observer[ProgramID]) => {
    val collectThreadSubscription = Subscription()
    val subscription : CompositeSubscription = CompositeSubscription(collectThreadSubscription)

    // The subscription returned by asyncBrowsingScheduler.schedule is useless because we are scheduling one execution only
    val schedulerSubscription : Subscription = asyncBrowsingScheduler.schedule {
      collectEntriesMoreRecentThan(startIndex, earliestUpdated, observer, collectThreadSubscription, endIndex)
    }

    subscription += schedulerSubscription
  }
  )

  def getEntriesEarlierThan(earliestUpdated: DateTime, startIndex: Int = 0, endIndex: Option[Int] = None) : Observable[Try[ProgramInfo]] =
    getIdOfEntriesEarlierThan(earliestUpdated, startIndex, endIndex)  map { getByProgramID(_) }

  def requestEntriesMoreRecentThan(earliestUpdated: DateTime, startIndex: Int = 0, endIndex: Option[Int] = None)  : Observable[Future[ProgramInfo]] =
    getIdOfEntriesEarlierThan(earliestUpdated, startIndex, endIndex)  map { requestByProgramID(_) }

  def getByProgramID(id: ProgramID) : Try[ProgramInfo] = tryGet { requestByProgramID(id) }

  def requestByProgramID(id: ProgramID) : Future[ProgramInfo] = retrieveFullProgramInfoAsyncAt(getByProgramIdRelativeUrl(id), id)

  def getBySourceAndId(source: String, id: String) : Try[ProgramInfo] = tryGet { requestBySourceAndId(source, id) }

  def requestBySourceAndId(source: String, id: String) : Future[ProgramInfo] = retrieveFullProgramInfoAsyncAt(getBySourceAndIdRelativeUrl(source, id), s"$source:$id")

  def getByProgramIDList(ids: Seq[ProgramID]) : Seq[Try[ProgramInfo]] = {
    val responses = ids map { id => failAfterTimeout(id) { requestByProgramID(id) } }

    Await.result( Future.sequence( responses ), timeout * 2  )
  }

  def shutdown() : Unit = {
    log.info("Closing HTTP subsystem")
    try {
      IO(Http).ask(Http.CloseAll)(30.second).await
    } finally {
      if(isInternalActorSystem) {
        log.info("SHUTTING DOWN ACTOR SYSTEM")
        actorSystem.shutdown()
        log.info("DONE")
      }
    }
  }

  def collectEntriesMoreRecentThan(startIndex: Int, earliestUpdated: DateTime, observer: Observer[ProgramID], subscription: Subscription, endIndexOption: Option[Int]): Unit

  def retrieveAllProgramIdsInRange(rangeStart: Int, rangeSize: Int) : Future[ProgramList] = {
    val url = programDbServiceBaseAddress + getProgramListRelativeUrl(rangeStart, rangeSize)
    programListRetrievalPipeline { Get(url) }
  }

  protected def publishRecentIdsFromList(programList: ProgramList, earliestUpdated: DateTime, observer: Observer[ProgramID]) : Boolean  = {
    def isLastBatch(programListMetadata: ProgramListMetadata) : Boolean = {
      import programListMetadata._

      total <= offset + limit
    }

    programList._links.items filter { item => item.lastUpdated isAfter earliestUpdated } foreach {
      item => observer.onNext(extractIdFromUrl(item.href))
    }
    isLastBatch(programList._metadata)

  }

  protected def retrieveSources(basicProgramInfo: ProgramInfo) : Future[ProgramInfo ] = {
    def programInfoWithSources(basic: ProgramInfo, sources: ProgramSources) : ProgramInfo = {
      val sourceMap : Map[String, List[String]] = sources.entries.foldLeft (Map.empty[String, List[String]]) {
        (map, currEntry) => {
          val entriesWithSameKey = map.get(currEntry.source) getOrElse List.empty[String]
          map + (currEntry.source -> (currEntry.id :: entriesWithSameKey) )
        }
      }

      basic.copy( externalSourcesIDs = Some(sourceMap) )
    }

    log.debug(s"Retrieving links at ${basicProgramInfo._links.sources.href}")
    val sourcesInfo: Future[ProgramSources] = programSourcesPipeline { Get( basicProgramInfo._links.sources.href ) }

    sourcesInfo map {
      programInfoWithSources(basicProgramInfo, _)
    }  recoverWith {
      case ex =>
        log.warn(s"Error trying to retrieve sources info at '${basicProgramInfo._links.sources.href}' for program ${basicProgramInfo.uuid}. Returning base info", ex)
        Future.successful(basicProgramInfo)
    }
  }

  protected def retrieveSeriesAndSeasonIds(basic: ProgramInfo) : Future[ProgramInfo] = {
    if( (basic.`type` == "PROGRAMME") && basic._links.season.isDefined ) {
      log.debug(s"Retrieving season info at ${basic._links.season.get.href}")
      val programSeasonInfoResponse: Future[ProgramSeasonInfo] = programSeasonsPipeline { Get(basic._links.season.get.href) }
      programSeasonInfoResponse map {
        programSeasonInfo => basic.copy(
          seasonId = Some(programSeasonInfo.uuid),
          seriesId = Some( extractIdFromUrl(programSeasonInfo._links.series.href) )
        )
      } recoverWith {
        case ex =>
          log.warn(s"Error trying to retrieve season info at '${basic._links.season.get.href}' for program ${basic.uuid} returning base info", ex)
          Future.successful(basic)
      }
    } else {
      Future.successful(basic)
    }
  }

  protected def retrieveFullProgramInfoAsyncAt(relativePath: String, id: String) : Future[ProgramInfo] = {
    retrieveBasicProgramInfoAsyncAt(relativePath, id) flatMap { retrieveSources(_) } flatMap { retrieveSeriesAndSeasonIds(_) }
  }

  protected def retrieveBasicProgramInfoAsyncAt(relativePath: String, id: String) : Future[ProgramInfo] = {
    val targetUrl = programDbServiceBaseAddress + relativePath
    log.debug("Calling programDB at {}", targetUrl)
    val response : Future[ProgramInfo] = programInfoRetrievalPipeline { Get( targetUrl ) }

    response map { _.copy(requestId = Some(id)) }
  }

  protected def failAfterTimeout[T](requestId: String)(response: Future[T]) : Future[Try[T]] = {
    val timeoutFailureFuture : Future[Try[T]] = future {
      Thread sleep timeout.toMillis
      Failure[T] (new TimeoutException(s"Call didn't complete call for id $requestId in ${timeout.toMillis} millis"))
    }

    val extractedResponse = response map { responseEntity  => Success(responseEntity) } recover { case exception => Failure[T](exception) }
    firstCompletedOf( List(extractedResponse , timeoutFailureFuture) )
  }


  protected def decodeProgramInfo(response: HttpResponse) : ProgramInfo = {
    if (response.status.isSuccess) {
      programDatabaseResultFormat.apply(response.entity) match {
        case Left(deserializationError) => throw new IOException(deserializationError.toString)
        case Right(programInfoWithNoRawBody) => programInfoWithNoRawBody.copy(raw = Some(response.entity.asString) )
      }
    }
    else throw new UnsuccessfulResponseException(response)
  }

  protected def redirectPropagatingHeaders(maxRedirects: Int) (response: Future[HttpResponse]) : Future[HttpResponse] =
    if(maxRedirects == 0) response else response flatMap( doRedirect(maxRedirects - 1) )

  protected def doRedirect(maxRedirects: Int) (response: HttpResponse) : Future[HttpResponse] = {
    def fullySpecifiedUri(uri: Uri) : String = programDbServiceBaseAddress + uri.toRelative.toString

    val nextRetriesPipeline: HttpRequest => Future[HttpResponse] = basePipeline ~> redirectPropagatingHeaders(maxRedirects)

    response.status.intValue match {
      case (301 | 302 | 303 | 307) =>
        //Returning same response if cannot find where to redirect
        response.header[LocationHeader] map { location => nextRetriesPipeline { Get(fullySpecifiedUri(location.uri))  } } getOrElse successful(response)

      case _ => successful(response)
    }
  }

  protected def createActorSystem() : ActorSystem = {
    isInternalActorSystem = true
    // This is a workaround for the startup problems encountered when running in the cluster
    System.setProperty("spray.version", "1.0")
    System.setProperty("akka.version", "2.2.3")
    ActorSystem("program-info-client", ConfigFactory.parseResources(sprayConfFile))
  }
}



