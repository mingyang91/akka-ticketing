package dev.famer.ticketing

import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.Offset
import akka.projection.cassandra.scaladsl.CassandraProjection
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.{AtLeastOnceProjection, SourceProvider}
import akka.projection.{ProjectionBehavior, ProjectionId, eventsourced}
import dev.famer.ticketing.proto.Event

object Bootstrap {
  val setup = Behaviors.setup[String] { context =>
    val system = context.system
    implicit val ec = system.executionContext

    def sourceProvider(tag: String): SourceProvider[Offset, eventsourced.EventEnvelope[Event]] =
      EventSourcedProvider
        .eventsByTag[proto.Event](
          system,
          readJournalPluginId = CassandraReadJournal.Identifier,
          tag = tag
        )

    def projection(tag: String): AtLeastOnceProjection[Offset, EventEnvelope[Event]] =
      CassandraProjection.atLeastOnce(
        projectionId = ProjectionId("ticketing", tag),
        sourceProvider(tag),
        handler = () => new TicketingHandler()
      )

    TicketBehavior.init(system)

    ShardedDaemonProcess(system).init[ProjectionBehavior.Command](
      name = "ticketing",
      numberOfInstances = TicketingTags.tags.length,
      behaviorFactory = (i: Int) => ProjectionBehavior(projection(TicketingTags.tags(i))),
      stopMessage = ProjectionBehavior.Stop
    )

    Behaviors.empty
  }

}
