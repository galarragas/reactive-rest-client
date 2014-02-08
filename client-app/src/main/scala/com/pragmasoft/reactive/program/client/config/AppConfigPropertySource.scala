package com.pragmasoft.reactive.program.client.config

import com.escalatesoft.subcut.inject.config.{ConfigPropertySource, Undefined, Defined, ConfigProperty}
import com.typesafe.config.{Config, ConfigFactory}
import java.io.InputStreamReader

object AppConfigPropertySource {
  val CLASSPATH_PATH_PREFIX = "classpath:"

  def loadAppConfig(configPath : String ) : Config = {
    if(configPath.startsWith(CLASSPATH_PATH_PREFIX)) {
      ConfigFactory.parseReader( new InputStreamReader(getClass.getClassLoader.getResourceAsStream(configPath.drop(CLASSPATH_PATH_PREFIX.length))))
    } else {
      ConfigFactory.parseFile(new java.io.File(configPath))
    }
  }

  def apply(configPath: String) :  AppConfigPropertySource = apply(loadAppConfig(configPath))
  def apply(appConfig: Config) :  AppConfigPropertySource = new AppConfigPropertySource(appConfig)
}

class AppConfigPropertySource(appConfig: Config) extends ConfigPropertySource {
  override def getOptional(propertyName: String): ConfigProperty =
    if(appConfig.hasPath(propertyName)) Defined(propertyName, appConfig.getString(propertyName)) else Undefined(propertyName)
}
