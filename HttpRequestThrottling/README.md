Spray Request Throttling
====================

Spray Client extension to allow limitation of client request frequency and number of parallel requests

## Dependencies:

- Scala 2.10
- Spray Client 1.2.0
- Akka 2.2.3
- Akka_testkit 2.2.3
- ScalaTest 2.10
- WireMock 1.38 - For HTTP Testing

## API

This is a generalisation of the request throttling logic implemented in the Reactive Rest Client project on my GitHub.
The idea is to create a generic mechanism to allow the throttling of all the messages sent and received by a `sendReceive` Spray pipeline.
The work can be easily generalised for different protocols but at the moment I'm using it with for HTTP requests.

## Usage

