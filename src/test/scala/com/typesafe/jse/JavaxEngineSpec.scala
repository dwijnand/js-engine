package com.typesafe.jse

import org.specs2.mutable.Specification
import com.typesafe.jse.Engine.JsExecutionResult
import java.io.File
import scala.collection.immutable
import akka.pattern.ask
import org.specs2.time.NoTimeConversions
import akka.util.Timeout
import akka.actor.{ActorRef, ActorSystem}
import scala.concurrent.Await
import java.util.concurrent.TimeUnit

class JavaxEngineSpec extends Specification {

  def withEngine[T](block: ActorRef => T): T = {
    val system = ActorSystem()
    val engine = system.actorOf(JavaxEngine.props(engineName = "js"))
    try block(engine) finally {
      AkkaCompat.terminate(system)
    }
  }

  "A JavaxEngine" should {

    "execute some javascript by passing in a string arg and comparing its return value" in {
      withEngine {
        engine =>
          val f = new File(classOf[JavaxEngineSpec].getResource("test-javax.js").toURI)
          implicit val timeout = Timeout(5000L, TimeUnit.MILLISECONDS)

          val futureResult = (engine ? Engine.ExecuteJs(f, immutable.Seq("999"), timeout.duration)).mapTo[JsExecutionResult]
          val result = Await.result(futureResult, timeout.duration)
          new String(result.error.toArray, "UTF-8").trim must_== ""
          new String(result.output.toArray, "UTF-8").trim must_== "999"
      }
    }

    "execute some javascript by passing in a string arg and comparing its return value expecting an error" in {
      withEngine {
        engine =>
          val f = new File(classOf[JavaxEngineSpec].getResource("test-node.js").toURI)
          implicit val timeout = Timeout(5000L, TimeUnit.MILLISECONDS)

          val futureResult = (engine ? Engine.ExecuteJs(f, immutable.Seq("999"), timeout.duration)).mapTo[JsExecutionResult]
          val result = Await.result(futureResult, timeout.duration)
          new String(result.output.toArray, "UTF-8").trim must_== ""
          new String(result.error.toArray, "UTF-8").trim must contain("""ReferenceError: "require" is not defined""")
      }
    }
  }

}
