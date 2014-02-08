package com.pragmasoft.reactive.program.client.service.actors

import akka.actor.{Props, ActorLogging, Actor}
import com.escalatesoft.subcut.inject.{BindingModule, Injectable}
import org.apache.hadoop.hbase.client.{Put, HTable}
import org.apache.hadoop.hbase.util.Bytes
import com.pragmasoft.reactive.program.client.service.persistence.ProgramInfoHBasePersistenceSupport.OptionalProgramInfoSerialization
import com.pragmasoft.reactive.program.api.data.ProgramInfo

object WriteToHTableActor {
  def props(bindingModule: BindingModule): Props =
    Props.create(classOf[WriteToHTableActor], bindingModule)
}

class WriteToHTableActor(val bindingModule: BindingModule) extends Actor with ActorLogging with Injectable {

  val targetTable = inject[HTable]
  val targetTableName = Bytes.toString(targetTable.getTableName)
  val serializationFunction = inject[OptionalProgramInfoSerialization]

  def receive = {
    case programInfo:ProgramInfo => serializationFunction(programInfo) foreach { case (id: String, put: Put) =>
      log.debug("Saving program info with id {} in table {}", id, targetTableName)

      try {
        targetTable.put(put)
      } catch {
        case ex: Throwable =>
          log.error(s"Exception in actor ${self.path}", ex)
          targetTable.flushCommits()
          throw ex
      }
    }
  }

  override def postStop() : Unit = {
    log.info(s"Shutting down actor ${self.path}")
    targetTable.flushCommits()
    targetTable.close()
  }
}
