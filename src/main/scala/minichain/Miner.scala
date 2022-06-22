package minichain

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.pattern.StatusReply
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import minichain.Blockchain._
import minichain.MemPool.{PoolTxs, PullPoolTxs}

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration._

/** Miner is pulling txs from mempool and applies them to UtxoState, valid/verified txs are then used to mine a block
 * which is passed to blockchain */
object Miner extends LazyLogging {

  def stream(blockchain: ActorRef[ChainRequest], memPool: ActorRef[PullPoolTxs])
            (implicit system: ActorSystem[_]): Source[StatusReply[Block], Cancellable] = {
    implicit val timeout: Timeout = 3.seconds
    Source.tick(5.seconds, 3.seconds, ())
      .mapAsync(parallelism = 1) { _ =>
        memPool.ask(PullPoolTxs)
      }.filter(_.txs.nonEmpty)
      .mapAsync(parallelism = 1) { case PoolTxs(txs) =>
        blockchain.ask(ref => ApplyTxsToState(txs, ref))
      }.filter(_.valid.nonEmpty)
      .mapAsync(parallelism = 1) { case TxsAppliedToState(validTxs, _, index, parentHash) =>
        val newBlock = Miner.mineNextBlock(index + 1, parentHash, Transactions(validTxs), Miner.StdMiningTargetNumber)
        blockchain.ask(ref => ApplyBlockToChain(newBlock, ref))
      }
  }

  final val genesisTx = Transaction(100000000, "Alice", "Bob")

  /** In real blockchain, it is adjusted based on various properties like network load, mining power, price, etc. */
  final val StdMiningTargetNumber = targetByLeadingZeros(1)

  /** Hash of non-existent block to be used as a parent for genesis block */
  val Zero_Hash: Hash =
    new Hash(
      Hash.newSha256Instance.digest(
        "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks".getBytes("UTF-8")
      )
    )

  final val verifiedGenesisBlock = Miner.mineNextBlock(
    index = 0, // The very first block
    parentHash = Zero_Hash, // Let's assume this is by definition for the Genesis block.
    transactions = Transactions(ArraySeq(genesisTx)),
    StdMiningTargetNumber,
  )

  /** Method for building mining target number */
  def targetByLeadingZeros(zeros: Int): HashNumber = {
    require(zeros < Hash.Sha256NumberOfBytes)

    val bytes: Bytes =
      Array.tabulate[Byte](32) { n =>
        if (n < zeros) {
          0
        }
        else {
          0xff.toByte
        }
      }

    BigInt(1, bytes)
  }

  /** Hash BlockTemplate with an increasing nonce until we get a hash number that is lower than mining target number */
  def mineNextBlock(
                     index: Long,
                     parentHash: Hash,
                     transactions: Transactions,
                     miningTargetNumber: BigInt,
                   ): Block = {
    var currentNonce: Nonce = -1
    var currentSolution: Hash = null
    do {
      currentNonce += 1
      currentSolution = BlockTemplate.cryptoHash(index, parentHash, transactions, miningTargetNumber, currentNonce)
    } while (currentSolution.toNumber >= miningTargetNumber && currentNonce < Long.MaxValue)

    if (currentSolution.toNumber >= miningTargetNumber)
      throw new IllegalStateException("Unable to find solution with Nonce<Long.MinValue, Long.MaxValue>")
    logger.info(s"Mined new block of ${transactions.txs.length} txs : $currentSolution")
    Block(currentSolution, BlockTemplate(index, parentHash, transactions, miningTargetNumber, currentNonce))
  }
}
