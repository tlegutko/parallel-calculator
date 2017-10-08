package testapp

import scala.concurrent._
import scala.io.StdIn
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives
import akka.stream._
import spray.json._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val expressionStringFormat = jsonFormat1(StringExpression)
  implicit val expressionResultStringFormat = jsonFormat1(StringExpressionResult)
}

final case class StringExpression(expression: String)
final case class StringExpressionResult(result: Expression.Result)
final case class StringExpressionError(reason: Expression.Error)

object ParallelCalculatorServer extends Directives with JsonSupport {

  def main(args: Array[String]): Unit = { // TODO delete
    implicit val system = ActorSystem("my-app")
    implicit val materializer = ActorMaterializer.create(system)
    implicit val executionContext = system.dispatcher

    val result = ParallelCalculator.evaluate("(1-1)*2+3*(1-3+4)+10/2")
    result match {
      case Left(err) => println(err); system.terminate()
      case Right(futureRes) => futureRes.onComplete {
        case Success(res) => println(res); system.terminate()
        case Failure(ex) => println(ex); system.terminate()
      }
     }
  }

  def main2(args: Array[String]): Unit = {
    implicit val system = ActorSystem("test-app")
    implicit val materializer = ActorMaterializer.create(system)
    implicit val executionContext = system.dispatcher

    val route =
      path("evaluate") {
        post {
          entity(as[StringExpression]) { stringExpression =>
            ParallelCalculator.evaluate(stringExpression.expression) match {
              case Left(err) => complete(StatusCodes.UnprocessableEntity -> err.reason)
              case Right(futureRes) => onComplete(futureRes) {
                case Success(res) => complete(StringExpressionResult(res))
                case Failure(ex) => println(ex); complete(StatusCodes.InternalServerError -> Expression.StreamProcessingError().reason)
              }
            }
          }
        }
      }

    val bindingFuture = Http().bindAndHandle(route, "localhost", 5555)

    println(s"Server online at http://localhost:5555/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }

}
