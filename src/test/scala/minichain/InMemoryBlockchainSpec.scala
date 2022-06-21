package minichain

import minichain.Blockchain.TxsAppliedToState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.ArraySeq

class InMemoryBlockchainSpec extends AnyFlatSpec with Matchers {

  private def forkBlockchainWith(parentHash: Hash, atHeight: Long, bc: BlockchainLike, txs: Transactions) = {
    (atHeight to bc.height+4).foldLeft(parentHash -> bc) { case ((newParentHash, newBc), childIdx) =>
      val newBlock = Miner.mineNextBlock(childIdx, newParentHash, txs, Miner.StdMiningTargetNumber)
      newBlock.hash -> newBc.append(newBlock).get
    }
  }

  private def generateNewBlockchain(txs: Transactions, height: Long = 3) = {
    (1L to height).foldLeft(Miner.verifiedGenesisBlock.hash -> InMemoryBlockchain.fromGenesis) { case ((parentHash, bc), idx) =>
      val newBlock = Miner.mineNextBlock(idx, parentHash, txs, Miner.StdMiningTargetNumber)
      newBlock.hash -> bc.append(newBlock).get
    }
  }

  it should "provide correct height" in {
    val bcWithGenesisOnly = InMemoryBlockchain.fromGenesis
    bcWithGenesisOnly.height shouldBe 0
    val newBlock = Miner.mineNextBlock(1, Miner.verifiedGenesisBlock.hash, Transactions(ArraySeq(Transaction(100, "Alice", "Bob"))), Miner.StdMiningTargetNumber)
    bcWithGenesisOnly.append(newBlock).get.height shouldBe 1
  }

  it should "return correct heads" in {
    val bcWithGenesisOnly = InMemoryBlockchain.fromGenesis
    bcWithGenesisOnly.height shouldBe 0
    val newBlock = Miner.mineNextBlock(1, Miner.verifiedGenesisBlock.hash, Transactions(ArraySeq(Transaction(100, "Alice", "Bob"))), Miner.StdMiningTargetNumber)
    val evolvedBc = bcWithGenesisOnly.append(newBlock)
    evolvedBc.get.height shouldBe 1
    evolvedBc.get.latestBlocks shouldBe List(newBlock)
  }

  it should "append valid blocks and reject invalid" in {
    val bcWithGenesisOnly = InMemoryBlockchain.fromGenesis

    val validBlock = Miner.mineNextBlock(1, Miner.verifiedGenesisBlock.hash, Transactions(ArraySeq(Transaction(100, "Alice", "Bob"))), Miner.StdMiningTargetNumber)
    val invalidIndexBlock = Miner.mineNextBlock(256, Miner.verifiedGenesisBlock.hash, Transactions(ArraySeq(Transaction(100, "Alice", "Bob"))), Miner.StdMiningTargetNumber)
    val orphanBlock = Miner.mineNextBlock(1, Hash.sha256("Alice".getBytes("UTF-8")), Transactions(ArraySeq(Transaction(100, "Alice", "Bob"))), Miner.StdMiningTargetNumber)
    val invalidTxsBlock = Miner.mineNextBlock(1, Miner.verifiedGenesisBlock.hash, Transactions(ArraySeq.empty), Miner.StdMiningTargetNumber)

    bcWithGenesisOnly.append(validBlock).isSuccess shouldBe true
    bcWithGenesisOnly.append(invalidIndexBlock).isFailure shouldBe true
    bcWithGenesisOnly.append(invalidTxsBlock).isFailure shouldBe true
    bcWithGenesisOnly.append(orphanBlock).isFailure shouldBe true
  }

  it should "find blocks by index" in {
    InMemoryBlockchain.fromGenesis.findByIndex(1) shouldBe empty
    InMemoryBlockchain.fromGenesis.findByIndex(0).nonEmpty shouldBe true
  }

  it should "find blocks by hash" in {
    InMemoryBlockchain.fromGenesis.findByHash(Miner.Zero_Hash) shouldBe empty
    InMemoryBlockchain.fromGenesis.findByHash(Miner.verifiedGenesisBlock.hash).nonEmpty shouldBe true
  }

