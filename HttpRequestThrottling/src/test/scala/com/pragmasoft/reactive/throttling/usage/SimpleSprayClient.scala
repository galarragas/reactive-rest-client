package com.pragmasoft.reactive.throttling.usage

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import spray.client.pipelining._
import com.pragmasoft.reactive.throttling.threshold._
import scala.concurrent.Future
import org.scalatest.FlatSpec
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import akka.util.Timeout
import scala.concurrent.duration._
import spray.json._
import spray.client.pipelining._
import com.github.tomakehurst.wiremock.client.WireMock._
import org.apache.http.HttpHeaders._
import com.pragmasoft.reactive.throttling.usage.SimpleResponse
import org.apache.http.HttpStatus._
import org.apache.http.HttpHeaders._
import com.github.tomakehurst.wiremock.client.WireMock


// Both lines have to be there to make spray json conversions work
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

case class SimpleResponse(message: String)

object SimpleClientProtocol extends DefaultJsonProtocol {
  implicit val simpleResponseFormat = jsonFormat1(SimpleResponse)
}

class SimpleSprayClient(serverBaseAddress: String, frequency: Frequency, parallelRequests: Int) {
  import SimpleClientProtocol._
  import com.pragmasoft.reactive.throttling.HttpRequestThrottling._

  implicit val actorSystem = ActorSystem("program-info-client", ConfigFactory.parseResources("test.conf"))

  import actorSystem.dispatcher

  implicit val apiTimeout : Timeout = frequency.interval * 3

  val pipeline = sendReceive(throttleFrequencyAndParallelRequests(frequency, parallelRequests)) ~> unmarshal[SimpleResponse]

  def callFakeService(id: Int) : Future[SimpleResponse] = pipeline { Get(s"$serverBaseAddress/fakeService?$id") }


  def shutdown() = actorSystem.shutdown()
}

import scala.concurrent.ExecutionContext.Implicits.global

class SimpleSprayClientTest extends FlatSpec{
  behavior of "SimpleSprayClient"
  val port = 29999
  val stubServiceUrl = s"http://localhost:$port"
  val frequency : Frequency = 5 every (15 seconds)
  val parallelRequests = 3

  it should s"enqueue requests to do maximum $frequency" in withStubbedApi { client : SimpleSprayClient =>

    givenThat {
      get( urlMatching( s"$stubServiceUrl/fakeService?\\d" ) ) willReturn {
        aResponse withStatus(SC_OK)  withHeader(CONTENT_TYPE, "application/json") withBody( """{ "message": "hello" }""" )
      }
    }

    val totalRequests = frequency.amount * 2
    for { id <- 0 to totalRequests } yield client.callFakeService(id)

    Thread.sleep(1000)

    verify(frequency.amount, getRequestedFor( urlMatching( """/fakeService\?\d+""" ) ) )

    Thread.sleep(frequency.interval.toMillis)

    verify(totalRequests, getRequestedFor( urlMatching( """/fakeService\?\d+""" ) ) )
  }

  it should "limit parallel requests" in withStubbedApi { client : SimpleSprayClient =>

    val responseDelay = frequency.interval / 2
    givenThat {
      get( urlMatching( s"$stubServiceUrl/fakeService?\\d" ) ) willReturn {
        aResponse withStatus(SC_OK)  withHeader(CONTENT_TYPE, "application/json") withBody( """{ "message": "hello" }""" ) withFixedDelay(responseDelay.toMillis.toInt)
      }
    }

    for { id <- 0 to (frequency.amount * 2) } yield client.callFakeService(id)

    Thread.sleep(1000)

    verify(parallelRequests, getRequestedFor( urlMatching( """/fakeService\?\d+""" ) ) )

    Thread.sleep(responseDelay.toMillis + 1000)

    verify(frequency.amount - parallelRequests, getRequestedFor( urlMatching( """/fakeService\?\d+""" ) ) )

  }

  def withStubbedApi( test: SimpleSprayClient => Unit ) = {

    var client : SimpleSprayClient = null
    var wireMockServer : WireMockServer = null

    try {
      client = new SimpleSprayClient(stubServiceUrl, frequency, parallelRequests)

      wireMockServer = new WireMockServer(wireMockConfig().port(port));
      wireMockServer.start();

      WireMock.configureFor("localhost", port);

      test(client)

    } finally {
      if(client != null)
        client.shutdown()

      if(wireMockServer != null)
        wireMockServer.stop()
    }


  }

}
