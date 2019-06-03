package slack

import cats.effect.Concurrent
import slack.net.WS
import spinoco.fs2.http.HttpClient

trait Slack[F[_]] {
  def rtm: RTM[F]
  def chat: Chat[F]
  def users: Users[F]
}

object Slack {

  def http[F[_]: WS: HttpClient: Concurrent]: Slack[F] = new Slack[F] {
    override val rtm: RTM[F] = RTM.http

    override val chat: Chat[F] = Chat.http

    override val users: Users[F] = Users.http
  }
}
