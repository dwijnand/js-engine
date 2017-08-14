package com.typesafe.jse

import akka.actor.ActorSystem

object AkkaCompat {
  def terminate(system: ActorSystem): Unit = {
    system.shutdown()
    system.awaitTermination()
  }
}
