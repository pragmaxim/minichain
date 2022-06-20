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

  def initialized(txs: mutable.ArrayBuilder[Transaction] = mutable.ArrayBuilder.make): Behavior[PoolApplyEvent] =
    Behaviors.setup[PoolApplyEvent] { ctx =>
      Behaviors.receiveMessage[PoolApplyEvent] {
        case PullPoolTxs(replyTo) =>
          replyTo ! PoolTxs(ArraySeq.unsafeWrapArray(txs.result()))
          Behaviors.same
        case ApplyTxsToPool(newTxs, replyTo) =>
          ctx.log.info(s"Received new transactions : ${newTxs.mkString("", "; ", "")}")
          txs.addAll(newTxs)
          replyTo ! TxsAppliedToPool(newTxs)
          initialized(txs)

      }.receiveSignal {
        case (_, PreRestart) =>
          ctx.log.info(s"Starting MemPool ...")
          Behaviors.same
      }
  }

  case class Transaction(value: Long, input: String, output: String)

  case class Transactions(txs: IndexedSeq[Transaction]) {
    /** For demonstration purposes, merkle tree not implemented */
    def merkleTreeRootHash: Hash = {
      val digest = Sha256.newDigestInstance
      var index = 0
      while(index < txs.length) {
        val tx = txs(index)
        digest.update(bigEndianByteArray(tx.value))
        digest.update(tx.input.getBytes("UTF-8"))
        digest.update(tx.output.getBytes("UTF-8"))
        index += 1
      }
      new Hash(digest.digest())
    }
  }

}