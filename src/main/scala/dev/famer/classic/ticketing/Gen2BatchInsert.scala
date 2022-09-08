package dev.famer.classic.ticketing

import io.getquill.jdbczio.Quill
import io.getquill.{CamelCase, NamingStrategy, PostgresEscape, PostgresZioJdbcContext}
import zio.stream.ZStream
import zio.{Promise, Queue, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer, durationInt}

import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

object Gen2BatchInsert extends ZIOAppDefault {
  val datasourceLayer = Quill.DataSource.fromPrefix("testPostgresDB")

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = {
    val io = for {
      start <- Stock.start().fork
      res <- ZIO.foreachPar((1 to 1000000).toVector) {
        uid => Stock.take(uid, 1)
      }.timed
      _ <- ZIO.log(s"Done: ${res._1}")
      _ <- start.join
    } yield ()

    io.provideSomeLayer(datasourceLayer ++ Stock.queueLayer)
  }
}

object Stock {
  case class Request(userId: Int, productId: Int, promise: Promise[String, Unit])

  val queueLayer: ZLayer[Any, Nothing, Queue[Request]] =
    ZLayer.fromZIO(zio.Queue.bounded[Request](1024 * 1024))

  def take(userId: Int, productId: Int): ZIO[Queue[Request], String, Unit] = ZIO.service[Queue[Request]]
    .flatMap { queue =>
      for {
        promise <- Promise.make[String, Unit]
        _ <- queue.offer(Request(userId, productId, promise))
        res <- promise.await
      } yield res
    }

  def start(): ZIO[DataSource with Queue[Request], Throwable, Unit] = {
    for {
      queue <- ZIO.service[Queue[Request]]
      _ <- ZStream.fromQueue(queue)
        .groupedWithin(1024, 64.milliseconds)
        .runFoldZIO(1024 * 1024) { (remain, batch) =>
          val (accept, reject) = batch.splitAt(remain)
          for {
            now <- zio.Clock.instant
            _ <- Gen2DB.flush(accept.map(req => Gen2DB.OrderRow(-1, req.productId, req.userId, now)))
            _ <- ZIO.foreachPar(accept) { _.promise.succeed(()) }
            _ <- ZIO.foreachPar(reject) { _.promise.fail("sold out") }
          } yield remain - accept.size
        }
    } yield ()
  }
}


object Gen2DB {
  lazy val ctx = new PostgresZioJdbcContext(NamingStrategy(CamelCase, PostgresEscape))

  import ctx._

  case class ProductRow(id: Int, name: String, stock: Int)
  case class OrderRow(id: Int, productId: Int, userId: Int, time: Instant)
  implicit val meta = insertMeta[OrderRow](_.id)

  def persistOrder(order: Seq[OrderRow]): ZIO[DataSource, SQLException, List[Long]] = run {
    quote {
      liftQuery(order)
        .foreach(r => querySchema[OrderRow]("public.order").insertValue(r))
    }
  } <* ZIO.log(s"persist: ${order.size}")

  def updateStock(productId: Int, decrease: Int): ZIO[DataSource, SQLException, Long] = run {
    quote {
      querySchema[ProductRow]("public.product")
        .filter(_.id == lift(productId))
        .update(p => p.stock -> (p.stock - lift(decrease)))
    }
  }

  def flush(order: Seq[OrderRow]): ZIO[DataSource, Throwable, (List[Long], Long)] = transaction {
    persistOrder(order) <*> updateStock(order.head.productId, order.size)
  }
}