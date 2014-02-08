package com.pragmasoft.reactive.program.client.service.persistence

import com.escalatesoft.subcut.inject.Injectable
import com.escalatesoft.subcut.inject._
import com.pragmasoft.reactive.program.api.data.jsonconversions.ProgramDatabaseJsonProtocol
import org.slf4j.LoggerFactory
import scala.util.Failure

import com.pragmasoft.reactive.program.client.service.ProgramDBPersistence
import com.pragmasoft.reactive.program.client.service.persistence.ProgramInfoHBasePersistenceSupport._
import scala.Some
import com.pragmasoft.reactive.program.api.data.ProgramInfo

class ProgramDBHBasePersistence(implicit val bindingModule: BindingModule) extends ProgramDBPersistence with Injectable {
  val log = LoggerFactory.getLogger(classOf[ProgramDBHBasePersistence])

  val hbaseQuorum = injectProperty[String] ("hbase.quorum")
  val hbaseQuorumPort = injectProperty[Int] ("hbase.port")
  val mpodIdConversionTableName = injectProperty[String] ("hbase.table.mpod.lookup")
  val idConversionTableName = injectProperty[String] ("hbase.table.id.lookup")
  val programInfoTableName = injectProperty[String] ("hbase.table.programInfo")
  val tablePoolSize = injectProperty[Int] ("hbase.table.pool.size")

  require(!hbaseQuorum.isEmpty, "Invalid empty value for hbaseQuorum")
  require(!mpodIdConversionTableName.isEmpty, "Invalid empty value for mpodIdConversionTableName")
  require(!programInfoTableName.isEmpty, "Invalid empty value for programInfoTableName")

  log.info(s"Connecting with hbase at $hbaseQuorum port $hbaseQuorumPort using tables $mpodIdConversionTableName and $programInfoTableName")

  val hbaseConf = HBaseConfiguration.create()
  hbaseConf.clear()
  hbaseConf.set("hbase.zookeeper.quorum", hbaseQuorum)
  hbaseConf.set("hbase.zookeeper.property.clientPort", hbaseQuorumPort.toString )

  val quorum = hbaseConf.get("hbase.zookeeper.quorum")
  //Without retrieving the quorum here the set value was ignored at write-time
  require(quorum == hbaseQuorum, "HBase quorum is not the one set after configuration!!!")

  val tablePool: HTablePool = new HTablePool(hbaseConf, tablePoolSize)

  def saveProgramInfo(programInfo: ProgramInfo) : Unit = {
    try {
      log.debug(s"Saving program ID ${programInfo.uuid}")

      val programDataTable = tablePool.getTable(programInfoTableName)
      programDataTable.put(putForProgramInfoStorage(programInfo))
      programDataTable.flushCommits()
      programDataTable.close()
    } catch  {
      case exception: Throwable => log.error("Exception trying to save program info", exception)
    }
  }

  def addIdLookupEntry(programInfo: ProgramInfo): Unit = {
    try {
      log.debug(s"Saving id conversion row for program ID ${programInfo.uuid}")

      val mpodIdConversionTable = tablePool.getTable(idConversionTableName)
      mpodIdConversionTable.put(putForIdLookup(programInfo))
      mpodIdConversionTable.flushCommits()
      mpodIdConversionTable.close()
    } catch  {
      case exception: Throwable => log.error("Exception trying to save program info", exception)
    }
  }

  def shutdown(): Unit = {
    log.info("Closign HBase session")
    tablePool.close()
  }
}
