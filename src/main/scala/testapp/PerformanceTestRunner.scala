package testapp

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import scala.util.{ Failure, Success }

object PerformanceTestRunner extends App { // defined as regular class, tests get different execution model
  implicit val system = ActorSystem();
  implicit val materializer = ActorMaterializer.create(system)
  implicit val ec = system.dispatcher

  val r = new scala.util.Random(System.currentTimeMillis())
  def exprGen(lvl: Int): Expression = { // full binary tree: 2^(lvl-1)-1 operations
    if (lvl == 0)
      Num(r.nextInt(3).toDouble+r.nextDouble())
    else
      r.nextInt(4) match {
        case 0 => Mul(exprGen(lvl-1), exprGen(lvl-1))
        case 1 => Div(exprGen(lvl-1), exprGen(lvl-1))
        case 2 => Sum(exprGen(lvl-1), exprGen(lvl-1))
        case 3 => Sub(exprGen(lvl-1), exprGen(lvl-1))
      }
  }
  def runner[R](block: => R): (R, Double) = {
    val t0 = System.currentTimeMillis()
    val result = block
    val t1 = System.currentTimeMillis()
    val execTime = (t1 - t0)/1000.0
    (result, execTime)
  }
  def singleThreadedRun(e: Expression) = {
    val (res, time) = runner(e.evaluateInSingleThread)
    println(s"single thread, res $res, time ${time}s")
  }
  def parallelRun(e: Expression) = {
    val (res, time) = runner(ParallelCalculatorStreams(e).run())
    res.onComplete {
      case Success(res) => println(s"parallel streams, res $res, time ${time}s");
      case Failure(res) => println(s"parallel streams, failure, res $res, time ${time}s");
    }
  }
  for (n <- (14 to 19)) {
    val e = exprGen(n)
    val opNum = Math.pow(2, n-1.0).toInt - 1
    println(s"processing $opNum operations")
    singleThreadedRun(e)
    parallelRun(e)
    Thread.sleep(1000)
  }
  Thread.sleep(1000)
  system.terminate
}
