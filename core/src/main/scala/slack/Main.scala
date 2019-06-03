package slack

import cats.effect.IOApp
import cats.effect.{ExitCode, IO}
import cats.implicits._
import spinoco.fs2.http.HttpClient
import java.util.concurrent.Executors
import java.nio.channels.AsynchronousChannelGroup

import cats.effect.Resource
import cats.effect.concurrent.Ref

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import spinoco.protocol.http.codec.HttpRequestHeaderCodec
import spinoco.protocol.http.codec.HttpResponseHeaderCodec
import javax.net.ssl.SSLContext
import slack.RTM.Event.Message
import slack.net.WS

final case class AppConfig(token: String, verb: String)

object Main extends IOApp {

  val config: AppConfig = ConfigImpl.value

  val cachedUnbounded: Resource[IO, ExecutionContextExecutorService] =
    Resource.make(IO(Executors.newCachedThreadPool()))(e => IO(e.shutdown())).map(ExecutionContext.fromExecutorService)

  def offensive(msg: Message): Boolean =
    msg.text.toLowerCase.contains(s"${config.verb} you")

  def run(args: List[String]): IO[ExitCode] =
    cachedUnbounded.use { ec =>
      val stdioLines =
        fs2.io.stdin[IO](4096, ec).through(fs2.text.utf8Decode[IO]).through(fs2.text.lines[IO]).map(_.trim)

      implicit val acg    = AsynchronousChannelGroup.withThreadPool(ec)
      implicit val sslCtx = SSLContext.getDefault()

      implicit val ws: WS[IO] = WS.default

      HttpClient[IO](
        HttpRequestHeaderCodec.defaultCodec,
        HttpResponseHeaderCodec.defaultCodec,
        ec,
        sslCtx
      ).flatMap { implicit client =>
        val slack  = Slack.http[IO]
        val listen = slack.rtm.connect(config.token)

        Ref[IO].of(Option.empty[String]).flatMap { lastReplied =>
          def sendToCurrent(message: String, getLast: IO[Option[String]] = lastReplied.get) =
            getLast.flatMap {
              case None => IO(println(s"Not sending $message, no channel set"))
              case Some(channel) =>
                slack.chat.postMessage(PostMessage(channel, message, linkNames = true), config.token)
            }

          val appStream = listen.evalTap {
            case Right(msg: Message) =>
              lastReplied.getAndSet(Some(msg.channel)).flatMap {
                case Some(lastChannel) if lastChannel === msg.channel => IO.unit
                case _                                                => sendToCurrent("Hello! I will reply here now.", IO.pure(Some(msg.channel)))
              }
            case _ => IO.unit
          }.evalMap {
            case Right(msg: Message) if offensive(msg) =>
              slack.users.info(msg.user).flatMap { u =>
                slack.chat.postMessage(
                  PostMessage(msg.channel, s"${config.verb} you too @" + u.name, linkNames = true),
                  config.token
                )
              }
            case _ => IO.unit
          }

          (appStream concurrently stdioLines
            .evalMap(sendToCurrent(_))).onFinalize(sendToCurrent("I'm leaving!")).compile.drain
        }
      }
    }.as(ExitCode.Success)
}
