package minichain

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PreRestart}

import scala.collection.immutable.ArraySeq
import scala.collection.mutable

/** Mempool receives txs from peers and wallet and provides them to miner for mining a block */
object MemPool {
  sealed trait PoolApplyEvent

  case class PullPoolTxs(replyTo: ActorRef[PoolTxs]) extends PoolApplyEvent

  case class ApplyTxsToPool(txs: IndexedSeq[Transaction], replyTo: ActorRef[TxsAppliedToPool]) extends PoolApplyEvent

  sealed trait PoolResponse extends Response

  case class PoolTxs(txs: IndexedSeq[Transaction]) extends PoolResponse

  case class TxsAppliedToPool(txs: IndexedSeq[Transaction]) extends PoolResponse

  def behavior(txs: mutable.ArrayBuilder[Transaction] = mutable.ArrayBuilder.make): Behavior[PoolApplyEvent] =
    Behaviors.setup[PoolApplyEvent] { ctx =>
      Behaviors.receiveMessage[PoolApplyEvent] {
        case PullPoolTxs(replyTo) =>
          replyTo ! PoolTxs(ArraySeq.unsafeWrapArray(txs.result()))
          Behaviors.same
        case ApplyTxsToPool(newTxs, replyTo) =>
          ctx.log.info(s"Received new transactions : ${newTxs.mkString("", "; ", "")}")
          txs.addAll(newTxs)
          replyTo ! TxsAppliedToPool(newTxs)
          behavior(txs)

      }.receiveSignal {
        case (_, PreRestart) =>
          ctx.log.info(s"Starting MemPool ...")
          Behaviors.same
      }
    }

}