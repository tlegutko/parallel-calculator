package testapp

import scala.concurrent._

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._

object ParallelCalculatorStreams {
  type ExprRes = Expression.Result
  type ExprSource = Source[ExprRes, NotUsed]
  type ExprOp = (ExprRes, ExprRes) => ExprRes

  def apply(expr: Expression): RunnableGraph[Future[Expression.Result]] =
    exprToStream(expr).toMat(Sink.head[Expression.Result])(Keep.right)

  def exprToStream(expr: Expression): ExprSource = {
    expr match {
      case Mul(l, r) => exprToGraph(l, r, (_ * _))
      case Div(l, r) => exprToGraph(l, r, (_ / _))
      case Sum(l, r) => exprToGraph(l, r, (_ + _))
      case Sub(l, r) => exprToGraph(l, r, (_ - _))
      case Num(x) => Source.single(x)
    }
  }
  def exprToGraph(l: Expression, r: Expression, op: ExprOp): ExprSource = {
    (l, r) match {
      case (Num(x), Num(y)) => createGraph(Source.single(x), Source.single(y), op)
      case (xExpr, Num(y)) => createGraph(exprToStream(xExpr), Source.single(y), op)
      case (Num(x), yExpr) => createGraph(Source.single(x), exprToStream(yExpr), op)
      case (xExpr, yExpr) => createGraph(exprToStream(xExpr), exprToStream(yExpr), op)
    }
  }
  def createGraph(s0: ExprSource, s1: ExprSource, op: ExprOp): ExprSource = {
      Source.fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._
        val zipOp = b.add(ZipWith[ExprRes, ExprRes, ExprRes](op))
        s0.async ~> zipOp.in0
        s1.async ~> zipOp.in1
        SourceShape(zipOp.out)
      })
  }
}
