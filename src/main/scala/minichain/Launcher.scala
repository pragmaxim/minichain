package minichain

import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.Await
import scala.concurrent.duration._

object Launcher extends App {

  val guardian: Behavior[Nothing] =
    Behaviors.setup[Nothing] { ctx =>
      val blockchainRef = ctx.spawn(Blockchain.initialized(InMemoryBlockchain.fromGenesis), "Blockchain")
      val memPoolRef = ctx.spawn(MemPool.initialized(), "MemPool")
      ctx.spawn(Miner(blockchainRef, memPoolRef), "Miner")
      ctx.spawn(Peer(memPoolRef), "Peer")
      ctx.spawn(Wallet(memPoolRef), "Wallet")
      Behaviors.unhandled
    }

  val system = ActorSystem[Nothing](guardian, "minichain")

  Await.result(system.whenTerminated, 10.minutes)
}
