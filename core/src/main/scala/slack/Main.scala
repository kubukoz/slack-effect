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
import _root_.spinoco.protocol.http.HttpRequestHeader
import spinoco.protocol.http.HttpMethod
import fs2.Pipe
import spinoco.fs2.http.websocket.Frame
import scodec.Encoder
import cats.Show
import cats.effect.Concurrent
import cats.Applicative

final case class AppConfig(token: String, rtmMessageBufferSize: Int)

object Main extends IOApp {

  val config: AppConfig = ConfigImpl.value

  val cachedUnbounded =
    Resource.make(IO(Executors.newCachedThreadPool()))(e => IO(e.shutdown())).map(ExecutionContext.fromExecutorService)

  def run(args: List[String]): IO[ExitCode] =
    cachedUnbounded.use { ec =>
      implicit val acg    = AsynchronousChannelGroup.withThreadPool(ec)
      implicit val sslCtx = SSLContext.getDefault()

      implicit val ws: WS[IO] = WS.default

      HttpClient[IO](
        HttpRequestHeaderCodec.defaultCodec,
        HttpResponseHeaderCodec.defaultCodec,
        ec,
        sslCtx
      ).flatMap { implicit client =>
        RTM.http[IO](config).connect.showLinesStdOut.compile.drain
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

    implicit val showUnknown: Show[Unknown] = Show.fromToString
    implicit val showEvent: Show[Event]     = Show.fromToString

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

  def http[F[_]: Concurrent: WS](
    config: AppConfig
  )(implicit client: HttpClient[F]): RTM[F] = new RTM[F] {

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
            val req =
              WebSocketRequest(
                url.host,
                HttpRequestHeader(method = HttpMethod.GET, path = url.path, query = url.query),
                secure = true
              )

            implicit val strCodec = scodec.codecs.utf8

            Stream.eval(Queue.bounded[F, String](config.rtmMessageBufferSize)).flatMap { q =>
              val ws = WS[F].request[String, String](req) {
                _.collect {
                  case Text(a) => a
                }.through(q.enqueue).drain
              }

              q.dequeue
                .evalMap(io.circe.parser.decode(_)(Event.decoder).leftMap(e => new Throwable(e)).liftTo[F]) concurrently
                Stream.eval(ws)
            }

          case ConnectResponse.NotOk(error) =>
            Stream.raiseError[F](new Throwable(show"Failed to connect to RTM: $error"))
        }
  }
}

trait WS[F[_]] {
  def request[I: scodec.Decoder, O: Encoder](req: WebSocketRequest)(pipe: Pipe[F, Frame[I], Frame[O]]): F[Unit]
}

object WS {
  def apply[F[_]](implicit F: WS[F]): WS[F] = F

  def default[F[_]: ConcurrentEffect: Timer: ContextShift](
    implicit ag: AsynchronousChannelGroup
  ): WS[F] = new WS[F] {

    def request[I: scodec.Decoder, O: Encoder](
      req: WebSocketRequest
    )(pipe: fs2.Pipe[F, Frame[I], Frame[O]]): F[Unit] = {
      WebSocket.client[F, I, O](req, pipe).compile.lastOrError.flatMap {
        case None => Applicative[F].unit
        case Some(errorResponse) =>
          new Throwable(s"Failed to establish websocket connection to $req. Server response: $errorResponse")
            .raiseError[F, Unit]
      }
    }
  }
}
