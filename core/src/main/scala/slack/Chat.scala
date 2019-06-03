package slack

import cats.effect.Sync
import io.circe
import io.circe.generic.extras.semiauto._
import slack.internal.Constants
import slack.internal.implicits._
import spinoco.fs2.http.{HttpClient, HttpRequest}
import spinoco.protocol.http.Uri
import spinoco.protocol.http.header.Authorization
import spinoco.protocol.http.header.value.HttpCredentials

trait Chat[F[_]] {
  def postMessage(command: PostMessage, token: String): F[Unit]
}

object Chat {

  def http[F[_]: Sync](implicit client: HttpClient[F]): Chat[F] = new Chat[F] {

    override def postMessage(command: PostMessage, token: String): F[Unit] = {
      client
        .request(
          HttpRequest
            .post[F, PostMessage](
              Uri.https(Constants.ApiHost, "/api/chat.postMessage"),
              command
            )
            .withHeader(Authorization(HttpCredentials.OAuthToken("Bearer", token)))
        )
        .map(_.body)
        .compile
        .drain
    }
  }
}

case class PostMessage(channel: String, text: String, linkNames: Boolean = false)

object PostMessage extends slack.internal.CirceConfig {
  implicit val encoder: circe.Encoder[PostMessage] = deriveEncoder
}
