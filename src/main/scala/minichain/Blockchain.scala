package minichain

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PreRestart}
import com.typesafe.scalalogging.LazyLogging
import minichain.Blockchain.TxsAppliedToState
import minichain.InMemoryBlockchain.{MapPimp, SortedMapPimp, UtxoState}
import minichain.MemPool.Transaction

import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, TreeMap}
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/** Blockchain actor verifies/validates transaction by trying to apply them to UtxoState,
 * returns valid txs to the Miner which mines a blocks and asks for applying it to the blockchain */
object Blockchain {
  sealed trait ChainApplyEvent
  case class ApplyTxsToState(txs: IndexedSeq[Transaction], parentHash: Hash, replyTo: ActorRef[TxsAppliedToState]) extends ChainApplyEvent
  case class ApplyBlockToChain(block: Block, replyTo: ActorRef[BlockAppliedToChain]) extends ChainApplyEvent

  sealed trait ChainResponse extends Response
  case class TxsAppliedToState(valid: IndexedSeq[Transaction], invalid: IndexedSeq[Transaction]) extends ChainResponse
  case class BlockAppliedToChain(block: Block, valid: Boolean) extends ChainResponse

  def initialized(state: BlockchainLike): Behavior[ChainApplyEvent] =
    Behaviors.setup[ChainApplyEvent] { ctx =>
      Behaviors.receiveMessage[ChainApplyEvent] {
      case ApplyTxsToState(txs, parentHash, replyTo) =>
        val (appliedTxs, newUtxoState) = state.applyTxsToUtxoState(txs, parentHash)
        replyTo ! appliedTxs
        initialized(newUtxoState)
      case ApplyBlockToChain(block, replyTo) =>
        ctx.log.info(s"Appending new block at height : ${block.template.index} [ ${block.hash.toNumber} ]")
        state.append(block) match {
          case Success(newState) =>
            replyTo ! BlockAppliedToChain(block, valid = true)
            initialized(newState)
          case Failure(ex) =>
            replyTo ! BlockAppliedToChain(block, valid = false)
            ctx.log.error(s"Ignoring invalid block", ex)
            Behaviors.same
        }
    }.receiveSignal {
      case (_, PreRestart) =>
        ctx.log.info(s"Starting MiniChain ...")
        Behaviors.same
    }
  }
}

sealed trait BlockchainLike {
  /** @return current height of blockchain, index of the latest block */
  def height: Int

  /** @return true if any branch has formed in blockchain and was not garbage collected yet */
  def isForked: Boolean

  /** @return latest block or blocks in case chain is currently having equally high competing branches */
  def latestBlocks: List[Block]

  /** Appends block to the tip of blockchain
   * @param verifiedBlock to append
   * @return new amended BlockchainLike instance  */
  def append(verifiedBlock: Block): Try[BlockchainLike]

  /**
   * Validates and applies transactions to the UtxoState
   * @param txs to validate and apply
   * @param parentHash is a hash of a parent block tracked by miner
   * @return Applied transactions (valid/invalid) and amended copy of BlockchainLike instance
   */
  def applyTxsToUtxoState(txs: IndexedSeq[Transaction], parentHash: Hash): (TxsAppliedToState, BlockchainLike)

  /**
   * Find block(s) by index
   * @param index height
   * @return block or blocks in case chain is currently having equally high competing branches
   */
  def findByIndex(index: Int): List[Block]

  /**
   * Find block by its hash
   * @param hash of the block we want to look up
   * @return Maybe a block if it exists in blockchain
   */
  def findByHash(hash: Hash): Option[Block]

  /**
   * Comparing 2 blockchains from the genesis block up until common ancestor block is found
   * @param that blockchain to compare with
   * @return Maybe a block that is a common ancestor to blockchains being compared
   */
  def commonAncestor(that: BlockchainLike): Option[Block]

  /**
   * When a shorter branch is too old, it cannot grow anymore
   * as competing branch has won, such shorter branches need to be removed
   * @return Maybe a block that is a common ancestor to blockchains being compared
   */
  def garbageCollectOrphanedBranches: BlockchainLike

  /** We keep UtxoState instance for each block due to forking, but we only need to keep latest
   * instances when competition of branches is still possible, the rest can be garbage collected
   * @return hashes of blocks that UtxoState version was garbage collected for + amended blockchain instance
   */
  def garbageCollectUtxoState: (IndexedSeq[Hash], BlockchainLike)
}

