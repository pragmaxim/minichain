package minichain

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PreRestart}
import minichain.MemPool.{ApplyTxsToPool, TxsAppliedToPool}

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.DurationInt

/** Peer receives txs from other peers for demonstration purposes, it does not transmit txs to them */
object Peer {
  def behavior(memPool: ActorRef[ApplyTxsToPool]): Behavior[TxsAppliedToPool] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        timers.startSingleTimer(TxsAppliedToPool(ArraySeq.empty), 1.second)

        Behaviors.receiveMessage[TxsAppliedToPool] {
          case TxsAppliedToPool(_) =>
            ctx.scheduleOnce(1.second, memPool, ApplyTxsToPool(ArraySeq(Transaction(100, "Alice", "Bob"), Transaction(15, "Bob", "Tom")), ctx.self))
            Behaviors.same
        }.receiveSignal {
          case (_, PreRestart) =>
            ctx.log.info(s"Starting Peer ...")
            Behaviors.same
        }
      }
    }
}