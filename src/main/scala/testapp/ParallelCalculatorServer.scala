package testapp

import java.text.DecimalFormat
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
  implicit val jsonInput = jsonFormat1(JsonExpressionInput)
  implicit object jsonOutput extends RootJsonFormat[JsonExpressionResult] {
    // custom serialization so instead of {"result": 11.0} there is {"result": 11}
    // that is no trailing zero, so it's exactly as specified in example
    def write(res: JsonExpressionResult) = 
      JsObject("result" -> JsNumber(new DecimalFormat("#.###").format(res.result)))

    def read(value: JsValue) = value match {
      case _ => deserializationError("Reading of result not expected")
    }
  }
}

final case class JsonExpressionInput(expression: String)
final case class JsonExpressionResult(result: Double)

object ParallelCalculatorServer extends Directives with JsonSupport {

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("test-app")
    implicit val materializer = ActorMaterializer.create(system)
    implicit val executionContext = system.dispatcher

    val route =
      path("evaluate") {
        post {
          entity(as[JsonExpressionInput]) { stringExpression =>
            ParallelCalculator.evaluate(stringExpression.expression) match {
              case Left(err) => complete(StatusCodes.UnprocessableEntity -> err.reason)
              case Right(futureRes) => onComplete(futureRes) {
                case Success(res) =>
                  if (res.isInfinite)
                    complete(StatusCodes.UnprocessableEntity -> Expression.DivisionByZeroError().reason)
                  else
                  complete(JsonExpressionResult(res))
                case Failure(ex) => complete(
                  StatusCodes.InternalServerError -> Expression.StreamProcessingError().reason)
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
