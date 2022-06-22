package minichain

import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.Await
import scala.concurrent.duration._

object Launcher extends App {

  val guardian: Behavior[Nothing] =
    Behaviors.setup[Nothing] { ctx =>
      implicit val system: ActorSystem[Nothing] = ctx.system
      val blockchainRef = ctx.spawn(Blockchain.behavior(InMemoryBlockchain.fromGenesis), "Blockchain")
      val memPoolRef = ctx.spawn(MemPool.behavior(), "MemPool")

      Demo.peerTxSource(memPoolRef).run()
      Demo.walletTxsSource(memPoolRef).run()
      Miner.stream(blockchainRef, memPoolRef).run()
      Behaviors.unhandled
    }

  val system = ActorSystem[Nothing](guardian, "minichain")

  Await.result(system.whenTerminated, 10.minutes)
}
