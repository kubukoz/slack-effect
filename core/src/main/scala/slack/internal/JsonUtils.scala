package slack.internal

import cats.effect.Sync
import io.circe.Decoder
import cats.implicits._

private[slack] object JsonUtils {

  def decodeAsJson[F[_]: Sync, A: Decoder](body: fs2.Stream[F, Byte]): F[A] = {
    //bodyAsString doesn't work with media type application/json
    body.through(fs2.text.utf8Decode[F]).compile.foldMonoid.flatMap(io.circe.parser.decode[A](_).liftTo[F])
  }
}
