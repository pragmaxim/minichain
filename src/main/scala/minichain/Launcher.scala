package minichain

import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.Await
import scala.concurrent.duration._

object Launcher extends App {

  val guardian: Behavior[Nothing] =
    Behaviors.setup[Nothing] { ctx =>
      val blockchainRef = ctx.spawn(Blockchain.behavior(InMemoryBlockchain.fromGenesis), "Blockchain")
      val memPoolRef = ctx.spawn(MemPool.behavior(), "MemPool")
      ctx.spawn(Miner.behavior(blockchainRef, memPoolRef), "Miner")
      ctx.spawn(Peer.behavior(memPoolRef), "Peer")
      ctx.spawn(Wallet.behavior(memPoolRef), "Wallet")
      Behaviors.unhandled
    }

  val system = ActorSystem[Nothing](guardian, "minichain")

  Await.result(system.whenTerminated, 10.minutes)
}
