package minichain

import java.security.MessageDigest

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

object Hash {

  def newSha256Instance: MessageDigest = MessageDigest.getInstance("SHA-256")

  val Sha256NumberOfBytes = 32

  def sha256(bytess: Bytes*): Hash = {
    val digest = newSha256Instance
    for (bytes <- bytess) {
      digest.update(bytes)
    }

    val hash = digest.digest()
    assert(hash.length == Sha256NumberOfBytes)

    new Hash(hash)
  }
}

case class Transaction(value: Long, input: String, output: String)

case class Transactions(txs: IndexedSeq[Transaction]) {
  /** For demonstration purposes, merkle tree not implemented */
  def merkleTreeRootHash: Hash = {
    val digest = Hash.newSha256Instance
    var index = 0
    while (index < txs.length) {
      val tx = txs(index)
      digest.update(bigEndianByteArray(tx.value))
      digest.update(tx.input.getBytes("UTF-8"))
      digest.update(tx.output.getBytes("UTF-8"))
      index += 1
    }
    new Hash(digest.digest())
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
    Hash.sha256(
      bigEndianByteArray(index),
      parentHash.bytes,
      transactions.merkleTreeRootHash.bytes,
      miningTargetNumber.toByteArray,
      bigEndianByteArray(nonce)
    )
  }
}

