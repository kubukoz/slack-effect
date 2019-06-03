package slack.internal

import io.circe
import io.circe.Json
import scodec.{Attempt, Codec, Decoder, Encoder, Err}
import spinoco.fs2.http.body.{BodyDecoder, BodyEncoder}
import spinoco.protocol.mime.{ContentType, MIMECharset, MediaType}
import io.circe.syntax._

object implicits {

  val jsonCodec: Codec[Json] = scodec.codecs.utf8.exmap(
    s =>
      io.circe.parser
        .parse(s)
        .fold(pf => Attempt.failure(Err("JSON parsing failure: " + pf.message)), Attempt.successful),
    json => Attempt.successful(json.noSpaces)
  )

  implicit def jsonEncoderForCirceEncoder[A: circe.Encoder]: Encoder[A] = {
    jsonCodec.contramap(_.asJson)
  }
  implicit def jsonDecoderForCirceDecoder[A: circe.Decoder]: Decoder[A] = {
    jsonCodec.emap {
      _.as[A].fold(df => Attempt.failure(Err(df.message)), Attempt.successful)
    }
  }

  implicit def jsonDecoder[A: circe.Decoder]: BodyDecoder[A] = BodyDecoder.forDecoder[A] {
    case ContentType.TextContent(spinoco.protocol.mime.MediaType.`application/json`, _) =>
      Attempt.successful(jsonDecoderForCirceDecoder[A])
    case other =>
      Attempt.failure(Err(other.mediaType + " was not " + spinoco.protocol.mime.MediaType.`application/json`))
  }

  implicit def jsonEncoderForA[A: circe.Encoder]: BodyEncoder[A] = {
    BodyEncoder.utf8String
      .withContentType(
        ContentType.TextContent(MediaType.`application/json`, Some(MIMECharset.`UTF-8`))
      )
      .mapIn[A](_.asJson.noSpaces)
  }
}
