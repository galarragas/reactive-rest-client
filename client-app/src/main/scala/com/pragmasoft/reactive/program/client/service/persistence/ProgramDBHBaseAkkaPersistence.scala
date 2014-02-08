package com.pragmasoft.reactive.program.client.service.persistence

import com.escalatesoft.subcut.inject.{Injectable, BindingModule}
import org.slf4j.LoggerFactory
import akka.actor._
import com.typesafe.config.ConfigFactory
import akka.routing.RoundRobinRouter
import akka.actor.SupervisorStrategy.Stop
import scala.concurrent.duration._
import com.escalatesoft.subcut.inject.NewBindingModule._
import com.pragmasoft.reactive.program.client.service.ProgramDBPersistence
import com.pragmasoft.reactive.program.client.service.persistence.ProgramInfoHBasePersistenceSupport._
import akka.routing.Broadcast
import akka.actor.AllForOneStrategy
import com.pragmasoft.reactive.program.api.data.ProgramInfo
import org.apache.hadoop.hbase.{TableNotFoundException, HBaseConfiguration}
import org.apache.hadoop.hbase.client.HTable
import com.pragmasoft.reactive.program.client.service.actors.WriteToHTableActor

class ProgramDBHBaseAkkaPersistence(implicit val bindingModule: BindingModule) extends ProgramDBPersistence with Injectable {
  val log = LoggerFactory.getLogger(classOf[ProgramDBHBasePersistence])

  var isInternalActorSystem = false

  val hbaseQuorum = injectProperty[String] ("hbase.quorum")
  val hbaseQuorumPort = injectProperty[Int] ("hbase.port")
  val mpodIdConversionTableName = injectProperty[String] ("hbase.table.mpod.lookup")
  val iddConversionTableName = injectProperty[String] ("hbase.table.id.lookup")
  val actorPoolSize = injectProperty[Int]("hbase.table.pool.size")
  val programInfoTableName = injectProperty[String] ("hbase.table.programInfo")
  implicit val actorSystem = injectOptional[ActorSystem] getOrElse(createActorSystem())
  val writeBufferSize = injectProperty[Long]("hbase.write.buffer.size")
  val sprayConfFile = injectProperty[String]("spray.config.file.name")

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


  val failForPersistentIOErrorStrategy = AllForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1.minute) {
    case ex:TableNotFoundException => Stop
    case ex:ActorInitializationException => Stop
  }

  val programInfoPersistenceClusterRouter = createWriterActor(programInfoTableName, serializeProgramInfoForStorage)
  val programInfoIdLookupClusterRouter = createWriterActor(iddConversionTableName, serializeProgramInfoForIdLookup)

  def saveProgramInfo(programInfo: ProgramInfo): Unit = programInfoPersistenceClusterRouter ! programInfo

  def addIdLookupEntry(programInfo: ProgramInfo): Unit = programInfoIdLookupClusterRouter ! programInfo

  def shutdown(): Unit = {
    programInfoPersistenceClusterRouter ! Broadcast(PoisonPill)
    programInfoIdLookupClusterRouter ! Broadcast(PoisonPill)

    if(isInternalActorSystem) {
      log.info("Shutting down actor system")
      actorSystem.shutdown()
    }
  }

  def createWriterActor(tableName: String, serializationFunction: OptionalProgramInfoSerialization): ActorRef = {
    def createTableForActor(tableName: String) : HTable = {
      val table = new HTable(hbaseConf, tableName)
      table.setAutoFlush(false)
      table.setWriteBufferSize(writeBufferSize)
      table
    }

    val configBindingModule = newBindingModule { bindingModule =>
      import bindingModule._

      bind [HTable] toProvider createTableForActor(tableName)
      bind [OptionalProgramInfoSerialization] toSingle serializationFunction
    }

    actorSystem.actorOf(
      WriteToHTableActor.props(configBindingModule)
        .withRouter( RoundRobinRouter(nrOfInstances = actorPoolSize, supervisorStrategy = failForPersistentIOErrorStrategy) )
    )
  }

  private[ProgramDBHBaseAkkaPersistence] def createActorSystem() : ActorSystem = {
    isInternalActorSystem = true
    // This is a workaround for the startup problems encountered when running in the cluster
    System.setProperty("spray.version", "1.0")
    System.setProperty("akka.version", "2.2.3")
    ActorSystem("program-info-client", ConfigFactory.parseResources(sprayConfFile))
  }

}
