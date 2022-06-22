package minichain

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PreRestart}
import akka.pattern.StatusReply
import minichain.Blockchain.TxsAppliedToState

import scala.util.{Failure, Success, Try}

/** Blockchain actor verifies/validates transaction by trying to apply them to UtxoState,
 * returns valid txs to the Miner which mines a blocks and asks for applying it to the blockchain */
object Blockchain {
  sealed trait ChainRequest

  case class ApplyTxsToState(txs: IndexedSeq[Transaction], replyTo: ActorRef[TxsAppliedToState]) extends ChainRequest

  case class ApplyBlockToChain(block: Block, replyTo: ActorRef[StatusReply[Block]]) extends ChainRequest

  sealed trait ChainResponse extends Response

  case class TxsAppliedToState(valid: IndexedSeq[Transaction], invalid: IndexedSeq[Transaction], height: Long, parentHash: Hash) extends ChainResponse

  case class BlockAppliedToChain(block: Block, valid: Boolean) extends ChainResponse

  def behavior(state: BlockchainLike): Behavior[ChainRequest] =
    Behaviors.setup[ChainRequest] { ctx =>
      Behaviors.receiveMessage[ChainRequest] {
        case ApplyTxsToState(txs, replyTo) =>
          val (appliedTxs, newUtxoState) = state.applyTxsToUtxoState(txs)
          replyTo ! appliedTxs
          behavior(newUtxoState)
        case ApplyBlockToChain(block, replyTo) =>
          ctx.log.info(s"Appending new block at height : ${block.template.index} [ ${block.hash.toNumber} ]")
          state.append(block) match {
            case Success(newState) =>
              replyTo ! StatusReply.success(block)
              behavior(newState)
            case Failure(ex) =>
              replyTo ! StatusReply.error(ex)
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

trait BlockchainLike {
  /** @return hash of the latest block */
  def bestBlockHash: Hash

  /** @return current height of blockchain, index of the latest block */
  def height: Long

  /** @return true if any branch has formed in blockchain and was not garbage collected yet */
  def isForked: Boolean

  /** @return latest block or blocks in case chain is currently having equally high competing branches */
  def latestBlocks: List[Block]

  /** Appends block to the tip of blockchain
   *
   * @param verifiedBlock to append
   * @return new amended BlockchainLike instance */
  def append(verifiedBlock: Block): Try[BlockchainLike]

  /**
   * Validates and applies transactions to the UtxoState
   *
   * @param txs        to validate and apply
   * @return Applied transactions (valid/invalid) and amended copy of BlockchainLike instance
   */
  def applyTxsToUtxoState(txs: IndexedSeq[Transaction]): (TxsAppliedToState, BlockchainLike)

  /**
   * Find block(s) by index
   *
   * @param index height
   * @return block or blocks in case chain is currently having equally high competing branches
   */
  def findByIndex(index: Long): List[Block]

  /**
   * Find block by its hash
   *
   * @param hash of the block we want to look up
   * @return Maybe a block if it exists in blockchain
   */
  def findByHash(hash: Hash): Option[Block]

  /**
   * Comparing 2 blockchains from the genesis block up until common ancestor block is found
   *
   * @param that blockchain to compare with
   * @return Maybe a block that is a common ancestor to blockchains being compared
   */
  def commonAncestor(that: BlockchainLike): Option[Block]

  /**
   * When a shorter branch is too old, it cannot grow anymore
   * as competing branch has won, such shorter branches need to be removed
   *
   * @return Maybe a block that is a common ancestor to blockchains being compared
   */
  def garbageCollectOrphanedBranches: BlockchainLike

  /** We keep UtxoState instance for each block due to forking, but we only need to keep latest
   * instances when competition of branches is still possible, the rest can be garbage collected
   *
   * @return hashes of blocks that UtxoState version was garbage collected for + amended blockchain instance
   */
  def garbageCollectUtxoState: (IndexedSeq[Hash], BlockchainLike)
}

object Settings {
  /** less developed branches need to be garbage collected */
  val OrphanedForksGarbageCollectionLength = 10

  /** old versions of UtxoState need to be garbage collected : (height - start; height - stop) */
  val UtxoStateGarbageCollectionStart = 20
  val UtxoStateGarbageCollectionStop = 10
}
