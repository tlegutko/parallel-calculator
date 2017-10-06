package testapp

import org.scalatest._

class ParallelCalculatorSpec extends FlatSpec with Matchers {
  // "The calculator" should "answer perfectly right away" in {
  //   ParallelCalculator.evaluate("(1-1)*2+3*(1-3+4)+10/2") should be (Right(11))
  // }

  "The correct expression" should "evaluate correctly" in {
    Sum(Num(2), Sum(Mul(Num(2), Num(2)), Mul(Num(2), Num(2)))).evaluate should be (10)
  }

}
