package alpaca.service

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{
  BinaryMessage,
  TextMessage,
  WebSocketRequest,
  Message => WSMessage
}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import alpaca.dto.streaming.request.{
  AuthenticationRequest,
  AuthenticationRequestData
}
import io.nats.client._
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._

import scala.collection.immutable
import scala.concurrent.Future

class StreamingClient {

  val apiKey: String = ConfigService.accountKey
  val wsUrl: String =
    ConfigService.base_url
      .replace("https", "wss")
      .replace("http", "wss") + "/stream"
  val polygonServerURLs = Array(s"nats://${apiKey}@nats1.polygon.io:31101",
                                s"nats://${apiKey}@nats2.polygon.io:31102",
                                s"nats://${apiKey}@nats3.polygon.io:31103")

  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val options: Options =
    new Options.Builder().servers(polygonServerURLs).build()
  val nats: Connection = Nats.connect(options)
  val webSocketFlow =
    Http().webSocketClientFlow(WebSocketRequest(wsUrl))

  private var authenticated: Boolean = false

  def sub(list: List[String])
    : Map[String,
          (SourceQueueWithComplete[Message], Source[Message, NotUsed])] = {
    list.map {
      case subject
          if subject.startsWith("Q.") || subject.startsWith("T.") || subject
            .startsWith("A.") || subject
            .startsWith("AM") =>
        (subject, subscribePolygon(subject))
      case x => (x, subscribeAlpaca(x))
    }.toMap
  }

  def subscribeAlpaca(subject: String) = {
    val source = Source
      .queue[Message](bufferSize = 1000, OverflowStrategy.backpressure)
      .preMaterialize()

    val incoming: Sink[WSMessage, Future[Done]] =
      Sink.foreach[WSMessage] {
        case message: BinaryMessage.Strict =>
//          source._1.offer(message)
      }

    if (!authenticated) {
      val ar = AuthenticationRequest(
        data = AuthenticationRequestData(ConfigService.accountKey,
                                         ConfigService.accountSecret))

      val source = Source
        .queue[Message](bufferSize = 1000, OverflowStrategy.backpressure)
        .preMaterialize()

      val incomingAuth: Sink[WSMessage, Future[Done]] =
        Sink.foreach[WSMessage] {
          case message: BinaryMessage.Strict =>
//            message.source._1.offer(message)
        }

      val ars = ar.asJson.noSpaces
      val outgoing = Source.single(TextMessage(ars))
      outgoing
        .viaMat(webSocketFlow)(Keep.right)
        .toMat(incoming)(Keep.both)
        .run()
      authenticated = true

    }
    source
  }

  def subscribePolygon(subject: String)
    : (SourceQueueWithComplete[Message], Source[Message, NotUsed]) = {
    val source = Source
      .queue[Message](bufferSize = 1000, OverflowStrategy.backpressure)
      .preMaterialize()
    val sub = nats.subscribe(subject)
    val dispatcher = nats.createDispatcher(msg => {
      source._1.offer(msg)
    })
    dispatcher.subscribe(sub.getSubject)
    source
  }

}