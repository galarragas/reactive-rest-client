package com.pragmasoft.reactive.program.api.config

import com.escalatesoft.subcut.inject.config.ConfigProperty
import scala.concurrent.duration._

object ConfigConversions {

  implicit def toDuration(prop: ConfigProperty): Duration = {
    val amount: Long = prop.value.toLong
    val timeQualifier: String = prop.name.split('.').last

    timeQualifier match {
      case "seconds" | "secs" => amount.seconds
      case "hours"  => amount.hours
      case _ => amount.milliseconds
    }
  }

}
