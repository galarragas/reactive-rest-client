package com.pragmasoft.reactive.program.client.service.persistence

import com.pragmasoft.reactive.program.client.service.ProgramDBPersistence
import com.pragmasoft.reactive.program.api.data.ProgramInfo


class ProgramDBNullPersistence extends ProgramDBPersistence {
  def saveProgramInfo(programInfo: ProgramInfo): Unit = {}
  def addIdLookupEntry(programInfo: ProgramInfo): Unit = {}

  def shutdown(): Unit = {}

}

