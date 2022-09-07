package dev.famer.ticketing

import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.pattern.after
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.typed.scaladsl.ActorSource
import akka.stream.{CompletionStrategy, OverflowStrategy}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Random

object LocalCluster extends App {
  val Name = "Ticketing"

  private def system(config: Config) = ActorSystem(Bootstrap.setup, Name, config)
  private val conf = ConfigFactory.load()

  val seedNodes = for (i <- 0 until 3) yield s"akka://$Name@127.0.0.1:${25520 + i}"

  val systems = for (i <- 0 until  3) yield {
    val nodeConf = ConfigFactory.parseString(
      s"""{
         | akka {
         |   cluster {
         |    seed-nodes = [
         |      ${seedNodes.map(addr => s"\"$addr\"").mkString("\n")}
         |    ]
         |    sharding {
         |      # Number of shards used by the default HashCodeMessageExtractor
         |      # when no other message extractor is defined. This value must be
         |      # the same for all nodes in the cluster and that is verified by
         |      # configuration check when joining. Changing the value requires
         |      # stopping all nodes in the cluster.
         |      number-of-shards = 36
         |    }
         |
         |    downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
         |    shutdown-after-unsuccessful-join-seed-nodes = 30s
         |    roles = ["write-model"]
         |  }
         |   remote.artery {
         |     canonical {
         |       port = ${25520 + i}
         |       hostname = "127.0.0.1"
         |     }
         |   }
         |   management.http.port = ${8558 + i}
         | }
         |}""".stripMargin).withFallback(conf)
    system(nodeConf)
  }

  implicit val sys = systems.head
  val clusterSharding = ClusterSharding(sys)
  implicit val ec = sys.executionContext
  implicit val timeout = Timeout(30.seconds)


  def take(subject: String, quota: Int)(replyTo: ActorRef[proto.TakeReply]) =
    proto.Command(proto.Command.Kind.Take(proto.Command.Take(subject, quota, proto.ActorRefTakeReply(replyTo))))

  def nakedTake(subject: String, quota: Int)(replyTo: ActorRef[proto.TakeReply]) =
    proto.Command.Take(subject, quota, proto.ActorRefTakeReply(replyTo))


  def make(quota: Int)(replyTo: ActorRef[proto.MakeReply]) =
    proto.Command(proto.Command.Kind.Make(proto.Command.Make(quota, proto.ActorRefMakeReply(replyTo))))


  def takeMany(list: Map[String, Int])(replyTo: ActorRef[proto.TakeManyReply]) =
    proto.Command(proto.Command.Kind.TakeMany(proto.Command.TakeMany(list, proto.ActorRefTakeManyReply(replyTo))))

  def testFlow(entityId: String) = {
    val entityRef = clusterSharding.entityRefFor(TicketBehavior.EntityKey, entityId)

    val ingress: ActorRef[proto.Command.Take] = ActorSource.actorRef[proto.Command.Take](
      {
        case proto.Command.Take(subject, _, _, _) if (subject.isEmpty) => CompletionStrategy.draining
      },
      {
        case proto.Command.Take(_, quota, _, _) if (quota == 0) => new Exception("zero")
      },
      1024 * 1024,
      OverflowStrategy.fail
    )
      .groupedWithin(10 * 1024, 128.milliseconds)
      .toMat(Sink.foreachAsync(2) { commandList =>
        val replyMap = commandList.groupMapReduce(_.subject)(_.replyTo)((l, _) => l)
        val list = commandList.groupMapReduce(_.subject)(_.quota)((l, _) => l)
        for {
          reply <- entityRef ? takeMany(list)
        } yield {
          val rejected = reply.kind.done.get.rejected.toSet
          replyMap.map { case (subject, replyTo) =>
            val kind = if (rejected.contains(subject)) {
              proto.TakeReply.Kind.Insufficient(proto.TakeReply.Insufficient())
            } else {
              proto.TakeReply.Kind.Done(proto.TakeReply.Done())
            }

            replyTo.serialized ! proto.TakeReply(kind)
          }
        }
      })(Keep.left)
      .run()


    for {
      makeReply <- entityRef ? make(512 * 1024)
      _ = println(makeReply.kind)
      begin = System.currentTimeMillis()
      res <- Future.traverse((1 to 512 * 1024).toVector) { _ =>
        for {
          takeReply <- ingress ? nakedTake(Random.nextString(8), 1)
        } yield if(takeReply.kind.isDone) 1 else 0
      }
      end = System.currentTimeMillis()
    } yield println(s"!!!: ${end - begin}ms consume ${res.sum}")
  }

  after(30.seconds)(testFlow("coupon-13"))
    .onComplete(res => println(s"RES: $res"))
}

