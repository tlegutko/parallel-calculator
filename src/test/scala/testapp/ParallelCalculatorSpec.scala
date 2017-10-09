package testapp

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpEntity, HttpMethods, HttpRequest, MediaTypes, StatusCodes }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import akka.util.ByteString
import org.scalatest._
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class ParallelCalculatorSpec extends FlatSpec with Matchers with ScalatestRouteTest with ParallelCalculatorRestService {

  def postRequest(expr: String): HttpRequest = {
    val jsonRequest = ByteString(
    s"""
        |{
        |    "expression": "$expr"
        |}
    """.stripMargin)

    HttpRequest(
      HttpMethods.POST,
      uri = "/evaluate",
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))
  }

  "The http server" should "return correct result for data from task specification" in {
    postRequest("(1-1)*2+3*(1-3+4)+10/2") ~> route ~> check {
      status.isSuccess() shouldEqual true
      responseAs[JsonExpressionResult].result shouldEqual 11
    }
  }

  it should "correctly handle expression with division by 0" in {
    postRequest("2/(3-3)") ~> route ~> check {
      status shouldEqual StatusCodes.UnprocessableEntity
      responseAs[String] shouldEqual Expression.DivisionByZeroError().reason
    }
  }

  it should "correctly respond to malformed requests" in {
    def malformedRequestCheck(s: String) = postRequest(s) ~> route ~> check {
      status shouldEqual StatusCodes.UnprocessableEntity
    }
    malformedRequestCheck("")
    malformedRequestCheck("1+")
    malformedRequestCheck("(1+3))")
    malformedRequestCheck("1+=2")
    malformedRequestCheck("abc")
  }

  "The correct expression" should "evaluate correctly on single thread" in {
    Sum(Num(2), Sum(Mul(Num(2), Num(2)), Mul(Num(2), Num(2)))).evaluateInSingleThread should be (10)
  }

  "Parsers combinators" should "correctly parse expressions" in {
    ParallelCalculator.stringToExpression("1+(3*3)") shouldEqual Right(Sum(Num(1), Mul(Num(3), Num(3))))
    ParallelCalculator.stringToExpression("(((3)))+(7*2/(5-5))") shouldEqual Right(Sum(Num(3), Div(Mul(Num(7), Num(2)), Sub(Num(5), Num(5)))))
  }

  it should "report errors on incorrect expressions" in {
    def exprError(s: String) = ParallelCalculator.stringToExpression(s) should matchPattern { case Left(Expression.ParsingError(_)) => }
    exprError("*20")
    exprError("")
    exprError("29*30/")
    exprError("*((3)+()")
    exprError("((3+2)")
  }

  "StreamsGraph" should "correctly evaluate expression" in {
    def streamTest(e: Expression, res: Double) = {
      val probe = TestProbe()
      ParallelCalculatorStreams.exprToStream(e).to(Sink.actorRef(probe.ref, "completed")).run()
      probe.expectMsg(1.second, res)
    }
    streamTest(Sum(Mul(Num(2), Num(3)), Div(Num(5), Num(5))), 7)
    streamTest(Mul(Mul(Mul(Num(2), Num(2)), Num(2)), Num(2)), 16)
    streamTest(Div(Num(1), Num(0)), Double.PositiveInfinity)
  }
}
