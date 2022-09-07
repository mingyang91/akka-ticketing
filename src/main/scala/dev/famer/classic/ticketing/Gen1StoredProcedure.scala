package dev.famer.classic.ticketing

import io.getquill.{PostgresZioJdbcContext, SnakeCase}
import zio.ZIO

import java.sql.SQLException
import javax.sql.DataSource

case class Order(id: Int)

object Gen1StoredProcedure {
  lazy val ctx = new PostgresZioJdbcContext(SnakeCase)

  import ctx._
  def ordering(userId: Int, productId: Int): ZIO[DataSource, SQLException, Int] = run {
    quote {
      sql"SELECT public.ordering($userId, $productId)".as[Int]
    }
  }

}

