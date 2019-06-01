package slack

trait Slack[F[_]] {
  def rtm: RTM[F]
}
