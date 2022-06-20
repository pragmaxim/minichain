package minichain

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PreRestart}
import minichain.MemPool.{ApplyTxsToPool, Transaction, TxsAppliedToPool}

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration._

/** Wallet only simulates transaction generation by a user */
object Wallet {
  def apply(memPool: ActorRef[ApplyTxsToPool]): Behavior[TxsAppliedToPool] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        timers.startSingleTimer(TxsAppliedToPool(ArraySeq.empty), 500.millis)
        Behaviors.receiveMessage[TxsAppliedToPool] {
          case TxsAppliedToPool(_) =>
            ctx.scheduleOnce(1.second, memPool, ApplyTxsToPool(ArraySeq(Transaction(100, "Bob", "Alice")), ctx.self))
            Behaviors.same
        }.receiveSignal {
          case (_, PreRestart) =>
            ctx.log.info(s"Starting Wallet ...")
            Behaviors.same
        }
      }
    }
}