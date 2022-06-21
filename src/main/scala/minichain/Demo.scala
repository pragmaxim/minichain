package minichain

import akka.Done
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import minichain.MemPool.ApplyTxsToPool

import scala.collection.immutable.ArraySeq
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object Demo {

  def run(memPool: ActorRef[ApplyTxsToPool])(implicit system: ActorSystem[_]): Future[(Done, Done)] = {
    implicit val timeout: Timeout = 3.seconds

    /** Peer receives txs from other peers for demonstration purposes, it does not transmit txs to them */
    val peerFuture =
      Source
        .tick(1.second, 1.second, ArraySeq(Transaction(100, "Alice", "Bob"), Transaction(15, "Bob", "Tom")))
        .mapAsync(1)( txs => memPool.ask( ref => ApplyTxsToPool(txs, ref) ))
        .run()

    /** Wallet only simulates transaction generation by a user */
    val walletFuture =
      Source
        .tick(500.millis, 1.second, ArraySeq(Transaction(100, "Bob", "Alice")))
        .mapAsync(1)( txs => memPool.ask( ref => ApplyTxsToPool(txs, ref) ))
        .run()

    peerFuture.zip(walletFuture)
  }

}
