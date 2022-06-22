package minichain

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import minichain.MemPool.{ApplyTxsToPool, TxsAppliedToPool}

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.DurationInt

object Demo {

  implicit val timeout: Timeout = 3.seconds

  /** Peer receives txs from other peers for demonstration purposes, it does not transmit txs to them */
  def peerTxSource(memPool: ActorRef[ApplyTxsToPool])(implicit s: ActorSystem[_]): Source[TxsAppliedToPool, Cancellable] =
    Source
      .tick(1.second, 1.second, ArraySeq(Transaction(100, "Alice", "Bob"), Transaction(15, "Bob", "Tom")))
      .mapAsync(1)(txs => memPool.ask(ref => ApplyTxsToPool(txs, ref)))

  /** Wallet only simulates transaction generation by a user */
  def walletTxsSource(memPool: ActorRef[ApplyTxsToPool])(implicit s: ActorSystem[_]): Source[TxsAppliedToPool, Cancellable] =
    Source
      .tick(500.millis, 1.second, ArraySeq(Transaction(100, "Bob", "Alice")))
      .mapAsync(1)(txs => memPool.ask(ref => ApplyTxsToPool(txs, ref)))

}
