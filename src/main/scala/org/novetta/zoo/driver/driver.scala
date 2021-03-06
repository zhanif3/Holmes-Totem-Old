package org.novetta.zoo.driver

import java.util.concurrent.{Executors, ExecutorService}

import akka.actor.{ActorRef, ActorSystem, Props}
import org.novetta.zoo.actors._
import org.novetta.zoo.services.{YaraSuccess, MetadataSuccess, YaraWork, MetadataWork}
import org.novetta.zoo.types._

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import akka.routing.RoundRobinPool
import org.novetta.zoo.util.Instrumented

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}

import scala.util.Random

object driver extends App with Instrumented {
  lazy val execServ: ExecutorService = Executors.newFixedThreadPool(4000)
  val conf: Config = if (args.length > 0) {
    println("we have args > 0, using args")
    ConfigFactory.parseFile(new File(args(0)))

  } else {
    ConfigFactory.parseFile(new File("/Users/totem/novetta/totem/config/totem.conf"))
  }
  val system = ActorSystem("totem")

  val hostConfig = HostSettings(
    conf.getString("zoo.rabbit_settings.host.server"),
    conf.getInt("zoo.rabbit_settings.host.port"),
    conf.getString("zoo.rabbit_settings.host.username"),
    conf.getString("zoo.rabbit_settings.host.password"),
    conf.getString("zoo.rabbit_settings.host.vhost")
  )

  val exchangeConfig = ExchangeSettings(
    conf.getString("zoo.rabbit_settings.exchange.name"),
    conf.getString("zoo.rabbit_settings.exchange.type"),
    conf.getBoolean("zoo.rabbit_settings.exchange.durable")
  )
  val workqueueConfig = QueueSettings(
    conf.getString("zoo.rabbit_settings.workqueue.name"),
    conf.getString("zoo.rabbit_settings.workqueue.routing_key"),
    conf.getBoolean("zoo.rabbit_settings.workqueue.durable"),
    conf.getBoolean("zoo.rabbit_settings.workqueue.exclusive"),
    conf.getBoolean("zoo.rabbit_settings.workqueue.autodelete")
  )
  val resultQueueConfig = QueueSettings(
    conf.getString("zoo.rabbit_settings.resultsqueue.name"),
    conf.getString("zoo.rabbit_settings.resultsqueue.routing_key"),
    conf.getBoolean("zoo.rabbit_settings.resultsqueue.durable"),
    conf.getBoolean("zoo.rabbit_settings.resultsqueue.exclusive"),
    conf.getBoolean("zoo.rabbit_settings.resultsqueue.autodelete")
  )

  class TotemicEncoding(conf: Config) extends ConfigTotemEncoding(conf) {
    def GeneratePartial(work: String): String = {
      work match {
        case "FILE_METADATA" => Random.shuffle(services.getOrElse("metadata", List())).head
        case "HASHES" => Random.shuffle(services.getOrElse("hashes", List())).head
        case "PEINFO" => Random.shuffle(services.getOrElse("peinfo", List())).head
        case "VTSAMPLE" => Random.shuffle(services.getOrElse("vtsample", List())).head
        case "YARA" => Random.shuffle(services.getOrElse("yara", List())).head
      }
    }

    def enumerateWork(key: Long, filename: String, workToDo: Map[String, List[String]]): List[TaskedWork] = {
      val w = workToDo.map({
        case ("FILE_METADATA", li: List[String]) =>
          MetadataWork(key, filename, 60, "FILE_METADATA", GeneratePartial("FILE_METADATA"), li)
        case ("YARA", li: List[String]) =>
          YaraWork(key, filename, 60, "YARA", GeneratePartial("YARA"), li)
        case (s: String, li: List[String]) =>
          UnsupportedWork(key, filename, 1, s, GeneratePartial(s), li)
        case _ => Unit
      }).collect({
        case x: TaskedWork => x
      })
      w.toList
    }

    def workRoutingKey(work: WorkResult): String = {
      work match {
        case x: MetadataSuccess => "metadata.result.static.zoo"
        case x: YaraSuccess => "yara.result.static.zoo"
      }
    }
  }

  val encoding = new TotemicEncoding(conf)

  val myGetter: ActorRef = system.actorOf(RabbitConsumerActor.props[ZooWork](hostConfig, exchangeConfig, workqueueConfig, encoding, Parsers.parseJ).withDispatcher("akka.actor.my-pinned-dispatcher"), "consumer")
  val mySender: ActorRef = system.actorOf(Props(classOf[RabbitProducerActor], hostConfig, exchangeConfig, resultQueueConfig, conf.getString("zoo.requeueKey"), conf.getString("zoo.misbehaveKey")), "producer")

  val zoowork = ZooWork("http://127.0.0.1:9900/000a887477d86792d38bac9bbe786ed5", "http://127.0.0.1:9990/000a887477d86792d38bac9bbe786ed5",
    "000a887477d86792d38bac9bbe786ed5", Map[String, List[String]]("HASHES" -> List[String](), "FILE_METADATA" -> List[String]()), 0)
  val zooworkSecond = ZooWork("http://127.0.0.1:9900/000a887477d86792d38bac9bbe786ed5", "http://127.0.0.1:9900/000a887477d86792d38bac9bbe786ed5",
    "000a887477d86792d38bac9bbe786ed5", Map[String, List[String]]("FILE_METADATA" -> List[String](), "YARA" -> List[String](), "PEINFO" -> List[String]()), 0)

  val json = (
    ("primaryURI" -> zoowork.primaryURI) ~
    ("secondaryURI" -> zoowork.secondaryURI) ~
    ("filename" -> zoowork.filename) ~
    ("tasks" -> zoowork.tasks) ~
    ("attempts" -> zoowork.attempts)
    )
  val jsonSecond = (
    ("primaryURI" -> zooworkSecond.primaryURI) ~
      ("secondaryURI" -> zooworkSecond.secondaryURI) ~
      ("filename" -> zooworkSecond.filename) ~
      ("tasks" -> zooworkSecond.tasks) ~
      ("attempts" -> zoowork.attempts)
    )

  private[this] val loading = metrics.timer("loading")

  val j = compact(render(json))
  val j2 = loading.time({
    compact(render(jsonSecond))
  })

  mySender ! Send(RMQSendMessage(j2.getBytes, workqueueConfig.routingKey))

  println("running")
}
