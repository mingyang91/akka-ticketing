package dev.famer.ticketing

import akka.actor.typed
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.scaladsl.adapter._
import akka.serialization.Serialization
import scalapb.TypeMapper

import java.nio.charset.StandardCharsets.UTF_8

// https://github.com/akka/akka/issues/27975
// this trait is used to serialize ActorRef[T]
trait ActorReferenceCompanion {

  type ActorRef[-T] = typed.ActorRef[T]

  lazy val resolver: ActorRefResolver = ActorRefResolver {
    Serialization.getCurrentTransportInformation().system.toTyped
  }

  implicit def mapper[T]: TypeMapper[String, ActorRef[T]] =
    TypeMapper[String, ActorRef[T]](resolver.resolveActorRef)(serialize)

  def serialize[T](ref: ActorRef[T]) = new String(resolver.toSerializationFormat(ref).getBytes(UTF_8), UTF_8)

}
