package testapp



import scala.collection.concurrent.TrieMap
import scala.concurrent._
import scala.io.StdIn
import scala.util.{Failure, Success}
import scala.util.parsing.combinator._

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives
import akka.stream._
import akka.stream.scaladsl._
import spray.json._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val expressionStringFormat = jsonFormat1(StringExpression)
  implicit val expressionResultStringFormat = jsonFormat1(StringExpressionResult)
}

final case class StringExpression(expression: String)
final case class StringExpressionResult(result: Expression.Result)
final case class StringExpressionError(reason: Expression.Error)

object ParallelCalculatorServer extends Directives with JsonSupport {

  def main0(args: Array[String]): Unit = { // TODO delete
    val arith = new ExpressionParsers()
    arith.parseExpression("(1-1)*2+3*(1-3+4)+10/2")
  }

  def main1(args: Array[String]): Unit = { // TODO delete
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

  def main(args: Array[String]): Unit = {

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


object ParallelCalculator {
  def evaluate(s: String)(implicit mat: ActorMaterializer, ec: ExecutionContext): Either[Expression.Error, Future[Expression.Result]] =
    stringToExpression(s).map(expr => Future(expr.evaluate))
    // stringToExpression(s).map(createCalculationGraph(_).run())

  def createCalculationGraph(expression: Expression): RunnableGraph[Future[Expression.Result]] = {
    ???
        // val bindingFuturesink = Sink.head[Expression.Result]
        // RunnableGraph.fromGraph(GraphDSL.create(sink) { implicit builder => out =>
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

          // ClosedShape
        // })

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

  def stringToExpression(s: String): Either[Expression.Error, Expression] = {
    val parsers = new ExpressionParsers()
    parsers.parseExpression(s) match {
      case parsers.Success(res, _) => Right(res)
      case parsers.NoSuccess(err, _) => Left(Expression.ParsingError(err))
    }
  }

}

class ExpressionParsers extends JavaTokenParsers {
  def expr: Parser[Expression] = term ~! rep("+" ~! term | "-" ~! term) ^^ {
    case op ~ list => list.foldLeft(op) {
      case (x, "+" ~ y) => Sum(x, y)
      case (x, "-" ~ y) => Sub(x, y)
    }
  }
  def term: Parser[Expression] = factor ~! rep("*" ~! factor | "/" ~! factor) ^^ {
    case op ~ list => list.foldLeft(op) {
      case (x, "*" ~ y) => Mul(x, y)
      case (x, "/" ~ y) => Div(x, y)
    }
  }
  def factor: Parser[Expression] = "(" ~> expr <~ ")" | num
  def num = floatingPointNumber ^^ { n => Num(n.toDouble) }
  def parseExpression(expression: String) = parseAll(expr, expression)
}

case class Calculation(expressionMap: TrieMap[Expression.Level, List[Expression]], topLevel: Expression.Level)

object Expression {
  type Result = Double
  type Level = Int
  sealed trait Error{ def reason: String }
  case class StreamProcessingError(reason: String = "Error during stream processing") extends Error
  case class ParsingError(reason: String) extends Error
}


sealed trait Expression {
  def evaluate: Expression.Result = this match {
    case Mul(l, r) => l.evaluate * r.evaluate
    case Div(l, r) => l.evaluate / r.evaluate
    case Sum(l, r) => l.evaluate + r.evaluate
    case Sub(l, r) => l.evaluate - r.evaluate
    case Num(n) => n
  }
  // def leftP: Option[Expression] //  is left expression in parent
  // def rightP: Option[Expression] // is right expression in parent
}

// case class ExpressionLevel(e: Expression, level: Int) extends Expression
case class Mul(l: Expression, r: Expression) extends Expression
case class Div(l: Expression, r: Expression) extends Expression
case class Sum(l: Expression, r: Expression) extends Expression
case class Sub(l: Expression, r: Expression) extends Expression
case class Num(n: Expression.Result) extends Expression
