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
import scala.util.{ Failure, Success }
import spray.json._
import akka.stream._
import akka.stream.scaladsl._
import akka.{ NotUsed, Done }
import akka.util.ByteString
import scala.concurrent._
import scala.concurrent.duration._
import java.nio.file.Paths

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
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher
    val result = ParallelCalculator.evaluate("123")
     result.onComplete {
      case Success(resultOrError) => resultOrError match {
        case Right(res) => println(res)
        case Left(err) => println(err)
      }
      case Failure(ex) => println("surprising fail!")
     }
    result.onComplete { _ => 
      Thread.sleep(1000)
      system.terminate()
    }
  }

  def main2(args: Array[String]): Unit = {

    implicit val system = ActorSystem("my-app")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      path("evaluate") {
        post {
          entity(as[StringExpression]) { stringExpression =>
            onComplete(ParallelCalculator.evaluate(stringExpression.expression)) {
              case Success(either) => either match {
                case Right(res) => complete(StringExpressionResult(res))
                case Left(err) => complete(StatusCodes.UnprocessableEntity -> err.reason)
              }
              case Failure(ex) => complete(StatusCodes.InternalServerError -> "Error during stream processing!")
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
  def evaluate(stringExpression: String)(implicit mat: ActorMaterializer, ec: ExecutionContext): Future[Either[Expression.Error, Expression.Result]] = {
    stringToExpression(stringExpression) match {
      case Left(err) => Future(Left(err))
      case Right(expression) => {
        val sink = Sink.head[Int]
        val g = RunnableGraph.fromGraph(GraphDSL.create(sink) { implicit builder => out =>

          import GraphDSL.Implicits._
          val in = Source(1 to 5)
          // val out: Sink[Int, Future[Int]] = Sink.head

          val bcast = builder.add(Broadcast[Int](2))
          val merge = builder.add(Merge[Int](2))

          val f1, f2, f3, f4 = Flow[Int].map(_ + 10)

          in ~> f1 ~> bcast ~> f2 ~> merge ~> f3 ~> out
          bcast ~> f4 ~> merge
          ClosedShape
        })
        g.run().map(Right(_))
      }
    }

  }

  def stringToExpression(s: String): Either[Expression.Error, Expression] = {
    // TODO
    Right(Num(2))
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
