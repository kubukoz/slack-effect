package slack

import cats.effect.IOApp
import cats.effect.{ExitCode, IO}
import cats.implicits._
import spinoco.protocol.http.Uri
import spinoco.fs2.http.HttpClient
import spinoco.fs2.http.HttpRequest
import io.circe.Decoder
import java.util.concurrent.Executors
import java.nio.channels.AsynchronousChannelGroup
import cats.effect.Resource
import scala.concurrent.ExecutionContext
import spinoco.protocol.http.codec.HttpRequestHeaderCodec
import spinoco.protocol.http.codec.HttpResponseHeaderCodec
import javax.net.ssl.SSLContext
import fs2.Stream
import spinoco.fs2.http.websocket.WebSocket
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import spinoco.fs2.http.websocket.WebSocketRequest
import cats.effect.Timer
import fs2.concurrent.Queue
import spinoco.fs2.http.websocket.Frame.Text
import io.circe.Json
import io.circe.generic.extras.Configuration
import java.{util => ju}

case class Config(token: String, rtmMessageBufferSize: Int)

object Main extends IOApp {
  val token = "TODO"

  val config = Config(token, rtmMessageBufferSize = 100)

  val cachedUnbounded =
    Resource.make(IO(Executors.newCachedThreadPool()))(e => IO(e.shutdown())).map(ExecutionContext.fromExecutorService)

  def run(args: List[String]): IO[ExitCode] =
    cachedUnbounded.use { ec =>
      implicit val acg    = AsynchronousChannelGroup.withThreadPool(ec)
      implicit val sslCtx = SSLContext.getDefault()

      HttpClient[IO](
        HttpRequestHeaderCodec.defaultCodec,
        HttpResponseHeaderCodec.defaultCodec,
        ec,
        sslCtx
      ).flatMap { implicit client =>
        RTM
          .http[IO](config)
          .connect
          .evalMap { event =>
            IO(println(event))
          }
          .compile
          .drain
      }
    }.as(ExitCode.Success)
}

trait Slack[F[_]] {
  def rtm: RTM[F]
}

trait RTM[F[_]] {
  def connect: Stream[F, Either[RTM.Event.Unknown, RTM.Event]]
}

object RTM {
  sealed trait Event extends Product with Serializable

  object Event {
    case object Hello                                    extends Event
    case class ReactionAdded(reaction: String)           extends Event
    case class UserTyping(channel: String, user: String) extends Event

    case class Message(
      text: String,
      user: String,
      channel: String,
      team: String,
      ts: String,
      event_ts: String,
      client_msg_id: ju.UUID,
      suppress_notification: Boolean
    ) extends Event

    case class Unknown(json: Json) {
      override def toString: String = {
        val typeString = json.asObject.flatMap(_.apply("type")).flatMap(_.asString).map("[" + _ + "]").getOrElse("")
        show"Unknown$typeString(${json.noSpaces})"
      }
    }

    import io.circe.generic.extras.semiauto.deriveDecoder

    implicit val config: Configuration =
      Configuration(identity, Configuration.snakeCaseTransformation, false, Some("type"))

    val decoder: Decoder[Either[Unknown, Event]] =
      (deriveDecoder[Event] either Decoder[Json].map(Unknown(_))).map(_.swap)
  }

  private[RTM] sealed trait ConnectResponse extends Product with Serializable

  private[RTM] object ConnectResponse {
    case class Ok(url: Uri)         extends ConnectResponse
    case class NotOk(error: String) extends ConnectResponse

    implicit val uriDecoder: Decoder[Uri] = Decoder[String].emap(Uri.parse(_).toEither.leftMap(_.messageWithContext))

    import io.circe.generic.semiauto.deriveDecoder

    implicit val decoder: Decoder[ConnectResponse] = for {
      ok       <- Decoder.instance(_.downField("ok").as[Boolean])
      response <- if (ok) deriveDecoder[Ok] else deriveDecoder[NotOk]
    } yield response
  }

  def http[F[_]: ConcurrentEffect: ContextShift: Timer](
    config: Config
  )(implicit client: HttpClient[F], ag: AsynchronousChannelGroup, sslCtx: SSLContext): RTM[F] = new RTM[F] {

    val connectRequest =
      HttpRequest.get[F](Uri.https("slack.com", "/api/rtm.connect")).withQuery(Uri.Query("token", config.token))

    val connect: Stream[F, Either[Event.Unknown, Event]] =
      client
        .request(connectRequest)
        .evalMap { response =>
          if (response.header.status.isSuccess)
            response.body
              .through(fs2.text.utf8Decode[F])
              .compile
              .foldMonoid
              .flatMap(io.circe.parser.decode[ConnectResponse](_).liftTo[F])
          else
            response.bodyAsString
              .flatMap(_.toEither.leftMap(e => new Throwable(e.messageWithContext)).liftTo[F])
              .flatMap(e => new Throwable("Unsuccessful request. Response: " + e).raiseError[F, ConnectResponse])
        }
        .flatMap {
          case ConnectResponse.Ok(url) =>
            val req = url.host.port match {
              case None       => WebSocketRequest.wss(url.host.host, url.path.stringify, url.query.params: _*)
              case Some(port) => WebSocketRequest.wss(url.host.host, port, url.path.stringify, url.query.params: _*)
            }

            implicit val strCodec = scodec.codecs.utf8

            Stream.eval(Queue.bounded[F, String](config.rtmMessageBufferSize)).flatMap { q =>
              val ws = WebSocket.client[F, String, String](req, _.collect {
                case Text(a) => a
              }.through(q.enqueue).drain, sslContext = sslCtx)

              q.dequeue
                .evalMap(io.circe.parser.decode(_)(Event.decoder).leftMap(e => new Throwable(e)).liftTo[F]) concurrently ws
            }

          case ConnectResponse.NotOk(error) =>
            Stream.raiseError[F](new Throwable(show"Failed to connect to RTM: $error"))
        }
  }
}
