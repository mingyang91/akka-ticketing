package dev.famer.classic.ticketing

import io.getquill.{PostgresZioJdbcContext, SnakeCase}
import zio.ZIO

import java.sql.SQLException
import javax.sql.DataSource
import zio.EnvironmentTag
import zio.{Scope, ZIOAppArgs, ZLayer}
import zio.{Scope, ZIOAppArgs}
import io.getquill.jdbczio.Quill
import zio.stream.ZStream

case class Order(id: Int)

object Gen1StoredProcedure extends zio.ZIOAppDefault {
  val datasourceLayer = Quill.DataSource.fromPrefix("testPostgresDB")
  override def run: ZIO[Environment with ZIOAppArgs with Scope,Any,Any] = {

    val io = ZStream.range(1, 1000000)
      .mapZIOPar(128) { uid => DB.ordering(uid, 1) }
      .runDrain

    val flow = for {
      res <- io.timed
      _ <- ZIO.log(s"Done! time: ${res._1}")
    } yield ()

    flow.provideSomeLayer(datasourceLayer)
  }

}

object DB {
  lazy val ctx = new PostgresZioJdbcContext(SnakeCase)

  import ctx._
  def ordering(userId: Int, productId: Int): ZIO[DataSource, SQLException, Int] = run {
    quote {
      sql"SELECT public.ordering(${lift(userId)}, ${lift(productId)})".as[Int]
    }
  }
}