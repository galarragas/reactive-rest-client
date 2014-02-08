package com.pragmasoft.reactive.program.api.rest

import scala.concurrent.duration._
import com.escalatesoft.subcut.inject.NewBindingModule._
import com.escalatesoft.subcut.inject.config.PropertiesConfigPropertySource
import com.escalatesoft.subcut.inject._

class SequentialReadRestProgramInfoServiceSpec extends RestProgramInfoServiceSpec {
  val mockServiceListeningPort = 29999

  def createService: RestProgramInfoService = {

    val properties : Map[String, String] = Map(
      "programdb.api.base-address" -> s"http://localhost:$mockServiceListeningPort",
      "programdb.api.key" -> "apiKey",
      "programdb.api.version" -> "apiVersion",
      "programdb.api.timeout.seconds" -> timeout.toSeconds.toString,
      "programdb.api.pageSize" -> batchSize.toString,
      "spray.config.file.name" -> "spray.conf"
    )

    implicit val bindingModule = newBindingModuleWithConfig( PropertiesConfigPropertySource { properties } )

    new SequentialReadRestProgramInfoService
  }
}