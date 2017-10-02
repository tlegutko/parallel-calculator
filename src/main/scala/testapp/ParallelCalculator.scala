package testapp

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import scala.io.StdIn

object ParallelCalculatorServer {
  def main(args: Array[String]) {

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      path("evaluate") {
        get {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>(1-1)*2+3*(1-3+4)+10/2</h1>"))
        }
      }

    val bindingFuture = Http().bindAndHandle(route, "localhost", 5555)

    println(s"Server online at http://localhost:5555/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }

  def evaluate(expression: String): String = {
    "11"
  }
}
