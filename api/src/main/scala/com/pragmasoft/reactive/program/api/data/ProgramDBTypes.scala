package com.pragmasoft.reactive.program.api.data

import org.joda.time.DateTime

case class ProgramSourceInfo(source: String, id: String)

case class ProgramSources(entries: List[ProgramSourceInfo])

case class ProgramDbHref(href: String)

case class ProgramInfoLinks(sources: ProgramDbHref, season: Option[ProgramDbHref])

case class Synopse(text: String, source: String)

case class ProgramInfo(requestId: Option[String], uuid: String, name: String, `type`: String, duration: Option[Long],
                       genre: Option[String], subGenres: List[String],
                       releaseYear: Option[Int], tags: List[String], moods: List[String],
                       synopses: Option[List[Synopse]], seasonId: Option[String], seriesId: Option[String],
                       seasonNumber: Option[Int], episodeNumber: Option[Int], externalSourcesIDs: Option[Map[String, List[String]]],
                       _links: ProgramInfoLinks, raw: Option[String])

case class ProgramListMetadata(total: Long, limit: Int, offset: Long)

case class ProgramListItem(href: String, lastUpdated: DateTime)

case class ProgramListLinks(items: List[ProgramListItem])

case class ProgramList(_metadata: ProgramListMetadata, _links: ProgramListLinks)

case class ProgramSeasonLinks(series: ProgramDbHref)

case class ProgramSeasonInfo(name: String, seasonNumber: Int, uuid: String, _links: ProgramSeasonLinks)

object ProgramInfo {
  def emptyProgramInfo(id: String, errorMsg: Some[String]): ProgramInfo =
    ProgramInfo(Some(id), "",  "", "", None, None, List.empty, None,
      List.empty, List.empty, None, None, None, None, Some(-1), None, ProgramInfoLinks(ProgramDbHref(""), None),
      errorMsg
    )
}
