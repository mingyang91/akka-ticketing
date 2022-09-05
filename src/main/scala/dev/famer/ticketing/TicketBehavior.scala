package dev.famer.ticketing

import akka.actor.typed.{ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import dev.famer.ticketing.proto.{Event, State}

import java.time.Instant
import scala.concurrent.duration.DurationInt

object TicketBehavior {
  val Name = "Ticket"
  val tags: Vector[String] = Vector.tabulate(32)(i => s"ticketing-$i")
  val EntityKey = EntityTypeKey[proto.Command](Name)

  def init(system: ActorSystem[_]) = ClusterSharding(system).init(Entity(EntityKey) { entityContext =>
    val i = math.abs(entityContext.entityId.hashCode % tags.size)
    val selectedTag = tags(i)
    this (entityContext.entityId, selectedTag)
  }.withRole("write-model"))

  def apply(ticketId: String, projectionTag: String): Behavior[proto.Command] = {
    EventSourcedBehavior
      .withEnforcedReplies[proto.Command, proto.Event, proto.State](
        PersistenceId(EntityKey.name, ticketId),
        proto.State.defaultInstance,
        (state, command) => state.kind match {
          case proto.State.Kind.Empty => emptyHandleCommand(ticketId, command)
          case proto.State.Kind.Has(value) => hasHandleCommand(ticketId, value, command)
        },
        (state, event) => state.kind match {
          case proto.State.Kind.Empty => emptyHandleEvent(event)
          case proto.State.Kind.Has(value) => hasHandleEvent(value, event)
        })
      .withTagger(_ => Set(projectionTag))
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }

  def emptyHandleCommand(ticketId: String, command: proto.Command): ReplyEffect[Event, State] = {
    command.kind match {
      case proto.Command.Kind.Empty => Effect.noReply
      case proto.Command.Kind.Take(value) =>
        Effect.reply(value.replyTo.serialized)(takeNotFound())
      case proto.Command.Kind.TakeMany(value) =>
        Effect.reply(value.replyTo.serialized)(takeManyNotFound())
      case proto.Command.Kind.Make(value) =>
        Effect.persist(eventMade(ticketId, value))
          .thenReply(value.replyTo.serialized)(_ => makeDone())
    }
  }

  def hasHandleCommand(ticketId: String, state: proto.State.Has, command: proto.Command): ReplyEffect[Event, State] = {
    command.kind match {
      case proto.Command.Kind.Empty => Effect.noReply
      case proto.Command.Kind.Take(value) => {
        if (!state.available) {
          Effect.reply(value.replyTo.serialized)(takeError(s"Ticket($ticketId) is disabled"))
        } else if (state.remain > value.quota) {
          Effect.persist(eventTook(ticketId, value))
            .thenReply(value.replyTo.serialized)(_ => takeDone())
        } else {
          Effect.reply(value.replyTo.serialized)(takeInsufficient(s"remain(${state.remain}) is less than take(${value.quota})"))
        }
      }
      case proto.Command.Kind.TakeMany(value) =>
        if (!state.available) {
          Effect.reply(value.replyTo.serialized)(takeManyError(s"Ticket($ticketId) is disabled"))
        } else {
          val (_, consume) = value.list.foldLeft((state.remain, Map.empty[String, Int])) { case ((remain, consume), (subject, quota)) =>
            if (remain > quota) {
              (remain - quota, consume + (subject -> quota))
            } else {
              (remain, consume)
            }
          }

          val rejected = value.list.keySet.removedAll(consume.keySet)
          Effect.persist(consume.grouped(2048).map(split => eventTookMany(ticketId, split)).toSeq)
//          Effect.persist(eventTookMany(ticketId, consume))
            .thenReply(value.replyTo.serialized)(_ => takeManyDone(rejected.toSeq))
        }
      case proto.Command.Kind.Make(value) =>
        Effect.reply(value.replyTo.serialized)(makeError("Conflict! ticket has been existed"))
    }
  }

  private def eventMade(ticketId: String, make: proto.Command.Make) = {
    val now = Instant.now()
    proto.Event(
      proto.Event.Kind.Made(
        proto.Event.Made(
          id = ticketId,
          time = com.google.protobuf.timestamp.Timestamp(now.getEpochSecond, now.getNano),
          quota = make.quota
        )
      )
    )
  }

  private def eventTook(ticketId: String, take: proto.Command.Take) = {
    val now = Instant.now()
    proto.Event(
      proto.Event.Kind.Took(
        proto.Event.Took(
          id = ticketId,
          time = com.google.protobuf.timestamp.Timestamp(now.getEpochSecond, now.getNano),
          subject = take.subject,
          quota = take.quota
        )
      )
    )
  }

  private def eventTookMany(ticketId: String, list: Map[String, Int]) = {
    val now = Instant.now()
    proto.Event(
      proto.Event.Kind.TookMany(
        proto.Event.TookMany(
          id = ticketId,
          time = com.google.protobuf.timestamp.Timestamp(now.getEpochSecond, now.getNano),
          list = list
        )
      )
    )
  }

  private def makeDone() = {
    proto.MakeReply(
      proto.MakeReply.Kind.Done(
        proto.MakeReply.Done()
      )
    )
  }

  private def makeError(msg: String) = {
    proto.MakeReply(
      proto.MakeReply.Kind.Error(
        proto.MakeReply.Error(msg)
      )
    )
  }

  private def takeError(msg: String) = {
    proto.TakeReply(
      proto.TakeReply.Kind.Error(
        proto.TakeReply.Error(msg)
      )
    )
  }

  private def takeManyError(msg: String) = {
    proto.TakeManyReply(
      proto.TakeManyReply.Kind.Error(
        proto.TakeManyReply.Error(msg)
      )
    )
  }

  private def takeNotFound() = {
    proto.TakeReply(
      proto.TakeReply.Kind.NotFound(
        proto.TakeReply.NotFound()
      )
    )
  }

  private def takeManyNotFound() = {
    proto.TakeManyReply(
      proto.TakeManyReply.Kind.NotFound(
        proto.TakeManyReply.NotFound()
      )
    )
  }

  private def takeDone() = {
    proto.TakeReply(
      proto.TakeReply.Kind.Done(
        proto.TakeReply.Done()
      )
    )
  }

  private def takeManyDone(rejected: Seq[String]) = {
    proto.TakeManyReply(
      proto.TakeManyReply.Kind.Done(
        proto.TakeManyReply.Done(rejected)
      )
    )
  }


  private def takeInsufficient(msg: String) = {
    proto.TakeReply(
      proto.TakeReply.Kind.Insufficient(
        proto.TakeReply.Insufficient(msg)
      )
    )
  }

  def emptyHandleEvent(event: proto.Event): proto.State = event.kind match {
    case proto.Event.Kind.Made(value) =>
      proto.State(
        proto.State.Kind.Has(
          proto.State.Has(
            available = true,
            remain = value.quota,
            applied = Map.empty
          )
        )
      )
    case _ => proto.State.defaultInstance
  }

  def hasHandleEvent(state: proto.State.Has, event: proto.Event): proto.State = event.kind match {
    case proto.Event.Kind.Empty =>
      proto.State(proto.State.Kind.Has(state))
    case proto.Event.Kind.Made(value) => // NOTE: Impossible branch
      proto.State(
        proto.State.Kind.Has(
          proto.State.Has(
            available = true,
            remain = value.quota
          )
        )
      )
    case proto.Event.Kind.Took(value) =>
      proto.State(proto.State.Kind.Has(
        state.update(_.remain.modify(_ - value.quota))
          .update(_.applied.modify(_ + (value.subject -> value.quota)))
      ))
    case proto.Event.Kind.TookMany(value) =>
      proto.State(proto.State.Kind.Has(
        state.update(_.remain.modify(_ - value.list.values.sum))
          .update(_.applied.modify(_ ++ value.list))
      ))
    case proto.Event.Kind.QuotaAdded(value) =>
      proto.State(proto.State.Kind.Has(
        state.update(_.remain.modify(_ + value.quota))
      ))
    case proto.Event.Kind.QuotaAdjusted(value) =>
      proto.State(proto.State.Kind.Has(
        state.update(_.remain.set(value.quota))
      ))
    case proto.Event.Kind.Destroy(_) =>
      proto.State(proto.State.Kind.Empty)
  }
}
