package dev.famer.ticketing

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding

object Server extends App {
  private val system = ActorSystem(Bootstrap.setup, "Ticketing")
  private val clusterSharding = ClusterSharding(system)
  private implicit val ec = system.executionContext

  val entityRef = clusterSharding.entityRefFor(TicketBehavior.EntityKey, "test")

  entityRef ! proto.Command()
}