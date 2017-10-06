package testapp

import akka.http.scaladsl.model.StatusCodes
import scala.collection.concurrent.TrieMap
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
    result match {
      case Left(err) => println(err)
      case Right(futureRes) => futureRes.onComplete {
        case Success(res) => println(res); system.terminate()
        case Failure(ex) => println(ex); system.terminate()
      }
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
            ParallelCalculator.evaluate(stringExpression.expression) match {
              case Left(err) => complete(StatusCodes.UnprocessableEntity -> err.reason)
              case Right(futureRes) => onComplete(futureRes) {
                case Success(res) => complete(StringExpressionResult(res))
                case Failure(ex) => println(ex); complete(StatusCodes.InternalServerError -> Expression.StreamProcessingError.reason)
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


object ParallelCalculator {
  def evaluate(s: String)(implicit mat: ActorMaterializer): Either[Expression.Error, Future[Expression.Result]] =
    stringToExpression(s).map(createCalculationGraph(_).run())
  
  def createCalculationGraph(calculation: Calculation): RunnableGraph[Future[Expression.Result]] = {
        val sink = Sink.head[Expression.Result]
        RunnableGraph.fromGraph(GraphDSL.create(sink) { implicit builder => out =>
          // import GraphDSL.Implicits._
          // val in = Source(1 to 5).map(_.toDouble)
          // val out: Sink[Int, Future[Int]] = Sink.head

          // val bcast = builder.add(Broadcast[Expression.Result](2))
          // val merge = builder.add(Merge[Expression.Result](2))

          // val f1, f2, f3, f4 = Flow[Expression.Result].map(_ + 10)
          // in ~> f1 ~> bcast ~> f2 ~> merge ~> f3 ~> out
          // bcast ~> f4 ~> merge
          //                         // val in = Source.single(calculation)
                                  // val evalFlow = Flow[Expression].map(_.evaluate)
                                  // val parallelBalancer = balancer()

          ClosedShape
        })

  }

  def balancer[In, Out](worker: Flow[In, Out, Any], workerCount: Int): Flow[In, Out, NotUsed] = {
  import GraphDSL.Implicits._

  Flow.fromGraph(GraphDSL.create() { implicit b =>
    val balancer = b.add(Balance[In](workerCount, waitForAllDownstreams = true))
    val merge = b.add(Merge[Out](workerCount))

    for (_ <- 1 to workerCount) {
      // for each worker, add an edge from the balancer to the worker, then wire
      // it to the merge element
      balancer ~> worker.async ~> merge
    }

    FlowShape(balancer.in, merge.out)
  })
  }

  def stringToExpression(s: String): Either[Expression.Error, Calculation] = {
    // TODO
    Right(Calculation(TrieMap(0 -> List(Sum(Num(2), Num(2)))), 0))
  }

}

case class Calculation(expressionMap: TrieMap[Expression.Level, List[Expression]], topLevel: Expression.Level)

object Expression {
  type Result = Double
  type Level = Int
  sealed abstract class Error(val reason: String)
  case object StreamProcessingError extends Error("Error during stream processing")
}


sealed trait Expression {
  def evaluate: Expression.Result
  // def leftP: Option[Expression] //  is left expression in parent
  // def rightP: Option[Expression] // is right expression in parent
}

case class ExpressionLevel(e: Expression, level: Int) extends Expression {
  def evaluate = e.evaluate
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
