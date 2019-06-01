package slack.net

import cats.effect.ContextShift
import spinoco.fs2.http.websocket.WebSocketRequest
import fs2.Pipe
import spinoco.fs2.http.websocket.Frame
import cats.effect.ConcurrentEffect
import cats.effect.Timer
import java.nio.channels.AsynchronousChannelGroup
import cats.Applicative
import spinoco.fs2.http.websocket.WebSocket
import cats.implicits._
import scodec.Decoder
import scodec.Encoder

trait WS[F[_]] {
  def request[I: scodec.Decoder, O: Encoder](req: WebSocketRequest)(pipe: Pipe[F, Frame[I], Frame[O]]): F[Unit]
}

object WS {
  def apply[F[_]](implicit F: WS[F]): WS[F] = F

  def default[F[_]: ConcurrentEffect: Timer: ContextShift](
    implicit ag: AsynchronousChannelGroup
  ): WS[F] = new WS[F] {

    def request[I: Decoder, O: Encoder](
      req: WebSocketRequest
    )(pipe: Pipe[F, Frame[I], Frame[O]]): F[Unit] = {
      WebSocket.client[F, I, O](req, pipe).compile.lastOrError.flatMap {
        case None => Applicative[F].unit
        case Some(errorResponse) =>
          new Throwable(s"Failed to establish websocket connection to $req. Server response: $errorResponse")
            .raiseError[F, Unit]
      }
    }
  }
}
