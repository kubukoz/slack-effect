package slack

import java.util.UUID

import cats.Show
import cats.effect.Concurrent
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import io.circe.generic.extras.semiauto.deriveDecoder
import io.circe.{Decoder, Json}
import scodec.Codec
import slack.internal.{Constants, JsonUtils}
import slack.net.WS
import spinoco.fs2.http.websocket.{Frame, WebSocketRequest}
import spinoco.fs2.http.{HttpClient, HttpRequest}
import spinoco.protocol.http.{HttpMethod, HttpRequestHeader, Uri}

trait RTM[F[_]] {

  /**
    * Calls the /api/rtm.connect endpoint and opens a websocket connection to the acquired URL.
    * Streams decoded events (or the raw Json values wrapped in [[RTM.Event.Unknown]]).
    * */
  def connect(token: String, maxBufferSize: Int = 100): Stream[F, Either[RTM.Event.Unknown, RTM.Event]]
}

object RTM {
  sealed trait Event extends Product with Serializable

  object Event extends slack.internal.CirceConfig {
    case object Hello                                    extends Event
    case class ReactionAdded(reaction: String)           extends Event
    case class UserTyping(channel: String, user: String) extends Event

    case class Message(
      text: String,
      user: String,
      channel: String,
      team: String,
      ts: String,
      eventTs: String,
      clientMsgId: UUID,
      suppressNotification: Boolean
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

    implicit val decoder: Decoder[Event] = deriveDecoder
  }

  case class Connected(url: Uri) extends AnyVal

  object Connected extends slack.internal.CirceConfig {
    private implicit val uriDecoder: Decoder[Uri] =
      Decoder[String].emap(Uri.parse(_).toEither.leftMap(_.messageWithContext))

    implicit val decoder: Decoder[Connected] = deriveDecoder
  }

  def http[F[_]: Concurrent: WS](implicit client: HttpClient[F]): RTM[F] = new RTM[F] {

    val decodeEventOrUnknown: String => F[Either[Event.Unknown, Event]] = {
      io.circe.parser
        .parse(_)
        .leftMap(failure => new Throwable("Parsing failure: " + failure.message, failure.underlying))
        .liftTo[F]
        .map { json =>
          json.as[Event] orElse Left(Event.Unknown(json))
        }
    }

    private def connectRequest(token: String): HttpRequest[F] =
      HttpRequest.get[F](Uri.https(Constants.ApiHost, "/api/rtm.connect")).withQuery(Uri.Query("token", token))

    def connect(token: String, maxBufferSize: Int = 100): Stream[F, Either[Event.Unknown, Event]] =
      client
        .request(connectRequest(token))
        .evalMap { response =>
          if (response.header.status.isSuccess)
            JsonUtils.decodeAsJson[F, Result[Connected]](response.body)
          else
            response.bodyAsString
              .flatMap(_.toEither.leftMap(e => new Throwable(e.messageWithContext)).liftTo[F])
              .flatMap(e => new Throwable("Unsuccessful request. Response: " + e).raiseError[F, Result[Connected]])
        }
        .evalMap(_.orThrow[F]("Failed to connect to RTM: " + _))
        .flatMap {
          case Connected(url) =>
            val req =
              WebSocketRequest(
                url.host,
                HttpRequestHeader(method = HttpMethod.GET, path = url.path, query = url.query),
                secure = true
              )

            implicit val strCodec: Codec[String] = scodec.codecs.utf8

            Stream.eval(Queue.bounded[F, String](maxBufferSize)).flatMap { q =>
              val ws = WS[F].request[String, String](req) {
                _.collect {
                  case Frame.Text(a) => a
                }.through(q.enqueue).drain
              }

              q.dequeue.evalMap(decodeEventOrUnknown) concurrently Stream.eval(ws)
            }
        }
  }
}
