package com.pragmasoft.reactive.program.api.rest

import scala.concurrent.duration._
import RestProgramInfoService._
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import org.apache.http.HttpStatus._
import org.apache.http.HttpHeaders._
import org.scalatest.{GivenWhenThen, Matchers, FlatSpec}
import org.joda.time.DateTime
import spray.json.JsString
import rx.lang.scala.Observable
import com.pragmasoft.reactive.program.api.data.jsonconversions.ProgramDatabaseJsonProtocol
import scala.util.Failure
import scala.Some
import scala.util.Success
import com.pragmasoft.reactive.program.api.data.{ProgramInfo, Synopse, ProgramInfoLinks, ProgramDbHref}
import com.pragmasoft.reactive.program.api.ProgramDBService

trait RestProgramInfoServiceSpec extends FlatSpec with Matchers with GivenWhenThen {
  val programId: String = "154791db-2dd6-4c84-b62f-622ee0a5e2da"
  val serviceKey: String = "1000"
  val eventId: String = "001"
  val timeout = 4 seconds
  val batchSize = 100

  def mockServiceListeningPort : Int

  behavior of "GET byProgramId"

  it should "retrive program info" in  withStubbedAPI { (service, mockServer) =>

    Given( s"Program DB API is returning a valid response...'")
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl(programId) ) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(validProgramInfoJson)
      }
    }

    And( "The program sources info are returned correctly" )
    givenThat {
      get( urlEqualTo("/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources" ) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(sourcesJson)
      }
    }

    When( s"I call the service with program ID $programId")
    val programInfoResult = service.getByProgramID(programId)

    Then( "I'm expecting a successful response")
    programInfoResult match {
      case Success(programInfo) =>
        programInfo should equal (expectedProgramInfo(programId))

      case Failure(throwable) =>
        fail("Exception in programInfoService.getProgramInfo", throwable)
    }
  }

  it should "supply API headers" in withStubbedAPI{ (service, mockServer) =>

    Given( s"Program DB API is returning a valid response ...'")
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl(programId) ) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(validProgramInfoJson)
      }
    }

    And( "The program sources info are returned correctly" )
    givenThat {
      get( urlEqualTo("/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources" ) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(sourcesJson)
      }
    }

    When( s"Calling program info with programId $programId")
    service.getByProgramID(programId)

    Then("The programInfo service has been called with the API_KEY header and API_VERSION header")
    verify{
      getRequestedFor( urlEqualTo( getByProgramIdRelativeUrl(programId) ) )
        .withHeader(HEADER_API_KEY, matching("apiKey"))
        .withHeader(HEADER_API_VERSION, matching("apiVersion"))
    }
  }

  it should "follow redirections propagating API headers" in withStubbedAPI{ (service, mockServer) =>

    val secondUrl = "/redirectedUrl?parameter1"

    Given( s"Program DB call returns a redirect to '$secondUrl'")
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl(programId) ) ) willReturn (aResponse withStatus(SC_TEMPORARY_REDIRECT) withHeader("Location", secondUrl))
    }

    And( "Second redirect actually returns the program info JSON")
    givenThat {
      get( urlEqualTo( secondUrl ) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(validProgramInfoJson)
      }
    }

    And( "The program sources info are returned correctly" )
    givenThat {
      get( urlEqualTo("/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources" ) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(sourcesJson)
      }
    }

    When("ProgramInfo service is calling the first URL")
    val programInfoResult = service.getByProgramID(programId)

    Then("Response is successful")
    assert( programInfoResult.isSuccess, s"Failure calling programInfoService wiht redirect $programInfoResult" )

    And("The first URL has actually been called")
    verify { getRequestedFor( urlEqualTo( getByProgramIdRelativeUrl(programId) ) ) }

    And("The second call was to the redirect URL and providing the API_KEY header and API_VERSION header ")
    verify {
      getRequestedFor( urlEqualTo( secondUrl ) )
        .withHeader(HEADER_API_KEY, matching("apiKey"))
        .withHeader(HEADER_API_VERSION, matching("apiVersion"))
    }
  }

  it should "return failure" in withStubbedAPI { (service, mockServer) =>

    Given( s"Program DB call returns a failure" )
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl(programId) ) ) willReturn (aResponse withStatus(SC_NOT_FOUND))
    }

    When(s"I call the service for IDs $programId")
    Then("the call fails")
    assert( service.getByProgramID(programId) isFailure )
  }

  behavior of "GET byProgramIdList"

  it should "collect all required info" in withStubbedAPI { (service, mockServer) =>

    Given("All required ID are returned successfully")
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl("programId1") ) ) willReturn (aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(programInfoResponseWithNameAndID("name1", "programId1")))
    }
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl("programId2") ) ) willReturn (aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(programInfoResponseWithNameAndID("name2", "programId2")))
    }

    And( "The program sources info (common for all) are returned correctly" )
    givenThat {
      get( urlEqualTo("/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources" ) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(sourcesJson)
      }
    }

    When("I call the service for IDs programId1, programId2")
    val programInfoListResult = service.getByProgramIDList(List("programId1", "programId2"))

    Then("The call returns a list with the two expected program info")
    programInfoListResult should equal ( List(Success(programInfoWithNameAndID("name1", "programId1")), Success(programInfoWithNameAndID("name2", "programId2"))) )
  }

  it should "return failure for the failing calls" in withStubbedAPI { (service, mockServer) =>

    Given("Call to retrieve program id for 'programId1' succeed")
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl("programId1") ) ) willReturn (aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(programInfoResponseWithNameAndID("name1", "programId1")))
    }
    Given("Call to retrieve program id for 'programId2' fails")
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl("programId2") ) ) willReturn (aResponse withStatus(SC_NOT_FOUND))
    }

    And( "The program sources info (common for all) are returned correctly" )
    givenThat {
      get( urlEqualTo("/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources" ) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(sourcesJson)
      }
    }

    When("I call the service for IDs programId1, programId2")
    val programInfoListResult = service.getByProgramIDList(List("programId1", "programId2"))

    Then("The call returns a list with the the program info for programId1 and a failure for programId2")
    programInfoListResult(0) should equal (Success(programInfoWithNameAndID("name1", "programId1")))
    assert( programInfoListResult(1) isFailure )
  }

  it should "return failure for the failing calls only without failing subsequent calls" in withStubbedAPI { (service, mockServer) =>

    Given("Call to retrieve program id for 'programId1' fails")
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl("programId1") ) ) willReturn (aResponse withStatus(SC_NOT_FOUND))
    }
    And("Call to retrieve program id for 'programId2' is successful")
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl("programId2") ) ) willReturn (aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(programInfoResponseWithNameAndID("name2", "programId2")))
    }

    And( "The program sources info (common for all) are returned correctly" )
    givenThat {
      get( urlEqualTo("/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources" ) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(sourcesJson)
      }
    }

    When("I call the service for IDs programId1, programId2")
    val programInfoListResult = service.getByProgramIDList(List("programId1", "programId2"))

    Then("The call returns a list with the the program info for programId1 and a failure for programId1")
    assert( programInfoListResult(0) isFailure )
    programInfoListResult(1) should equal (Success(programInfoWithNameAndID("name2", "programId2")))
  }

  it should "return failure for the timing out calls only without failing all other items" in withStubbedAPI { (service, mockServer) =>

    Given("Call to retrieve program id for 'programId1' is successful")
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl("programId1") ) ) willReturn (aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(programInfoResponseWithNameAndID("name1", "programId1")))
    }

    And("Call to retrieve program id for 'programId2' is too slow")
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl("programId2") ) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(programInfoResponseWithNameAndID("name1", "programId1")) withFixedDelay( (timeout * 2).toMillis.toInt )
      }
    }

    And("Call to retrieve program id for 'programId3' is successful")
    givenThat {
      get( urlEqualTo( getByProgramIdRelativeUrl("programId3") ) ) willReturn (aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(programInfoResponseWithNameAndID("name3", "programId3")))
    }

    And( "The program sources info (common for all) are returned correctly" )
    givenThat {
      get( urlEqualTo("/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources" ) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(sourcesJson)
      }
    }

    When("I call the service for IDs programId1, programId2, programId3")
    val programInfoListResult = service.getByProgramIDList(List("programId1", "programId2", "programId3"))

    Then("The call returns a list with the the program info for programId1 and programId3 and a failure for programId2")
    programInfoListResult(0) should equal (Success(programInfoWithNameAndID("name1", "programId1")))
    assert( programInfoListResult(1) isFailure )
    programInfoListResult(2) should equal (Success(programInfoWithNameAndID("name3", "programId3")))
  }

  behavior of "GetProgramList"

  it should "return an empty observable if no result" in withStubbedAPI { (service, mockServer) =>

    givenThat {
      get( urlEqualTo(getProgramListRelativeUrl(0, batchSize)) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(
          """
            |{
            |
            |    "_embedded": { },
            |    "_links": {
            |        "items": [ ]
            |    },
            |    "_metadata": {
            |        "total": 0,
            |        "limit": 100,
            |        "offset": 0
            |    }
            |
            |}
          """.stripMargin
          )
      }
    }

    val programListObservable = service.getIdOfEntriesEarlierThan(new DateTime())

    assert( programListObservable.toBlockingObservable.toIterable isEmpty  )

  }

  it should "return an observable with an error element on error" in withStubbedAPI { (service, mockServer) =>

    givenThat {
      get( urlEqualTo(getProgramListRelativeUrl(0, batchSize)) ) willReturn {
        aResponse withStatus(SC_INTERNAL_SERVER_ERROR)
      }
    }

    var hadException = false

    val programListObservable = service.getIdOfEntriesEarlierThan(new DateTime()) onErrorResumeNext { (ex: Throwable) => {
        hadException = true
        Observable.empty[String]
      }
    }

    assert( programListObservable.toBlockingObservable.toIterable isEmpty  )
    assert( hadException )
  }

  it should "return an observable with two item if two elements returned by the API are satisfying the criteria on the fist call" in withStubbedAPI { (service, mockServer) =>

    givenThat {
      get( urlEqualTo(getProgramListRelativeUrl(0, batchSize)) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(
          s"""
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
            |                 "lastUpdated": "2011-09-04T13:45:13Z"
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
          )
      }
    }

    val programListObservable = service.getIdOfEntriesEarlierThan(new DateTime().withYear(2012))

    programListObservable.toBlockingObservable.toIterable.toList should equal (List("dc8f3627-7119-468a-b69b-007bda0fbd6a", "dc8f3627-7119-468a-b69b-007bda0fbd6c"))

  }

  it should "return an observable with three items if two elements returned by the API are satisfying the criteria on the fist call and one on the second" in withStubbedAPI { (service, mockServer) =>

    givenThat {
      get( urlEqualTo(getProgramListRelativeUrl(0, batchSize)) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(
         s"""
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
            |        "total": 120,
            |        "limit": 100,
            |        "offset": 0
            |    }
            |
            |}
          """.stripMargin
          )
      }
    }

    givenThat {
      get( urlEqualTo(getProgramListRelativeUrl(batchSize, batchSize)) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(
          s"""
            |{
            |
            |    "_embedded": { },
            |    "_links": {
            |        "items":
            |        [
            |            {    "href": "http://localhost:${mockServiceListeningPort}/programmes/dc8f3627-7119-468a-b69b-007bda0fbd6d",
            |                 "lastUpdated": "2013-09-03T13:45:13Z"
            |            },
            |            {    "href": "http://localhost:${mockServiceListeningPort}/programmes/dc8f3627-7119-468a-b69b-007bda0fbd6e",
            |                 "lastUpdated": "2013-09-03T13:45:13Z"
            |            },
            |            {    "href": "http://localhost:${mockServiceListeningPort}/programmes/dc8f3627-7119-468a-b69b-007bda0fbd6f",
            |                 "lastUpdated": "2013-09-04T13:45:13Z"
            |            }
            |        ]
            |    },
            |    "_metadata": {
            |        "total": 120,
            |        "limit": 100,
            |        "offset": 100
            |    }
            |
            |}
          """.stripMargin
          )
      }
    }

    val programListObservable = service.getIdOfEntriesEarlierThan(new DateTime(0).withYear(2013).withMonthOfYear(9).withDayOfMonth(4))

    programListObservable.toBlockingObservable.toIterable.toList should equal (List(
      "dc8f3627-7119-468a-b69b-007bda0fbd6a", "dc8f3627-7119-468a-b69b-007bda0fbd6c",
      "dc8f3627-7119-468a-b69b-007bda0fbd6f")
    )

  }

  it should "return an observable all available items in range" in withStubbedAPI { (service, mockServer) =>

    Given("Every call to program list will return two items matching per batch and specified a limit of 1100 entries in the DB, meaning 11 pages")
    givenThat {
      get( urlMatching("""/programmes/\?offset=\d{1,3}&limit=.+""") ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(
          s"""
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
            |        "total": 1100,
            |        "limit": 100,
            |        "offset": 0
            |    }
            |
            |}
          """.stripMargin
          )
      }
    }

    givenThat {
      get( urlMatching("""/programmes/\?offset=\d\d\d\d&limit=.+""") ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(
          s"""
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
            |        "total": 1100,
            |        "limit": 100,
            |        "offset": 1000
            |    }
            |
            |}
          """.stripMargin
          )
      }
    }

    When("I ask to retrieve the full IDs in the DB")
    val programListObservable = service.getIdOfEntriesEarlierThan(new DateTime(0).withYear(2013).withMonthOfYear(9).withDayOfMonth(4))

    Then("I'm  expecting 22 elements returned in the observable")
    programListObservable.toBlockingObservable.toIterable.toList.size should be (22)

  }

  it should "not call the API for next batch if current is the last one" in withStubbedAPI { (service, mockServer) =>

    givenThat {
      get( urlEqualTo(getProgramListRelativeUrl(0, batchSize)) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(
          s"""
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
            |                 "lastUpdated": "2011-09-04T13:45:13Z"
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
          )
      }
    }

    service.getIdOfEntriesEarlierThan(new DateTime().withYear(2012))

    //just necessary on the parallel version
    Thread.sleep(2000)

    verify(0 , getRequestedFor( urlEqualTo( getProgramListRelativeUrl(batchSize, batchSize)) ) )
  }


  it should "retrieve entries from the provided startIndex" in withStubbedAPI { (service, mockServer) =>

    givenThat {
      get( urlEqualTo(getProgramListRelativeUrl(100, batchSize)) ) willReturn {
        aResponse withStatus(SC_OK) withHeader(CONTENT_TYPE, "application/json") withBody(
          s"""
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
            |                 "lastUpdated": "2011-09-04T13:45:13Z"
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
          )
      }
    }

    val programListObservable = service.getIdOfEntriesEarlierThan(new DateTime().withYear(2012), 100)

    programListObservable.toBlockingObservable.toIterable.toList should equal (List("dc8f3627-7119-468a-b69b-007bda0fbd6a", "dc8f3627-7119-468a-b69b-007bda0fbd6c"))

    verify(getRequestedFor( urlEqualTo( getProgramListRelativeUrl(100, batchSize) ) ))
    verify(0, getRequestedFor( urlEqualTo( getProgramListRelativeUrl(0, batchSize) ) ))

  }

  "DateTime format" should "parse valid strings" in {
    val jsonString = "2013-09-04T00:00:00Z"

    import ProgramDatabaseJsonProtocol._

    JsString(jsonString).convertTo[DateTime] should equal (new DateTime(0l).withYear(2013).withMonthOfYear(9).withDayOfMonth(4))

  }

  def withStubbedAPI( test: (ProgramDBService, WireMockServer) => Unit ) {
    
    var service : RestProgramInfoService = null
    var wireMockServer : WireMockServer = null

    try {
      service = createService

      wireMockServer = new WireMockServer(wireMockConfig().port(mockServiceListeningPort));
      wireMockServer.start();

      WireMock.configureFor("localhost", mockServiceListeningPort);

      test(service, wireMockServer)

    } finally {
      if( service != null )
        service.shutdown()

      if(wireMockServer != null)
        wireMockServer.stop()
    }
  }

  def createService : RestProgramInfoService

  def programInfoResponseWithNameAndID(name: String, id: String) : String =
    s"""
      |{
      |    "name": "$name",
      |    "type": "MOVIE",
      |    "uuid": "$id",
      |    "tags": [ ],
      |    "moods": [ ],
      |    "subGenres": [ ],
      |    "uuid": "154791db-2dd6-4c84-b62f-622ee0a5e2da",
      |   "_links": {
      |     "sources": {
      |       "href": "http://localhost:${mockServiceListeningPort}/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources"
      |     }
      |   }
      |}
    """.stripMargin

  def programInfoWithNameAndID(name: String, id: String) : ProgramInfo = ProgramInfo(
    requestId = Some(id),
    name = name,
    `type` = "MOVIE",
    duration = None,
    genre = None,
    subGenres = List.empty,
    releaseYear = None,
    tags = List(),
    moods = List(),
    synopses = None,
    seasonId = None,
    seriesId = None,
    episodeNumber = None,
    seasonNumber = None,
    raw = Some(programInfoResponseWithNameAndID(name, id)),
    uuid = "154791db-2dd6-4c84-b62f-622ee0a5e2da",
    externalSourcesIDs = Some(
      Map(  ( "SOURCE1" -> List("9e695f2eb36cc210VgnVCM1000002c04170a____") ),
            ("BSS" -> List("9e695f2eb36cc210VgnVCM1000002c04170b____", "9e695f2eb36cc210VgnVCM1000002c04170a____")),
            ("PA" -> List("1411307"))
      )
    ),
    _links = ProgramInfoLinks(ProgramDbHref(s"http://localhost:${mockServiceListeningPort}/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources"), None)
  )

  lazy val sourcesJson =
    """
      |{
      |    "entries": [
      |        {
      |            "source": "PA",
      |            "id": "1411307"
      |        },
      |        {
      |            "source": "BSS",
      |            "id": "9e695f2eb36cc210VgnVCM1000002c04170a____"
      |        },
      |        {
      |            "source": "BSS",
      |            "id": "9e695f2eb36cc210VgnVCM1000002c04170b____"
      |        },
      |        {
      |            "source": "SOURCE1",
      |            "id": "9e695f2eb36cc210VgnVCM1000002c04170a____"
      |        }
      |    ],
      |    "_embedded": { },
      |    "_links": {
      |        "self": {
      |            "href": "http://localhost/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources/"
      |        },
      |        "programme": {
      |            "href": "http://localhost/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da"
      |        }
      |    },
      |    "_metadata": { }
      |
      |}
    """.stripMargin

  lazy val validProgramInfoJson =
    s"""
      |{
      |
      |    "name": "The Karate Kid",
      |    "aka": [
      |        "karate kid",
      |        "the karate kid 2010",
      |        "karate kid 2010"
      |    ],
      |    "episodeNumber": 0,
      |    "type": "MOVIE",
      |    "duration": 8400,
      |    "certificate": "PG",
      |    "releaseYear": 2010,
      |    "tags": [
      |        "family_drama",
      |        "family_action",
      |        "sport_family",
      |        "martial_arts",
      |        "sport_drama",
      |        "coming_of_age"
      |    ],
      |    "moods": [
      |        "Inspiring",
      |        "Feel Good",
      |        "Exciting"
      |    ],
      |    "synopses": [
      |        {
      |            "text": "Jackie Chan is the kung fu master",
      |            "source": "BSS"
      |        },
      |        {
      |            "text": "(2010) Family martial arts adventure.",
      |            "source": "SOURCE1"
      |        }
      |    ],
      |    "genre": "movies",
      |    "subGenres": [
      |        "family",
      |        "action"
      |    ],
      |    "uuid": "154791db-2dd6-4c84-b62f-622ee0a5e2da",
      |    "_embedded": { },
      |    "_links": {
      |        "self": {
      |            "href": "http://localhost:${mockServiceListeningPort}/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da"
      |        },
      |        "images": {
      |            "landscape": {
      |                "href": "http://localhost/pd-image/193982/l/{width}",
      |                "templated": true,
      |                "example": "http://localhost/pd-image/193982/l/1024",
      |                "comments": [
      |                    "max width 1024"
      |                ]
      |            },
      |            "portrait": {
      |                "href": "http://localhost/pd-image/193982/p/{height}",
      |                "templated": true,
      |                "example": "http://localhost/pd-image/193982/p/1024",
      |                "comments": [
      |                    "max height 1024"
      |                ]
      |            }
      |        },
      |        "vod": {
      |            "href": "http://localhost:${mockServiceListeningPort}/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/airings?filter=VOD"
      |        },
      |        "linear": {
      |            "href": "http://localhost:${mockServiceListeningPort}/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/airings?filter=LINEAR"
      |        },
      |        "sources": {
      |            "href": "http://localhost:${mockServiceListeningPort}/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources"
      |        }
      |    },
      |    "_metadata": {
      |        "lastUpdated": "2013-12-09T05:19:12Z"
      |    }
      |
      |}
    """.stripMargin

  def expectedProgramInfo(requestId: String) = ProgramInfo(
    requestId = Some(requestId),
    name = "The Karate Kid",
    `type` = "MOVIE",
    duration = Some(8400l),
    genre = Some("movies"),
    subGenres = List("family","action"),
    releaseYear = Some(2010),
    tags = List("family_drama", "family_action", "sport_family","martial_arts","sport_drama","coming_of_age"),
    moods = List("Inspiring","Feel Good", "Exciting"),
    synopses = Some(List(
        Synopse("Jackie Chan is the kung fu master", "BSS"),
        Synopse("(2010) Family martial arts adventure.","SOURCE1")
      )
    ),
    seasonId = None,
    seriesId = None,
    episodeNumber = Some(0),
    seasonNumber = None,
    raw = Some(validProgramInfoJson),
    uuid = "154791db-2dd6-4c84-b62f-622ee0a5e2da",
    externalSourcesIDs = Some(
      Map(  "PA" -> List("1411307"),
            "BSS" -> List("9e695f2eb36cc210VgnVCM1000002c04170b____", "9e695f2eb36cc210VgnVCM1000002c04170a____" ),
            "SOURCE1" -> List("9e695f2eb36cc210VgnVCM1000002c04170a____")
        )
    ),
    _links = ProgramInfoLinks(
      sources = ProgramDbHref(s"http://localhost:${mockServiceListeningPort}/programmes/154791db-2dd6-4c84-b62f-622ee0a5e2da/sources"),
      season = None
    )
  )


}