/**
 * Non-persistent Blockchain which holds all structures in memory
 * @param lastGarbageCollectionHeight checkpoint of the last garbage collection execution
 * @param indexCache cache of blocks by height, each height can have multiple blocks due to forking
 * @param hashCache cache of blocks by their hash
 * @param utxoStateByHash UtxoState corresponding to blocks, we need to have multiple instances due to forking
 */
case class InMemoryBlockchain(
                                    lastGarbageCollectionHeight: Int,
                                    indexCache: SortedMap[Int, List[Block]],
                                    hashCache: Map[Hash, BlockTemplate],
                                    utxoStateByHash: Map[Hash, UtxoState]) extends BlockchainLike with LazyLogging {
  def height: Int = indexCache.last._1

  def isForked: Boolean = indexCache.size == hashCache.size && indexCache.forall(_._2.tails.isEmpty)

  def latestBlocks: List[Block] = findByIndex(height)

  def append(verifiedBlock: Block): Try[InMemoryBlockchain] = {
    val block = verifiedBlock.template
    val blockHash = verifiedBlock.hash
    val isBlockOrphaned = !indexCache.get(block.index-1).exists(_.exists(_.hash == block.parentHash))

    if (isBlockOrphaned) {
      Failure(new IllegalArgumentException(s"Orphan block $blockHash does not have a parent ${block.parentHash} at height $height"))
    } else if (block.transactions.txs.isEmpty) {
      Failure(new IllegalArgumentException(s"Block $blockHash has no transactions at height $height"))
    } else {
      Try {
        val newBlockChain = InMemoryBlockchain(
          lastGarbageCollectionHeight,
          indexCache.adjust(block.index)(_.fold(verifiedBlock :: Nil)(verifiedBlock :: _)),
          hashCache.updated(blockHash, block),
          utxoStateByHash.updated(blockHash, utxoStateByHash(block.parentHash))
        )
        val highEnoughForGarbageCollection =
          newBlockChain.height - lastGarbageCollectionHeight > Settings.OrphanedForksGarbageCollectionLength
        if (highEnoughForGarbageCollection) {
          newBlockChain.garbageCollectOrphanedBranches.garbageCollectUtxoState._2
        } else
          newBlockChain
      }
    }
  }

  def applyTxsToUtxoState(txs: IndexedSeq[Transaction], parentHash: Hash): (TxsAppliedToState, InMemoryBlockchain) = {
    def transfer(value: Long, input: String, output: String, utxoState: Map[String, Long]): Try[Map[String, Long]] = {
      utxoState.get(input).map {
        case existingInputValue if existingInputValue >= value =>
          Success(utxoState.updated(input, existingInputValue - value).adjust(output)(_.fold(value)(_ + value)))
        case existingInputValue =>
          Failure(new IllegalArgumentException(s"Address $input has only $existingInputValue balance to cover request for $value"))
      }.getOrElse(Failure(new IllegalArgumentException(s"Input address $input does not exist")))
    }

    val utxoStateForHash = utxoStateByHash(parentHash)
    val printableState = utxoStateForHash.map { case (address, value) => s"$address : $value" }.mkString(", ")
    logger.info(s"Applying ${txs.length} transactions to UtxoState : $printableState")

    val validTxs = mutable.ArrayBuilder.make[Transaction]
    val invalidTxs = mutable.ArrayBuilder.make[Transaction]
    val newUtxoStateForHash = txs.foldLeft(utxoStateForHash) { case (utxoStateAcc, tx) =>
      transfer(tx.value, tx.input, tx.output, utxoStateAcc) match {
        case Success(newUtxoStateAcc) =>
          validTxs.addOne(tx)
          newUtxoStateAcc
        case Failure(ex) =>
          logger.warn(s"Unable to apply transaction $tx, ${ex.getMessage}")
          invalidTxs.addOne(tx)
          utxoStateAcc
      }
    }
    TxsAppliedToState(validTxs.result(), invalidTxs.result()) -> copy(utxoStateByHash = utxoStateByHash.updated(parentHash, newUtxoStateForHash))
  }


  def findByIndex(index: Int): List[Block] = indexCache.getOrElse(index, List.empty)

  def findByHash(hash: Hash): Option[Block] = hashCache.get(hash).map(Block(hash, _))

  def commonAncestor(that: BlockchainLike): Option[Block] = {
    var lowestHeight = Math.min(height, that.height)
    var ancestor = Option.empty[Block]
    while (ancestor.isEmpty && lowestHeight >= 0) {
      val thatBlocks = that.findByIndex(lowestHeight)
      val thisBlocks = findByIndex(lowestHeight)
      ancestor = thatBlocks.find { thatBlock =>
        thisBlocks.exists(_.hash == thatBlock.hash)
      }
      lowestHeight -= 1
    }
    ancestor
  }

  def garbageCollectOrphanedBranches: InMemoryBlockchain = {
    val _ :: tailBlocks = indexCache.last._2
    if (tailBlocks.nonEmpty) {
      logger.info(s"Ongoing fork detected, waiting until one branch wins ...")
      this
    } else {
      @tailrec
      def cleanupBranch(newCleanupHeight: Int, childIndex: Int, parentHash: Hash, newBlockChain: InMemoryBlockchain): InMemoryBlockchain = {
        val newIndexCache = newBlockChain.indexCache.adjust(childIndex)(_.get.filter(_.hash == parentHash))
        val newHashCache =
          newBlockChain.indexCache(childIndex)
            .filter(_.hash != parentHash)
            .foldLeft(newBlockChain.hashCache) { case (acc, block) => acc - block.hash }
        if (childIndex > lastGarbageCollectionHeight && parentHash != Miner.Zero_Hash) {
          cleanupBranch(
            newCleanupHeight,
            childIndex - 1,
            newIndexCache(childIndex).head.template.parentHash,
            InMemoryBlockchain(newCleanupHeight, newIndexCache, newHashCache, utxoStateByHash)
          )
        } else {
          InMemoryBlockchain(newCleanupHeight, newIndexCache, newHashCache, utxoStateByHash)
        }
      }
      def findLastSplit: Option[(Int, List[Block])] =
        indexCache
          .takeRight(Settings.OrphanedForksGarbageCollectionLength)
          .toSeq
          .findLast(_._2.tail.isEmpty)

      logger.info(s"Garbage collecting shorter branches from height ${lastGarbageCollectionHeight+1} to $height")
      findLastSplit match {
        case None =>
          this
        case Some((idx, blocks)) =>
          cleanupBranch(idx-1, idx-1, blocks.head.template.parentHash, this)
      }
    }
  }

  def garbageCollectUtxoState: (IndexedSeq[Hash], InMemoryBlockchain) =
    if (height <= Settings.UtxoStateGarbageCollectionStart) {
      Vector.empty -> this
    } else {
      val (newUtxoState, garbageCollectedHashes) =
        indexCache
          .takeRight(Settings.UtxoStateGarbageCollectionStart)
          .take(Settings.UtxoStateGarbageCollectionStop)
          .flatMap(_._2.map(_.hash))
          .foldLeft(utxoStateByHash, Vector.empty[Hash]) { case ((utxoStateAcc, gcHashes), hashToGarbageCollect) =>
            utxoStateAcc.removed(hashToGarbageCollect) -> gcHashes.appended(hashToGarbageCollect)
          }
      val fromHeight = height - Settings.UtxoStateGarbageCollectionStart
      val toHeight = height - Settings.UtxoStateGarbageCollectionStop
      logger.info(s"Garbage collected ${garbageCollectedHashes.length} UtxoState versions from height $fromHeight to $toHeight")
      garbageCollectedHashes -> copy(utxoStateByHash = newUtxoState)
    }
}

