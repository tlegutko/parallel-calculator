package testapp

import akka.http.scaladsl.model.StatusCodes
import scala.io.StdIn

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import spray.json._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val expressionStringFormat = jsonFormat1(StringExpression)
  implicit val expressionResultStringFormat = jsonFormat1(StringExpressionResult)
}

final case class StringExpression(expression: String)
final case class StringExpressionResult(result: Expression.Result)
final case class StringExpressionError(reason: Expression.Error)

object ParallelCalculatorServer extends Directives with JsonSupport {
  def main(args: Array[String]) = {

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      path("evaluate") {
        post {
          entity(as[StringExpression]) { stringExpression =>
            ParallelCalculator.evaluate(stringExpression.expression) match {
              case Right(res) => complete(StringExpressionResult(res))
              case Left(err) => complete(StatusCodes.UnprocessableEntity -> err.reason)
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

object ParallelCalculator {
  def evaluate(stringExpression: String): Either[Expression.Error, Expression.Result] = {
    // stringToExpression(stringExpression).map(_.evaluate)
    Right(11)
  }

  def stringToExpression(s: String): Either[Expression.Error, Expression] = {
    ???
  }

}

object Expression {
  type Result = Double
  case class Error(reason: String)
}
sealed trait Expression {
  def evaluate: Expression.Result
}
case class Sum(l: Expression, r: Expression) extends Expression {
  def evaluate = l.evaluate + r.evaluate
}

case class Mul(l: Expression, r: Expression) extends Expression {
  def evaluate = l.evaluate * r.evaluate
}

case class Num(n: Expression.Result) extends Expression {
  def evaluate = n
}
