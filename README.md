Reactive Rest Client
====================

Sample of a rest client written using Spray AKKA and RxScala

It is based on a concrete use case I recently developed and the main idea is to evolve this project into a general reactive client to be used only implementing the data marshalling.
  
This project consists of three sub-projects:

- API: A Client API to interface with a Rest Program Service, giving information (description, genre, source info) about TV programs
- Client App: A client application using the API to browse the content of the Program Service and dumping its content on HBase
in raw format and after doing some transformation for specific lookups
- HttpRequestThrottling: This is a generalisation of the request throttling logic implemented in the API project.

The idea of the application is to give a concrete example of the usage of Spray (Spray Client and Spray-Json), AKKA and ScalaRx.

It is based on a concrete use case I recently developed and the main idea is to evolve this project into a general reactive
  client to be used only implementing the data marshalling.

## Technologies used:

- Scala 2.10
- RxScala 0.16.0
- Spray Client 1.2.0
- Spray Json 1.2.4
- Akka 2.2.3
- Akka_testkit 2.2.3
- Subcut-Ext 2.0 (my own extension of the Subcut project) for Dependency Injection
- ScalaTest 2.10
- WireMock 1.38 - For HTTP Testing
- HBase Client 0.94.6

## API

The API layer exposes its service in both Sync and Async flavor using respectively a Try[T] and Future[T] result type.
It also exposes a rx.Observable stream of responses for long lasting queries.

I am assuming that the Program Service is quite slow and also limiting the incoming traffic. If any client is becoming too
eager will be delayed and eventually temporarily blocked. This is only affecting the `getEntriesEarlierThan` and `getIdOfEntriesEarlierThan`
APIs since are the one potentially browsing the full remote DB content.

To manage performance issues related to the service response time two implementations of the main Service are given:

- SequentialReadRestProgramInfoService is doing the call to the "programmes" in a single thread and then publishing any
ID (or entry) matching the lastModifiedDate criteria

- ParallelReadRestProgramInfoService is instead executing parallel read. To handle the trafic limitation enforced by the
remote service, the client is able to limit its throughput. To do so I am doing the calls using AKKA and using the Pull Pattern.
Actors are used to handle the parallel request and to control the request rate and adapt their speed to the configurable
throughput.

## Client App

The client application shows how the API layer can be used in a concrete case. The case is the acquisition of all the
information available in the Program Service and modified after a supplied date.
This test case shows how to deal with a potential large amount of information in an efficient way.
The information is read from the Program Service and written to HBase. The application explores two possible ways to
handle the writes:

- With auto-flush and using an HTablePool. This is the simplest and less efficient way. the version of HBase Client used
(that was the one available in the test environment) wasn't allowing to use the `HConnection` object as suggested in
http://hbase.apache.org/book/client.html so it is based on the usage of an HTablePool

- Using a write buffer and client-controlled flush. This is done using a pool of actors to handle the write operations

## HttpRequestThrottling

This is a generalisation of the request throttling logic implemented in the API project.
The idea is to create a generic mechanism to allow the throttling of all the messages sent and received by a `sendReceive` Spray pipeline.
The work can be generalised for different protocols but at the moment is focused on HTTP only.

## What Else

The application also shows the usage of some other framework I found very useful in the past:

- Subcut: To handle the Dependency Injection. The version used here is a customisation of the official one I did to
support easier injection of values from the configuration files

- WireMock: To test HTTP clients against a stubbed http server

Note: Tests are sometimes a bit flacky because of some ports remaining hung. I'm still working on it.
