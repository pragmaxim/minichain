package minichain

import minichain.MemPool.Transactions
import minichain.Miner._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}

class MinerSpec extends AnyFlatSpec with Matchers {

  it should "use hash with correct equality and hash" in {
    Miner.Zero_Hash shouldEqual Miner.Zero_Hash
    Miner.Zero_Hash.toHexString shouldEqual Miner.Zero_Hash.bytes.map("%02X" format _).mkString("0x", "", "")
  }

  it should "use block with correct equality" in {
    Miner.verifiedGenesisBlock.template shouldEqual Miner.verifiedGenesisBlock.template
  }

  it should "generate mining target number" in {
    Miner.targetByLeadingZeros(31) shouldBe 255
    assertThrows[IllegalArgumentException](Miner.targetByLeadingZeros(32))
  }

  it should "mine block" in {
    val genesisBlock = Miner.verifiedGenesisBlock
    val genesisHash = BlockTemplate.cryptoHash(0, Zero_Hash, Transactions(ArraySeq(genesisTx)), StdMiningTargetNumber, genesisBlock.template.nonce)
    genesisBlock.hash shouldBe genesisHash
    genesisBlock.template.verifyThisHasBeenMinedProperly()

    val veryDifficultTargetNumber = targetByLeadingZeros(31)
    assertThrows[TimeoutException] (
      Await.result(
        Future(Miner.mineNextBlock(1, genesisHash, Transactions(ArraySeq(genesisTx)), veryDifficultTargetNumber)),
        500.millis
      )
    )
  }
}
