package com.pragmasoft.reactive.program.api.data.jsonconversions

import spray.json.{JsString, JsValue, RootJsonFormat, DefaultJsonProtocol}
import com.pragmasoft.reactive.program.api.data._
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import com.pragmasoft.reactive.program.api.data._
import com.pragmasoft.reactive.program.api.data.ProgramList
import com.pragmasoft.reactive.program.api.data.ProgramSeasonLinks
import com.pragmasoft.reactive.program.api.data.ProgramListItem
import com.pragmasoft.reactive.program.api.data.ProgramSources
import com.pragmasoft.reactive.program.api.data.ProgramSourceInfo
import com.pragmasoft.reactive.program.api.data.ProgramInfoLinks
import com.pragmasoft.reactive.program.api.data.Synopse
import com.pragmasoft.reactive.program.api.data.ProgramSeasonInfo
import com.pragmasoft.reactive.program.api.data.ProgramListLinks
import com.pragmasoft.reactive.program.api.data.ProgramListMetadata
import com.pragmasoft.reactive.program.api.data.ProgramDbHref

object ProgramDatabaseJsonProtocol extends DefaultJsonProtocol {
  implicit val programDbHrefFormat = jsonFormat1(ProgramDbHref)
  implicit val programSourceInfoFormat = jsonFormat2(ProgramSourceInfo)
  implicit val programInfoLinksFormat = jsonFormat2(ProgramInfoLinks)
  implicit val programSourcesEntry = jsonFormat1(ProgramSources)
  implicit val synopseFormat = jsonFormat2(Synopse)
  implicit def programDatabaseResultFormat = jsonFormat18(ProgramInfo.apply)
  implicit object jodaDateTimeFormat extends RootJsonFormat[DateTime] {
    //2013-12-17T13:46:19Z
    val DATE_FORMAT = ISODateTimeFormat.dateTimeParser()

    // I have problems in parsing the day of the week as delivered by programDB API. Stripping it off
    val validDateFormat = """(\d\d\d\d-\d\d-\d\d).*(\d\d:\d\d:\d\d)(.+)""".r

    def write(obj: DateTime): JsValue = JsString(DATE_FORMAT.print(obj))

    def read(json: JsValue): DateTime = {
      json match {
        case JsString(date) => DATE_FORMAT.parseDateTime(date)

        case _ => throw new IllegalArgumentException(s"Expected JsString with content having format $validDateFormat for a DateTime attribute value")
      }
    }
  }
  implicit val programListItemFormat = jsonFormat2(ProgramListItem)
  implicit val programListLinksFormat = jsonFormat1(ProgramListLinks)
  implicit val programListMetadataFormat = jsonFormat3(ProgramListMetadata)
  implicit val programListFormat = jsonFormat2(ProgramList)

  implicit val programSeasonLinksFormat = jsonFormat1(ProgramSeasonLinks)
  implicit val programSeasonInfo = jsonFormat4(ProgramSeasonInfo)
}
