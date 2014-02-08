package com.pragmasoft.reactive.program.client.service

import com.pragmasoft.reactive.program.api.data.ProgramInfo

trait ProgramDBPersistence {
  def saveProgramInfo(programInfo: ProgramInfo): Unit
  def addIdLookupEntry(programInfo: ProgramInfo): Unit

  def shutdown(): Unit
}


