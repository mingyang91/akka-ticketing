package dev.famer.classic.ticketing

import io.getquill.jdbczio.Quill
import io.getquill.{CamelCase, NamingStrategy, PostgresEscape, PostgresZioJdbcContext}
import zio.stream.ZStream
import zio.{Scope, ZIO, ZIOAppArgs}

import java.sql.SQLException
import javax.sql.DataSource

object Gen1StoredProcedure extends zio.ZIOAppDefault {
  val datasourceLayer = Quill.DataSource.fromPrefix("testPostgresDB")
  override def run: ZIO[Environment with ZIOAppArgs with Scope,Any,Any] = {

    val io = ZStream.range(1, 1000000)
      .mapZIOPar(128) { uid => Gen1DB.ordering(uid, 1) }
      .runDrain

    val flow = for {
      res <- io.timed
      _ <- ZIO.log(s"Done! time: ${res._1}")
    } yield ()

    flow.provideSomeLayer(datasourceLayer)
  }

}

object Gen1DB {
  lazy val ctx = new PostgresZioJdbcContext(NamingStrategy(CamelCase, PostgresEscape))

  import ctx._
  def ordering(userId: Int, productId: Int): ZIO[DataSource, SQLException, Int] = run {
    quote {
      sql"SELECT public.ordering(${lift(userId)}, ${lift(productId)})".as[Int]
    }
  }
}