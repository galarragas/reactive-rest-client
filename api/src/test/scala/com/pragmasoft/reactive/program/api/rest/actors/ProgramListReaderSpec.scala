package com.pragmasoft.reactive.program.api.rest.actors

import akka.testkit.{TestProbe, TestKit}
import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, Matchers, FlatSpecLike}
import com.pragmasoft.reactive.program.api.rest.RestProgramInfoService
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import com.github.tomakehurst.wiremock.client.WireMock
import com.escalatesoft.subcut.inject.NewBindingModule._
import com.escalatesoft.subcut.inject.config.PropertiesConfigPropertySource
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import RestProgramInfoService._
import org.apache.http.HttpStatus._
import org.apache.http.HttpHeaders._
import org.joda.time.DateTime
import scala.concurrent.duration._
import com.pragmasoft.reactive.program.TestTags
import TestTags._

class ProgramListReaderSpec extends TestKit(ActorSystem("ProgramListCoordinatorTest")) with FlatSpecLike with Matchers with BeforeAndAfterAll  {

  val mockServiceListeningPort = 39999
  val timeout = 3000
  val batchSize = 10

  val properties : Map[String, String] = Map(
    "programdb.api.base-address" -> s"http://localhost:$mockServiceListeningPort",
    "programdb.api.key" -> "apiKey",
    "programdb.api.version" -> "apiVersion",
    "programdb.api.timeout.seconds" -> timeout.toString,
    "programdb.api.pageSize" -> batchSize.toString
  )

  implicit val bindingModule = newBindingModuleWithConfig( PropertiesConfigPropertySource { properties } )


  behavior of "ProgramListReader"

  it should "declare to be ready to coordinator once finished initialisation" in {
    val publisher = TestProbe()
    val coordinator = TestProbe()

    val programListReader = system.actorOf(ProgramListReader.props(coordinator.ref, publisher.ref, 10), "reader" + System.currentTimeMillis)

    coordinator expectMsg Ready
  }

  it should "Read ProgramList from ProgramDB service" in withStubbedAPI { wireMockServer =>
    val offset = 0

    givenThat {
      get {
        urlEqualTo( getProgramListRelativeUrl(offset, batchSize) )
      } willReturn {
        aResponse() withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(programListValidResponse)
      }
    }

    val publisher = TestProbe()

    val programListReader = system.actorOf(ProgramListReader.props(testActor, publisher.ref, 10), "reader" + System.currentTimeMillis)

    programListReader ! ProgramListPageRetrieve(offset, batchSize, new DateTime())

    Thread.sleep(1000)

    verify(getRequestedFor( urlEqualTo( getProgramListRelativeUrl(0, batchSize) ) ))
  }

  it should "send program IDs matching time filter to publisher" in withStubbedAPI { wireMockServer =>
    val offset = 0

    givenThat {
      get {
        urlEqualTo( getProgramListRelativeUrl(offset, batchSize) )
      } willReturn {
        aResponse() withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(programListValidResponse)
      }
    }

    val publisher = TestProbe()

    val programListReader = system.actorOf(ProgramListReader.props(testActor, publisher.ref, 10), "reader" + System.currentTimeMillis)

    programListReader ! ProgramListPageRetrieve(offset, batchSize, new DateTime(0).withYear(2013).withMonthOfYear(9).withDayOfMonth(4))

    publisher expectMsg PublishProgramID("dc8f3627-7119-468a-b69b-007bda0fbd6a")
    publisher expectMsg PublishProgramID("dc8f3627-7119-468a-b69b-007bda0fbd6c")

    verify(getRequestedFor( urlEqualTo( getProgramListRelativeUrl(0, batchSize) ) ))
  }

  it should "tell the coordinator that is ready to process another batch" in withStubbedAPI { wireMockServer =>
    val offset = 0

    givenThat {
      get {
        urlEqualTo( getProgramListRelativeUrl(offset, batchSize) )
      } willReturn {
        aResponse() withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(programListValidResponse)
      }
    }

    val publisher = TestProbe()
    val coordinator = TestProbe()

    val programListReader = system.actorOf(ProgramListReader.props(coordinator.ref, publisher.ref, 10), "reader" + System.currentTimeMillis)

    programListReader ! ProgramListPageRetrieve(offset, batchSize, new DateTime(0).withYear(2013).withMonthOfYear(9).withDayOfMonth(4))

    coordinator expectMsg Ready
  }

