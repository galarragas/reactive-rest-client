package com.pragmasoft.reactive.program.client.service.persistence

import org.apache.hadoop.hbase.util.Bytes._
import com.pragmasoft.reactive.program.api.data.{Synopse, ProgramInfo}
import org.apache.hadoop.hbase.client.Put
import com.pragmasoft.reactive.program.api.data.jsonconversions.ProgramDatabaseJsonProtocol
import ProgramDatabaseJsonProtocol._

object ProgramInfoHBasePersistenceSupport {
  type OptionalProgramInfoSerialization = ProgramInfo => Option[(String, Put)]

  val PROGRAM_INFO_COLUMN_FAMILY = toBytes("data")

  val CF_UUID = toBytes("uuid")
  val CF_NAME = toBytes("name")
  val CF_TYPE = toBytes("type")
  val CF_DURATION = toBytes("duration")
  val CF_GENRE = toBytes("genre")
  val CF_SUB_GENRES = toBytes("subGenres")
  val CF_RELEASE_YEAR = toBytes("releaseYear")
  val CF_TAGS = toBytes("tags")
  val CF_MOODS = toBytes("moods")
  val CF_SYNOPSES = toBytes("synopses")
  val CF_SEASON_NUMBER = toBytes("seasonNumber")
  val CF_EPISODE_NUMBER = toBytes("episodeNumber")
  val CF_SEASON_ID = toBytes("seasonId")
  val CF_SERIES_ID = toBytes("seriesId")
  val CF_SOURCES = toBytes("sources")

  val CF_ONDEMAND_ID = toBytes("ONDEMAND")
  val CF_ROVI_ID = toBytes("ROVI")
  val CF_PA_ID = toBytes("PA")

  val LOOKUP_COLUMNS : List[(String, Array[Byte])] =  List(
    "ONDEMAND" -> CF_ONDEMAND_ID,
    "ROVI" -> CF_ROVI_ID,
    "PA" -> CF_PA_ID
  )

  implicit class ProgramInfoPut(val put: Put) {
    def printStringList(list: List[String]): String = list map { elem => s"$elem" } mkString("[", ",", "]")
    def printSynopseList(list: List[Synopse]): String = list map { synopse : Synopse => synopseFormat.write(synopse).toString } mkString("[", ",", "]")

    val timestamp = System.currentTimeMillis

    // Every non-string field will be converted to string since the Hive-HBase connector can't handle anything but String or Binary data

    def addByteArray(columnFamily: Array[Byte], value: Array[Byte]) : Put = put.add(PROGRAM_INFO_COLUMN_FAMILY, columnFamily, timestamp, value)

    def addString(columnFamily: Array[Byte], value: String) : Put = put.add(PROGRAM_INFO_COLUMN_FAMILY, columnFamily, timestamp, toBytes(value))

    def addStringOp(columnFamily: Array[Byte], valueOp: Option[String]) : Put  =
      valueOp map { value : String => addByteArray(columnFamily, toBytes(value)) } getOrElse put

    def addIntOp(columnFamily: Array[Byte], value: Option[Int]) : Put = addStringOp(columnFamily, value map { _.toString } )

    def addLongOp(columnFamily: Array[Byte], value: Option[Long]) : Put = addStringOp(columnFamily, value map { _.toString } )

    def addStringList(columnFamily: Array[Byte], value: List[String]) : Put = if(!value.isEmpty) addString(columnFamily, printStringList(value) ) else put

    def addStringListOp(columnFamily: Array[Byte], value: Option[List[String]]) : Put = value map { addStringList(columnFamily, _) } getOrElse put

    def addSynopse(columnFamily: Array[Byte], value: Option[List[Synopse]]) : Put = addStringOp(columnFamily, value map { printSynopseList(_) } )

    import spray.json._
    import DefaultJsonProtocol._
    private[ProgramInfoPut] case class MapItem(key: String, value: String)
    private[ProgramInfoPut] implicit val mapItemFormat = jsonFormat2(MapItem)



    def addMappingList(columnFamily: Array[Byte], value: Option[Map[String, List[String]]]) : Put = {
      def disambiguateMappingsForKey(key: String, valueList: Seq[String]) : Seq[MapItem] = {
        valueList.foldRight (List.empty[MapItem]) { (currValue: String, list: List[MapItem]) =>
          (if(list.isEmpty) MapItem(key, currValue) else MapItem(key + list.size, currValue)) :: list
        }
      }

      def asString(mappingList: Map[String, Seq[String]]) : String = {
        val mappingListObj : Seq[MapItem] = mappingList.toList flatMap { case (key: String, values: Seq[String]) => disambiguateMappingsForKey(key, values) }

        mappingListObj.toJson.toString
      }

      addStringOp(columnFamily, value flatMap { list => if(list.isEmpty) None else Some(asString(list)) } )
    }
  }

  val serializeProgramInfoForStorage : OptionalProgramInfoSerialization = programInfo => Some(programInfo.uuid, putForProgramInfoStorage(programInfo))
  val serializeProgramInfoForIdLookup : OptionalProgramInfoSerialization = programInfo => Some(programInfo.uuid, putForIdLookup(programInfo))

  def putForProgramInfoStorage(programInfo: ProgramInfo) : Put = new Put(toBytes(programInfo.uuid))
    .addString(CF_NAME, programInfo.name)
    .addString(CF_TYPE, programInfo.`type`)
    .addLongOp(CF_DURATION, programInfo.duration)
    .addStringOp(CF_GENRE, programInfo.genre)
    .addStringList(CF_SUB_GENRES, programInfo.subGenres)
    .addIntOp(CF_RELEASE_YEAR, programInfo.releaseYear)
    .addStringList(CF_TAGS, programInfo.tags)
    .addStringList(CF_MOODS, programInfo.moods)
    .addSynopse(CF_SYNOPSES, programInfo.synopses)
    .addStringOp(CF_SEASON_ID, programInfo.seasonId)
    .addStringOp(CF_SERIES_ID, programInfo.seriesId)
    .addIntOp(CF_SEASON_NUMBER, programInfo.seasonNumber)
    .addIntOp(CF_EPISODE_NUMBER, programInfo.episodeNumber)
    .addMappingList(CF_SOURCES, programInfo.externalSourcesIDs)

  def findSourceMapping(programInfo: ProgramInfo, source: String) : Option[List[String]] = programInfo.externalSourcesIDs match {
    case Some(sourceIdList) => sourceIdList find {case (sourceId, _) => sourceId == source} map { _._2 }
    case None => None
  }

  def findSingleSourceMapping(programInfo: ProgramInfo, source: String) : Option[String] = findSourceMapping(programInfo, source) map { _.head }

  def putForIdLookup(programInfo: ProgramInfo) : Put = {
    LOOKUP_COLUMNS.foldLeft (new Put(toBytes(programInfo.uuid))) { case (put, (idKey, idColumnFamilyName)) =>
      findSingleSourceMapping(programInfo, idKey) map { idValue : String => put.addString(idColumnFamilyName, idValue) } getOrElse put
    }
  }
}
