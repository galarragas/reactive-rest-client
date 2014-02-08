package com.pragmasoft.reactive.program.client

import scala.concurrent.duration._
import org.joda.time.DateTime
import scala.util.{Try, Failure, Success}
import scala.concurrent._
import ExecutionContext.Implicits.global
import com.pragmasoft.reactive.program.api.rest.{SequentialReadRestProgramInfoService, RestProgramInfoService, ParallelReadRestProgramInfoService}
import com.escalatesoft.subcut.inject.NewBindingModule._
import com.escalatesoft.subcut.inject._
import NewBindingModule._
import com.typesafe.config.{ConfigFactory, Config}
import com.escalatesoft.subcut.inject._
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import org.joda.time.format.DateTimeFormat
import com.pragmasoft.reactive.program.api.rest.RestProgramInfoService._
import org.joda.time
import com.pragmasoft.reactive.program.client.service.{ProgramDBAcquirer, ProgramDBPersistence}
import com.pragmasoft.reactive.program.client.service.persistence.{ProgramDBHBasePersistence, ProgramDBHBaseAkkaPersistence}
import com.pragmasoft.reactive.program.client.service.ProgramDBAcquirer.MaxProgramExecutionTime
import com.pragmasoft.reactive.program.api.ProgramDBService
import com.pragmasoft.reactive.program.client.config.AppConfigPropertySource
import com.pragmasoft.reactive.program.api.config.ConfigConversions._

object WriteProgramDBToHBase extends App {
  val log = LoggerFactory.getLogger(WriteProgramDBToHBase.getClass)
  val configPath = "classpath:ingester.conf"

  if(args.length < 1) {
    Console.err.println("Missing mandatory arguments. Expected arguments: earliestDate [YYYY-mm-DD] (maxExecutionTime) (startIndex) (maxEntriesToAcquire)")
    sys.exit(-1)
  }

  val earliestDate = parseDate(args(0))
  val maxProgramExecutionTime = (getOptionalArgAt(1) map { _.toInt } getOrElse { appConfig getInt "max.program.exec.time.minutes" }).minutes
  val startIndex = getOptionalArgAt(2) map { _.toInt } getOrElse 0
  val maxNumberOfRetrievedEntries = getOptionalArgAt(3) map { _.toInt }

  val useAkkaForHBasePersistence = (appConfig getBoolean "hbase.persistence.use.akka")

  var actorSystem: ActorSystem = null
  var programInfoService: RestProgramInfoService = null
  var programDbHbasePersistence: ProgramDBPersistence = null



  implicit val configProvider = AppConfigPropertySource.apply(appConfig)

  log.info(s"Acquiring data from ProgramDB more recent than $earliestDate, max number of entries read: $maxNumberOfRetrievedEntries, max execution time: $maxProgramExecutionTime, start index: $startIndex")
  println(s"Acquiring data from ProgramDB more recent than $earliestDate, max number of entries read: $maxNumberOfRetrievedEntries, max execution time: $maxProgramExecutionTime, start index: $startIndex")

  try {
    System.setProperty("spray.version", "1.0")
    System.setProperty("akka.version", "2.2.3")
    actorSystem = ActorSystem("program-info-client", ConfigFactory.parseResources(appConfig getString "spray.config.file.name"))

    // Reading configuration and loading into the DI binding module used in to inject values into services
    implicit val bindingModule = readConfig

    val useParallelRead = appConfig.hasPath("programdb.api.parallelRead") && (appConfig.getInt("programdb.api.parallelRead") > 1)

    // Create services using DI bindings from binding module
    programInfoService =
      if(useParallelRead) {
        log.info("Using sequential read client")
        new ParallelReadRestProgramInfoService
      } else {
        log.info("Using parallel read")
        new SequentialReadRestProgramInfoService
      }

    val programDBAcquirer = bindingModule modifyBindings { implicit bindingModule =>
      import bindingModule._

      bind [ProgramDBService] toSingle programInfoService
      bind [ProgramDBPersistence] toSingle createProgramDBPersistence(bindingModule)

      new ProgramDBAcquirer
    }

    val start = System.currentTimeMillis

    log.info(s"Starting acquiring from program DB")

    val result: Try[Long] = programDBAcquirer.acquire(earliestDate, maxNumberOfRetrievedEntries, startIndex)

    result recover {
      case ex: TimeoutException =>
        log.warn(s"Acquisition timed out with time limit $maxProgramExecutionTime", ex)
        -1l
      case ex : Throwable =>
        log.error(s"Acquisition failed with exception", ex)
        -1l
    } foreach { acquiredEntries =>
      val elapsed = System.currentTimeMillis - start
      log.info(s"ProgramDB acquisition ended in $elapsed millis acquiring $acquiredEntries entries")
    }
  } finally {
    shutDown()
  }

  def createProgramDBPersistence(implicit bindingModule: BindingModule) : ProgramDBPersistence = {
    if(useAkkaForHBasePersistence) {
      log.info("Using AKKA writer")
      new ProgramDBHBaseAkkaPersistence
    } else {
      log.info("Using syncrhonous writer")
      new ProgramDBHBasePersistence
    }
  }

  def shutDown() {
    log.info("Shutting down")

    if( programInfoService != null ) {
      try {
        programInfoService.shutdown()
      } catch {
        case exception: Throwable => log.error(s"Exception $exception closing program info service")
      }
    }

    if(programDbHbasePersistence != null) {
      try {
        programDbHbasePersistence.shutdown()
      } catch {
        case exception: Throwable => log.error(s"Exception $exception closing HBase persistence")
      }
    }

    if(actorSystem != null) {
      try {
        actorSystem.shutdown()
      } catch {
        case exception: Throwable => log.error(s"Exception $exception closing actor system")
      }
    }
    log.info("BYE")
  }

  def readConfig : BindingModule = newBindingModuleWithConfig { implicit module =>
    import module._
    bind [ActorSystem] toSingle actorSystem

    bind [Duration] identifiedBy MaxProgramExecutionTime toSingle maxProgramExecutionTime
  }

  lazy val appConfig : Config = AppConfigPropertySource.loadAppConfig(configPath)

  def getOptionalArgAt(index: Int) : Option[String] = if(args.size > index) Some(args(index)) else None

  def parseDate(date: String) : DateTime = {
    val daysAgoExpression = """(\d+) days ago""".r

    date match {
      case "yesterday" => new time.DateTime().minusDays(1).toDateMidnight.toDateTime

      case daysAgoExpression(days) => new time.DateTime().minusDays(days.toInt).toDateMidnight.toDateTime

      case _ => DateTimeFormat.forPattern("YYYY-mm-DD").parseDateTime(date)
    }
  }
}