  it should "find common ancestor of 2 blockchains regardless of height" in {
    val dummyTxs_1 = Transactions(ArraySeq(Transaction(100, "Alice", "Bob")))
    val dummyTxs_2 = Transactions(ArraySeq(Transaction(100, "Bob", "Tom")))

    val (commonHeadHash_1, commonBlockChain_1) = generateNewBlockchain(dummyTxs_1)
    val (commonHeadHash_2, commonBlockChain_2) = generateNewBlockchain(dummyTxs_1)

    // check heads of blockchains are the same
    commonHeadHash_1 shouldBe commonHeadHash_2
    commonBlockChain_1.findByHash(commonHeadHash_2).nonEmpty shouldBe true
    commonBlockChain_2.findByHash(commonHeadHash_1).nonEmpty shouldBe true

    val (differentHeadHash_1, differentBlockChain_1) = forkBlockchainWith(commonHeadHash_1, commonBlockChain_1.height+1, commonBlockChain_1, dummyTxs_1)
    val (differentHeadHash_2, differentBlockChain_2) = forkBlockchainWith(commonHeadHash_2, commonBlockChain_2.height+1, commonBlockChain_2, dummyTxs_2)

    // different blockchains should have different heads
    differentHeadHash_1 shouldNot be(differentHeadHash_2)

    // check equally high blockchain for common ancestor
    differentBlockChain_1.commonAncestor(differentBlockChain_2).map(_.hash) shouldBe Some(commonHeadHash_1)
    differentBlockChain_2.commonAncestor(differentBlockChain_1).map(_.hash) shouldBe Some(commonHeadHash_1)

    val longerBlockChain_2 =
      differentBlockChain_2.append(
        Miner.mineNextBlock(
          differentBlockChain_2.height+1,
          differentHeadHash_2,
          dummyTxs_2,
          Miner.StdMiningTargetNumber
        )
      ).get

    // check longer blockchain with a shorter one for common ancestor
    differentBlockChain_1.commonAncestor(longerBlockChain_2).map(_.hash) shouldBe Some(commonHeadHash_1)
    longerBlockChain_2.commonAncestor(differentBlockChain_1).map(_.hash) shouldBe Some(commonHeadHash_1)

    // check common ancestor of evolved blockchain
    differentBlockChain_2.commonAncestor(longerBlockChain_2).map(_.hash) shouldBe Some(differentHeadHash_2)
  }

  it should "garbage collect orphaned branches" in {
    val dummyTxs_1 = Transactions(ArraySeq(Transaction(100, "Alice", "Bob")))
    val dummyTxs_2 = Transactions(ArraySeq(Transaction(100, "Bob", "Tom")))
    val dummyTxs_3 = Transactions(ArraySeq(Transaction(100, "Tom", "Jane")))

    // build a forked blockchain
    val (commonHeadHash, commonBlockChain) = generateNewBlockchain(dummyTxs_1)
    val (_, evolvedBlockChain) = forkBlockchainWith(commonHeadHash, commonBlockChain.height+1, commonBlockChain, dummyTxs_2)
    val (forkedHeadHash, forkedBlockChain) = forkBlockchainWith(commonHeadHash, commonBlockChain.height+1, evolvedBlockChain, dummyTxs_3)

    // cleanup does nothing as no branch has won yet, they are equally high
    forkedBlockChain.garbageCollectOrphanedBranches shouldEqual forkedBlockChain

    val winningBlockChain = forkedBlockChain.append(Miner.mineNextBlock(forkedBlockChain.height+1, forkedHeadHash, dummyTxs_3, Miner.StdMiningTargetNumber)).get

    // cleanup now removes the less evolved branch from the blockchain
    val forkFreeBlockChain = winningBlockChain.garbageCollectOrphanedBranches
    forkFreeBlockChain shouldNot equal(winningBlockChain)

    // check that there are now forks
    forkFreeBlockChain.isForked shouldBe false
  }

  it should "apply transactions into utxo state" in {
    val validTxs =
      ArraySeq(
        Transaction(100, "Bob", "Alice"),
        Transaction(100, "Alice", "Bob"),
        Transaction(50, "Bob", "Alice"),
        Transaction(50L, "Alice", "Tom")
      )
    val invalidTxs =
      ArraySeq(
        Transaction(50L, "Alice", "Tom"),
        Transaction(50L, "Unknown", "Tom")
      )
    val (TxsAppliedToState(valid, invalid), newBlockChain) = InMemoryBlockchain.fromGenesis.applyTxsToUtxoState(validTxs ++ invalidTxs, Miner.verifiedGenesisBlock.hash)
    newBlockChain.utxoStateByHash shouldBe Map(Miner.verifiedGenesisBlock.hash -> Map("Bob" -> 99999950, "Alice" -> 0, "Tom" -> 50))
    valid shouldBe validTxs
    invalid shouldBe invalidTxs
  }

  it should "garbage collect utxo state" in {
    val dummyTxs_1 = Transactions(ArraySeq(Transaction(100, "Alice", "Bob")))
    val dummyTxs_2 = Transactions(ArraySeq(Transaction(100, "Bob", "Tom")))
    val dummyTxs_3 = Transactions(ArraySeq(Transaction(100, "Tom", "Jane")))

    // build a forked blockchain
    val (commonHeadHash, commonBlockChain) = generateNewBlockchain(dummyTxs_1, 20)
    val (evolvedHeadHash, evolvedBlockChain) = forkBlockchainWith(commonHeadHash, commonBlockChain.height+1, commonBlockChain, dummyTxs_2)
    val (forkedHeadHash, forkedBlockChain) = forkBlockchainWith(commonHeadHash, commonBlockChain.height+1, evolvedBlockChain, dummyTxs_3)

    // short chain should not be garbage collected
    generateNewBlockchain(dummyTxs_1)._2.garbageCollectUtxoState._1 shouldBe empty

    // garbage collect old versions of UtxoState
    val garbageCollectedHashes = forkedBlockChain.garbageCollectUtxoState._1
    garbageCollectedHashes.size shouldBe 10
    garbageCollectedHashes.contains(commonHeadHash) shouldBe false
    garbageCollectedHashes.contains(evolvedHeadHash) shouldBe false
    garbageCollectedHashes.contains(forkedHeadHash) shouldBe false
  }
}
