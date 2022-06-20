package minichain

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, PreRestart}
import minichain.Blockchain._
import minichain.MemPool.{PoolTxs, PullPoolTxs, Transaction, Transactions}
import minichain.Sha256.newDigestInstance

import java.security.MessageDigest
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration._

/** Miner is pulling txs from mempool and applies them to UtxoState, valid/verified txs are then used to mine a block
 * which is passed to blockchain */
class Miner(ctx: ActorContext[Response], timers: TimerScheduler[Response], memPool: ActorRef[PullPoolTxs], blockchain: ActorRef[ChainApplyEvent]) extends AbstractBehavior[Response](ctx) {

  timers.startSingleTimer(PoolTxs(ArraySeq.empty), 5.second)

  def initialized(index: Int, parentHash: Hash): Behavior[Response] =
    Behaviors.receiveMessage[Response] {
      case PoolTxs(txs) =>
        if (txs.nonEmpty)
          blockchain ! ApplyTxsToState(txs, parentHash, context.self)
        context.scheduleOnce(5.second, memPool, PullPoolTxs(context.self))
        Behaviors.same
      case TxsAppliedToState(validTxs, _) if validTxs.nonEmpty =>
        val newBlock = Miner.mineNextBlock(index, parentHash, Transactions(validTxs), Miner.StdMiningTargetNumber)
        ctx.log.info(s"Mined new block of ${validTxs.length} txs : ${newBlock.hash}")
        blockchain ! ApplyBlockToChain(newBlock, context.self)
        initialized(index + 1, newBlock.hash)
      case BlockAppliedToChain(_, _) =>
        Behaviors.same
    }.receiveSignal {
      case (_, PreRestart) =>
        ctx.log.info(s"Starting Miner ...")
        Behaviors.same
    }

  override def onMessage(msg: Response): Behavior[Response] = Behaviors.unhandled
}

object Miner {

  def apply(blockchain: ActorRef[ChainApplyEvent], memPool: ActorRef[PullPoolTxs]): Behavior[Response] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        new Miner(ctx, timers, memPool, blockchain).initialized(1, Miner.verifiedGenesisBlock.hash)
      }
    }
  final val genesisTx = Transaction(100000000, "Alice", "Bob")

  /** In real blockchain, it is adjusted based on various properties like network load, mining power, price, etc. */
  final val StdMiningTargetNumber = targetByLeadingZeros(1)

  /** Hash of non-existent block to be used as a parent for genesis block */
  val Zero_Hash: Hash =
    new Hash(
      newDigestInstance.digest(
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
    require(zeros < Sha256.NumberOfBytes)

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
                     index: Int,
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

    Block(currentSolution, BlockTemplate(index, parentHash, transactions, miningTargetNumber, currentNonce))
  }
}

class Hash(val bytes: Bytes) {
  def toNumber: HashNumber = BigInt(1, bytes)
  def toHexString: String = "0x" + bytes.map(b => String.format("%02X", Byte.box(b))).mkString("")
  override def toString: String = toHexString

  override def equals(obj: Any): Boolean = {
    obj match {
      case hash: Hash =>
        hash.toNumber == toNumber
      case _ => false
    }
  }

  override def hashCode(): Int =
    toNumber.hashCode()
}

object Sha256 {
  val NumberOfBytes = 32

  def newDigestInstance: MessageDigest = MessageDigest.getInstance("SHA-256")

  def apply(bytess: Bytes*): Hash = {
    val digest = newDigestInstance
    for (bytes <- bytess) {
      digest.update(bytes)
    }

    val hash = digest.digest()
    assert(hash.length == NumberOfBytes)

    new Hash(hash)
  }
}

/** Hashed BlockTemplate forms a Block */
case class Block(hash: Hash, template: BlockTemplate)

/** Hashing BlockTemplate with the right nonce gives us hash bellow target mining number */
case class BlockTemplate(
                          index: Int, // should not be Int in case our blockchain is going to be successful :-)
                          parentHash: Hash,
                          transactions: Transactions,
                          miningTargetNumber: HashNumber,
                          nonce: Nonce,
                        ) {

  def cryptoHash: Hash = BlockTemplate.cryptoHash(index, parentHash, transactions, miningTargetNumber, nonce)

  def verifyThisHasBeenMinedProperly(): Unit =
    assert(cryptoHash.toNumber < miningTargetNumber)
}

object BlockTemplate {

  /** Hash properties of a BlockTemplate with Sha-256 */
  def cryptoHash(
                  index: Int,
                  parentHash: Hash,
                  transactions: Transactions,
                  miningTargetNumber: BigInt,
                  nonce: Nonce,
                ): Hash = {
    Sha256(
      bigEndianByteArray(index),
      parentHash.bytes,
      transactions.merkleTreeRootHash.bytes,
      miningTargetNumber.toByteArray,
      bigEndianByteArray(nonce)
    )
  }
}

