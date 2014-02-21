package com.pragmasoft.reactive.throttling.actors

import akka.actor._
import spray.http.{HttpResponse, HttpRequest}
import spray.http.HttpRequest
import spray.http.HttpResponse
import scala.concurrent.duration._
import com.pragmasoft.reactive.throttling.actors.handlerspool.{OneActorPerRequestPool, FixedSizePool, HandlerFactory, SetHandlerPool}
import spray.can.Http
import akka.io
import spray.util.actorSystem
import com.pragmasoft.reactive.throttling.threshold.Frequency
import scala.concurrent.ExecutionContext
import akka.util.Timeout

abstract class AbstractHttpRequestReplyThrottlingCoordinator(
                                                              transport: ActorRef,
                                                              frequencyThreshold: Frequency,
                                                              requestTimeout: FiniteDuration
 ) extends RequestReplyThrottlingCoordinator[HttpRequest](transport, frequencyThreshold, requestTimeout) with HandlerFactory {

  def createHandler() = context.actorOf(HttpRequestRequestReplyHandler.props(self))
}

class FixedPoolSizeHttpRequestReplyThrottlingCoordinator(
                                                          transport: ActorRef,
                                                          frequencyThreshold: Frequency,
                                                          requestTimeout: FiniteDuration,
                                                          val poolSize: Int
 ) extends AbstractHttpRequestReplyThrottlingCoordinator(transport, frequencyThreshold, requestTimeout) with FixedSizePool


class HttpRequestReplyThrottlingCoordinator(
                                             transport: ActorRef,
                                             frequencyThreshold: Frequency,
                                             requestTimeout: FiniteDuration
 ) extends AbstractHttpRequestReplyThrottlingCoordinator(transport, frequencyThreshold, requestTimeout) with OneActorPerRequestPool


object HttpRequestReplyThrottlingCoordinator {
  def throttleFrequencyAndParallelRequestsUsingTransport(transport: ActorRef, frequencyThreshold: Frequency, maxParallelRequests: Int, requestTimeout: FiniteDuration) =
    Props(classOf[FixedPoolSizeHttpRequestReplyThrottlingCoordinator], transport, frequencyThreshold, requestTimeout, maxParallelRequests)

  def throttleFrequencyUsingTransport(transport: ActorRef, frequencyThreshold: Frequency, requestTimeout: FiniteDuration) =
    Props(classOf[FixedPoolSizeHttpRequestReplyThrottlingCoordinator], transport, frequencyThreshold, requestTimeout)

  def throttleFrequencyAndParallelRequests(frequencyThreshold: Frequency, maxParallelRequests: Int)
                                 (implicit refFactory: ActorRefFactory, executionContext: ExecutionContext,requestTimeout: Timeout = 60.seconds) =
    Props(classOf[FixedPoolSizeHttpRequestReplyThrottlingCoordinator], io.IO(Http)(actorSystem), frequencyThreshold, requestTimeout.duration, maxParallelRequests)

  def throttleFrequency(frequencyThreshold: Frequency)
                                   (implicit refFactory: ActorRefFactory, executionContext: ExecutionContext, requestTimeout: Timeout = 60.seconds) =
    Props(classOf[FixedPoolSizeHttpRequestReplyThrottlingCoordinator], io.IO(Http)(actorSystem), frequencyThreshold, requestTimeout.duration)
}

class HttpRequestRequestReplyHandler(coordinator: ActorRef) extends RequestReplyHandler[HttpResponse](coordinator: ActorRef)

object HttpRequestRequestReplyHandler {
  def props(coordinator: ActorRef) = Props(classOf[HttpRequestRequestReplyHandler], coordinator)
}
