package org.phase.chat

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import fs2.concurrent.{Queue, Topic}
import io.circe.Json
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, MediaType}
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import fs2.{Pipe, Stream}
import org.http4s.headers.`Content-Type`

object ChatRoutes {

  def jokeRoutes[F[_]: Sync](J: Jokes[F]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F]{}
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "joke" =>
        for {
          joke <- J.get
          resp <- Ok(joke)
        } yield resp
    }
  }

  def helloWorldRoutes[F[_]: Sync](H: HelloWorld[F]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F]{}
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "hello" / name =>
        for {
          greeting <- H.hello(HelloWorld.Name(name))
          resp <- Ok(greeting)
        } yield resp
    }
  }

  def chatRoutes[F[_]: Sync: Concurrent](q: Queue[F, FromClient], t: Topic[F, ToClient], ref: Ref[F, State]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F]{}
    import dsl._

    HttpRoutes.of[F] {
      case GET -> Root / "metrics" =>
        val outputStream: Stream[F, String] = Stream
          .eval(ref.get)
          .map(state =>
            s"""
               |<html>
               |<title>Chat Server State</title>
               |<body>
               |<div>MessageCount: ${state.messageCount}</div>
               |</body>
               |</html>
              """.stripMargin)

        Ok(outputStream, `Content-Type`(MediaType.text.html))

      case GET -> Root / "ws" / userName =>
        val toClient = t
          .subscribe(1000)
          .map(toClientMessage =>
            WebSocketFrame.Text(toClientMessage.message)
          )

        WebSocketBuilder[F].build(toClient, _.collect({
            case WebSocketFrame.Text(text, _) =>
              FromClient(userName, text)
          })
          .through(q.enqueue)
        )
    }
  }
}