  it should "tell the coordinator if there was any error processing a batch" in withStubbedAPI { wireMockServer =>
    val offset = 0

    givenThat {
      get {
        urlEqualTo( getProgramListRelativeUrl(offset, batchSize) )
      } willReturn {
        aResponse() withStatus(SC_INTERNAL_SERVER_ERROR)
      }
    }

    val publisher = TestProbe()
    val coordinator = TestProbe()

    val programListReader = system.actorOf(ProgramListReader.props(coordinator.ref, publisher.ref, 10), "reader" + System.currentTimeMillis)

    programListReader ! ProgramListPageRetrieve(offset, batchSize, new DateTime(0).withYear(2013).withMonthOfYear(9).withDayOfMonth(4))

    // the first one
    coordinator expectMsg Ready
    coordinator.expectMsgType[FailedBatch]
  }

  it should "declare to be ready after error processing current batch" in withStubbedAPI { wireMockServer =>
    val offset = 0

    givenThat {
      get {
        urlEqualTo( getProgramListRelativeUrl(offset, batchSize) )
      } willReturn {
        aResponse() withStatus(SC_INTERNAL_SERVER_ERROR)
      }
    }

    val publisher = TestProbe()
    val coordinator = TestProbe()

    val programListReader = system.actorOf(ProgramListReader.props(coordinator.ref, publisher.ref, 10), "reader" + System.currentTimeMillis)

    programListReader ! ProgramListPageRetrieve(offset, batchSize, new DateTime(0).withYear(2013).withMonthOfYear(9).withDayOfMonth(4))

    coordinator.ignoreMsg { case FailedBatch(_, _) => true }
    coordinator expectMsg Ready
  }

  it should "delay serving request in excess of per minute limit until the next minute is passed" taggedAs(Flaky) in withStubbedAPI { wireMockServer =>
    val offset = 0

    givenThat {
      get {
        urlEqualTo( getProgramListRelativeUrl(offset, batchSize) )
      } willReturn {
        aResponse() withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(programListValidResponse)
      }
    }

    val publisher = TestProbe()

    val programListReader = system.actorOf(ProgramListReader.props(testActor, publisher.ref, 2, 20 seconds), "reader" + System.currentTimeMillis)

    programListReader ! ProgramListPageRetrieve(offset, batchSize, new DateTime(0).withYear(2013).withMonthOfYear(9).withDayOfMonth(4))
    programListReader ! ProgramListPageRetrieve(offset, batchSize, new DateTime(0).withYear(2013).withMonthOfYear(9).withDayOfMonth(4))
    programListReader ! ProgramListPageRetrieve(offset, batchSize, new DateTime(0).withYear(2013).withMonthOfYear(9).withDayOfMonth(4))

    Thread.sleep(1000)

    verify(2, getRequestedFor( urlEqualTo( getProgramListRelativeUrl(0, batchSize) ) ))

    //Waiting interval to be expired and a bit more for the message handling
    Thread.sleep(20000)
    verify(3, getRequestedFor( urlEqualTo( getProgramListRelativeUrl(0, batchSize) ) ))
  }

  val programListValidResponse = s"""
            |{
            |
            |    "_embedded": { },
            |    "_links": {
            |        "items":
            |        [
            |            {    "href": "http://localhost:${mockServiceListeningPort}/programmes/dc8f3627-7119-468a-b69b-007bda0fbd6a",
            |                 "lastUpdated": "2013-09-04T13:45:13Z"
            |            },
            |            {    "href": "http://localhost:${mockServiceListeningPort}/programmes/dc8f3627-7119-468a-b69b-007bda0fbd6b",
            |                 "lastUpdated": "2013-09-03T13:45:13Z"
            |            },
            |            {    "href": "http://localhost:${mockServiceListeningPort}/programmes/dc8f3627-7119-468a-b69b-007bda0fbd6c",
            |                 "lastUpdated": "2013-09-04T13:45:13Z"
            |            }
            |        ]
            |    },
            |    "_metadata": {
            |        "total": 3,
            |        "limit": 100,
            |        "offset": 0
            |    }
            |
            |}
          """.stripMargin

  def withStubbedAPI( test: (WireMockServer) => Unit ) {

    var wireMockServer : WireMockServer = null

    try {
      wireMockServer = new WireMockServer(wireMockConfig().port(mockServiceListeningPort));
      wireMockServer.start();

      WireMock.configureFor("localhost", mockServiceListeningPort);

      test(wireMockServer)

    } finally {
      if(wireMockServer != null)
        wireMockServer.stop()
    }
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
  
}
