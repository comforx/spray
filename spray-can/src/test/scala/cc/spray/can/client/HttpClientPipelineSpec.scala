/*
 * Copyright (C) 2011-2012 spray.cc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.can.client

import cc.spray.can.HttpPipelineStageSpec
import cc.spray.io.IoPeer
import org.specs2.mutable.Specification
import com.typesafe.config.ConfigFactory
import akka.testkit.TestActorRef
import akka.actor.Actor
import cc.spray.can.model.{HttpRequest, HttpMethods}

class HttpClientPipelineSpec extends Specification with HttpPipelineStageSpec {

  "The HttpClient pipeline" should {

    "send out a simple HttpRequest to the server" in {
      testFixture(request()) must produce(commands = Seq(SendString(rawRequest())))
    }

    "dispatch an incoming HttpResponse back to the sender" in {
      testFixture(
        request(),
        Received(rawResponse())
      ) must produce(commands = Seq(
        SendString(rawRequest()),
        IoPeer.Tell(system.deadLetters, response(), connectionActor)
      ))
    }

    "properly complete a 3 requests pipelined dialog" in {
      testFixture(
        request("Request 1"),
        request("Request 2"),
        request("Request 3"),
        Received(rawResponse("Response 1")),
        Received(rawResponse("Response 2")),
        Received(rawResponse("Response 3"))
      ) must produce(commands = Seq(
        SendString(rawRequest("Request 1")),
        SendString(rawRequest("Request 2")),
        SendString(rawRequest("Request 3")),
        IoPeer.Tell(system.deadLetters, response("Response 1"), connectionActor),
        IoPeer.Tell(system.deadLetters, response("Response 2"), connectionActor),
        IoPeer.Tell(system.deadLetters, response("Response 3"), connectionActor)
      ))
    }

    "properly handle responses to HEAD requests" in {
      testFixture(
        HttpRequest(method = HttpMethods.HEAD),
        Received {
          prep {
            """|HTTP/1.1 200 OK
               |Server: spray/1.0
               |Date: Thu, 25 Aug 2011 09:10:29 GMT
               |Content-Length: 8
               |
               |"""
          }
        }
      ) must produce(commands = Seq(
        SendString(rawRequest(method = "HEAD")),
        IoPeer.Tell(system.deadLetters, response("12345678").withBody(""), connectionActor)
      ))
    }
  }

  /////////////////////////// SUPPORT ////////////////////////////////

  val connectionActor = TestActorRef(new NamedActor("connectionActor"))

  class NamedActor(val name: String) extends Actor {
    def receive = { case 'name => sender ! name}
    def getContext = context
  }

  def testFixture: Fixture = {
    new Fixture(testPipeline) {
      override def getConnectionActorContext = connectionActor.underlyingActor.getContext
    }
  }

  def testPipeline = HttpClient.pipeline(
    new ClientSettings(
      ConfigFactory.parseString("""
        spray.can.client.user-agent-header = spray/1.0
        spray.can.client.idle-timeout = 50 ms
        spray.can.client.reaping-cycle = 0  # don't enable the TickGenerator
      """)
    ),
    system.log
  )

}
