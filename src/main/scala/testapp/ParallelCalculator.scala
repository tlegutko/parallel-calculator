package testapp

import scala.concurrent._
import scala.util.parsing.combinator._

import akka.stream._

object ParallelCalculator {
  def evaluate(s: String)(implicit mat: ActorMaterializer): Either[Expression.Error, Future[Expression.Result]] =
  stringToExpression(s).map(ParallelCalculatorStreams(_).run())

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

object Expression {
  type Result = Double
  type Level = Int
  sealed trait Error{ def reason: String }
  case class StreamProcessingError(reason: String = "Error during stream processing") extends Error
  case class DivisionByZeroError(reason: String = "Unable to process expression containing division by 0") extends Error
  case class ParsingError(reason: String) extends Error
}


sealed trait Expression {
  def evaluateInSingleThread = evaluate // useful for performance profiling
  private def evaluate: Expression.Result = this match {
    case Mul(l, r) => l.evaluate * r.evaluate
    case Div(l, r) => l.evaluate / r.evaluate
    case Sum(l, r) => l.evaluate + r.evaluate
    case Sub(l, r) => l.evaluate - r.evaluate
    case Num(n) => n
  }
}

case class Mul(l: Expression, r: Expression) extends Expression
case class Div(l: Expression, r: Expression) extends Expression
case class Sum(l: Expression, r: Expression) extends Expression
case class Sub(l: Expression, r: Expression) extends Expression
case class Num(n: Expression.Result) extends Expression
