package slack

import cats.Applicative
import cats.implicits._
import slack.data.User

trait Users[F[_]] {
  //todo more args
  def info(user: String): F[User]
}

object Users {

  def http[F[_]: Applicative]: Users[F] = new Users[F] {
    override def info(user: String): F[User] = User("kubukoz").pure[F]
  }
}
