package com.pragmasoft.reactive.program.api.rest

import com.escalatesoft.subcut.inject.NewBindingModule._
import com.escalatesoft.subcut.inject.config.PropertiesConfigPropertySource

class ParallelReadRestProgramInfoServiceSpec extends RestProgramInfoServiceSpec {
  val mockServiceListeningPort = 39999

  def createService: RestProgramInfoService = {

    val properties : Map[String, String] = Map(
      "programdb.api.base-address" -> s"http://localhost:$mockServiceListeningPort",
      "programdb.api.key" -> "apiKey",
      "programdb.api.version" -> "apiVersion",
      "programdb.api.timeout.seconds" -> timeout.toSeconds.toString,
      "programdb.api.pageSize" -> batchSize.toString,
      "programdb.api.maxRequestsPerMinute" -> "120",
      "programdb.api.parallelRead" -> "2",
      "spray.config.file.name" -> "spray.conf"
    )

    implicit val bindingModule = newBindingModuleWithConfig( PropertiesConfigPropertySource { properties } )

    new ParallelReadRestProgramInfoService
  }
}