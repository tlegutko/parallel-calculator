package testapp

import org.scalatest._

class ParallelCalculatorSpec extends FlatSpec with Matchers {
  "The calculator" should "answer perfectly right away" in {
    ParallelCalculatorServer.evaluate("(1-1)*2+3*(1-3+4)+10/2") should be ("11")
  }
}
