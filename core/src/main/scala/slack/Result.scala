package slack

import cats.implicits._
import cats.{ApplicativeError, MonadError}
import io.circe.Decoder
import slack.Result.Error

sealed trait Result[+A] extends Product with Serializable {

  final def toEither: Either[Error, A] = this match {
    case Result.NotOk(message) => Left(Error(message))
    case Result.Ok(value)      => Right(value)
  }

  final def orThrow[F[_]]: Result.internal.OrThrow[F, A] = new Result.internal.OrThrow[F, A](this)
}

object Result {
  final case class NotOk(error: String) extends Result[Nothing]
  final case class Ok[A](value: A)      extends Result[A]

  final case class Error(message: String) extends AnyVal

  object internal {
    final class OrThrow[F[_], +A] private[Result] (val self: Result[A]) extends AnyVal {

      def apply[AA >: A](modifyError: String => String)(implicit F: ApplicativeError[F, Throwable]): F[AA] =
        self match {
          case NotOk(error) => F.raiseError(new Throwable(modifyError(error)))
          case Ok(value)    => value.pure[F].widen
        }
    }
  }

  implicit val monadError: MonadError[Result, String] = new MonadError[Result, String] {
    override def flatMap[A, B](fa: Result[A])(f: A => Result[B]): Result[B] = fa match {
      case e @ NotOk(_) => e
      case Ok(value)    => f(value)
    }

    @scala.annotation.tailrec
    override def tailRecM[A, B](a: A)(f: A => Result[Either[A, B]]): Result[B] = f(a) match {
      case e @ NotOk(_) => e
      case Ok(value) =>
        value match {
          case Left(l)  => tailRecM(l)(f)
          case Right(r) => Ok(r)
        }
    }

    override def raiseError[A](e: String): Result[A] = Result.NotOk(e)

    override def handleErrorWith[A](fa: Result[A])(f: String => Result[A]): Result[A] = fa match {
      case NotOk(error) => f(error)
      case r @ Ok(_)    => r
    }

    override def pure[A](x: A): Result[A] = Result.Ok(x)
  }

  implicit def circeDecoder[A: Decoder]: Decoder[Result[A]] = Decoder[Boolean].prepare(_.downField("ok")).flatMap {
    case true  => Decoder[A].map(Result.Ok(_))
    case false => Decoder[String].prepare(_.downField("error")).map(Result.NotOk)
  }
}
