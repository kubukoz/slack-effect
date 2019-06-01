package slack

import fs2.Stream
import cats.implicits._
import io.circe.Decoder
import io.circe.generic.extras.semiauto.deriveDecoder
import io.circe.generic.extras.Configuration
import io.circe.Json
import cats.Show
import java.util.UUID
import spinoco.protocol.http.Uri
import cats.effect.Concurrent
import spinoco.fs2.http.HttpClient
import spinoco.fs2.http.websocket.WebSocketRequest
import spinoco.protocol.http.HttpRequestHeader
import fs2.concurrent.Queue
import spinoco.protocol.http.HttpMethod
import slack.net.WS
import spinoco.fs2.http.HttpRequest
import spinoco.fs2.http.websocket.Frame
import slack.internal.Constants
import slack.internal.JsonUtils

trait RTM[F[_]] {
  def connect: Stream[F, Either[RTM.Event.Unknown, RTM.Event]]
}

object RTM {
  sealed trait Event extends Product with Serializable

  object Event {
    case object Hello                                     extends Event
    case class ReactionAdded(reaction: String)            extends Event
    case class UserTyping(chasnel: String, usser: String) extends Event

    case class Message(
      text: String,
      user: String,
      channel: String,
      team: String,
      ts: String,
      event_ts: String,
      client_msg_id: UUID,
      suppress_notification: Boolean
    ) extends Event

    case class Unknown(json: Json) {
      override def toString: String = {
        val typeString =
          json.asObject.flatMap(_.apply("type")).flatMap(_.asString).map(tpe => show"""[type="$tpe"]""").getOrElse("")
        show"Unknown$typeString(${json.noSpaces})"
      }
    }

    implicit val showUnknown: Show[Unknown] = Show.fromToString
    implicit val showEvent: Show[Event]     = Show.fromToString

    implicit val config: Configuration =
      Configuration(identity, Configuration.snakeCaseTransformation, false, Some("type"))

    implicit val decoder: Decoder[Event] = deriveDecoder
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

    val decodeEventOrUnknown: String => F[Either[Event.Unknown, Event]] = {
      io.circe.parser
        .parse(_)
        .leftMap(failure => new Throwable("Parsing failure: " + failure.message, failure.underlying))
        .liftTo[F]
        .map { json =>
          json.as[Event] orElse Left(Event.Unknown(json))
        }
    }

    val connectRequest =
      HttpRequest.get[F](Uri.https(Constants.ApiHost, "/api/rtm.connect")).withQuery(Uri.Query("token", config.token))

    val connect: Stream[F, Either[Event.Unknown, Event]] =
      client
        .request(connectRequest)
        .evalMap { response =>
          if (response.header.status.isSuccess)
            JsonUtils.decodeAsJson[F, ConnectResponse](response.body)
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
                  case Frame.Text(a) => a
                }.through(q.enqueue).drain
              }

              q.dequeue.evalMap(decodeEventOrUnknown) concurrently Stream.eval(ws)
            }

          case ConnectResponse.NotOk(error) =>
            Stream.raiseError[F](new Throwable(show"Failed to connect to RTM: $error"))
        }
  }
}
