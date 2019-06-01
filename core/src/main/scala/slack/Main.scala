package slack

import cats.effect.IOApp
import cats.effect.{ExitCode, IO}
import cats.implicits._
import spinoco.fs2.http.HttpClient
import java.util.concurrent.Executors
import java.nio.channels.AsynchronousChannelGroup
import cats.effect.Resource
import scala.concurrent.ExecutionContext
import spinoco.protocol.http.codec.HttpRequestHeaderCodec
import spinoco.protocol.http.codec.HttpResponseHeaderCodec
import javax.net.ssl.SSLContext
import slack.net.WS

final case class AppConfig(token: String, rtmMessageBufferSize: Int)

object Main extends IOApp {

  val config: AppConfig = ConfigImpl.value

  val cachedUnbounded =
    Resource.make(IO(Executors.newCachedThreadPool()))(e => IO(e.shutdown())).map(ExecutionContext.fromExecutorService)

  def run(args: List[String]): IO[ExitCode] =
    cachedUnbounded.use { ec =>
      implicit val acg    = AsynchronousChannelGroup.withThreadPool(ec)
      implicit val sslCtx = SSLContext.getDefault()

      implicit val ws: WS[IO] = WS.default

      HttpClient[IO](
        HttpRequestHeaderCodec.defaultCodec,
        HttpResponseHeaderCodec.defaultCodec,
        ec,
        sslCtx
      ).flatMap { implicit client =>
        RTM.http[IO](config).connect.showLinesStdOut.compile.drain
      }
    }.as(ExitCode.Success)
}