object InMemoryBlockchain extends LazyLogging {

  /** Balance by address */
  type UtxoState = Map[String, Long]

  implicit class MapPimp[K, V](underlying: Map[K, V]) {
    def adjust(k: K)(f: Option[V] => V): Map[K, V] = underlying.updated(k, f(underlying.get(k)))
  }

  /** Create Blockchain from genesis block */
  def fromGenesis: InMemoryBlockchain = {
    new InMemoryBlockchain(
      lastGarbageCollectionHeight = 0,
      TreeMap(0 -> List(Miner.verifiedGenesisBlock)),
      Map(Miner.verifiedGenesisBlock.hash -> Miner.verifiedGenesisBlock.template),
      Map(Miner.verifiedGenesisBlock.hash -> Map(Miner.genesisTx.output -> Miner.genesisTx.value))
    )
  }

  implicit class SortedMapPimp[K, V](underlying: SortedMap[K, V]) {
    def adjust(k: K)(f: Option[V] => V): SortedMap[K, V] = underlying.updated(k, f(underlying.get(k)))
  }
}

object Settings {
  /** less developed branches need to be garbage collected */
  val OrphanedForksGarbageCollectionLength = 10

  /** old versions of UtxoState need to be garbage collected : (height - start; height - stop) */
  val UtxoStateGarbageCollectionStart = 20
  val UtxoStateGarbageCollectionStop = 10
}
