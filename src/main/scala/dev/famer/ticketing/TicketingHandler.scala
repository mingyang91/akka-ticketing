package dev.famer.ticketing

import akka.Done
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.Handler
import dev.famer.ticketing.proto.Event.Kind
import org.slf4j.LoggerFactory

import scala.concurrent.Future


class TicketingHandler extends Handler[EventEnvelope[proto.Event]] {
  private val logger = LoggerFactory.getLogger(getClass)

  override def process(envelope: EventEnvelope[proto.Event]): Future[Done] = {
    envelope.event.kind match {
      case proto.Event.Kind.Made(m) =>
        logger.info(s"event: $m")
        Future.successful(Done)
      case proto.Event.Kind.Took(t@proto.Event.Took(id, time, subject, quota, _)) =>
        logger.info(s"event: $t")
        Future.successful(Done)
      case proto.Event.Kind.TookMany(tm@proto.Event.TookMany(id, time, list, _)) =>
//        logger.info(s"event: $tm")
        Future.successful(Done)
       case Kind.QuotaAdded(qa@proto.Event.QuotaAdded(id, time, quota, _)) =>
        logger.info(s"event: $qa")
        Future.successful(Done)
      case Kind.QuotaAdjusted(qa@proto.Event.QuotaAdjusted(id, time, quota, _)) =>
        logger.info(s"event: $qa")
        Future.successful(Done)
      case Kind.Destroy(d@proto.Event.Destroy(id, time, _)) =>
        logger.info(s"event: $d")
        Future.successful(Done)
      case Kind.Empty =>
        logger.info(s"event: EMPTY, it's impossible")
        Future.successful(Done)
    }
  }
}
