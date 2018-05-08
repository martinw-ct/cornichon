package com.github.agourlay.cornichon.json

import com.github.agourlay.cornichon.core._
import com.github.agourlay.cornichon.json.JsonSteps.JsonStepBuilder
import com.github.agourlay.cornichon.matchers.MatcherResolver
import com.github.agourlay.cornichon.steps.StepUtilSpec
import io.circe.{ Json, JsonObject }
import io.circe.testing.ArbitraryInstances
import org.scalatest.prop.PropertyChecks
import org.scalatest.{ AsyncWordSpec, Matchers, OptionValues }

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class JsonStepsSpec extends AsyncWordSpec
  with PropertyChecks
  with ArbitraryInstances
  with Matchers
  with OptionValues
  with StepUtilSpec {

  private val matcherResolver = MatcherResolver()
  private val testKey = "test-key"
  private val jsonStepBuilder = JsonStepBuilder(resolver, matcherResolver, SessionKey(testKey), Some("test body"))

  "JsonStep" when {
    "JsonStepBuilder" must {
      "is value" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> "test")
        val step = jsonStepBuilder.is("test")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "is value fail" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> "test")
        val step = jsonStepBuilder.isNot("test")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(false)
        }
      }

      "is malformed Json fail" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> "test")
        val step = jsonStepBuilder.is("{test:")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(false)
        }
      }
      "isNot value" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> "test")
        val step = jsonStepBuilder.isNot("test1")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "isNot value fail" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> "test")
        val step = jsonStepBuilder.isNot("test")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(false)
        }
      }

      "is path to json key" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """{ "myKey" : "myValue" }""")
        val step = jsonStepBuilder.path("myKey").is("myValue")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "is with absent path to json key" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """{ "myKey" : "myValue" }""")
        val step = jsonStepBuilder.path("myKey1").is("myValue")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(false)
        }
      }

      "is json with ignore" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """{ "myKey" : "myValue", "myKeyOther" : "myOtherValue" }""")
        val step = jsonStepBuilder.ignoring("myKey").is("""{ "myKeyOther" : "myOtherValue" }""")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "is json with ignore provided in expected anyway" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """{ "myKey" : "myValue", "myKeyOther" : "myOtherValue" }""")
        val step = jsonStepBuilder.ignoring("myKey").is("""{ "myKey" : "myValue", "myKeyOther" : "myOtherValue" }""")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "is json ignoring absent key does not fail" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """{ "myKey" : "myValue", "myKeyOther" : "myOtherValue" }""")
        val step = jsonStepBuilder.ignoring("myKey", "myKey1").is("""{ "myKeyOther" : "myOtherValue" }""")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "is json with whitelisting" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """{ "myKey" : "myValue", "myKeyOther" : "myOtherValue" }""")
        val step = jsonStepBuilder.whitelisting.is("""{ "myKeyOther" : "myOtherValue" }""")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "is json with matcher" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """{ "myKey" : "myValue", "myKeyOther" : "myOtherValue" }""")
        val step = jsonStepBuilder.whitelisting.is("""{ "myKeyOther" : *any-string* }""")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "is any Circe jsonObject" in {
        forAll { jsonOb: JsonObject ⇒
          val json = Json.fromJsonObject(jsonOb)
          val session = Session.newEmpty.addValuesUnsafe(testKey -> json.spaces2)
          val step = jsonStepBuilder.is(json)
          val s = Scenario("scenario with JsonSteps", step :: Nil)
          val t = engine.runScenario(session)(s).map { r ⇒
            withClue(LogInstruction.renderLogs(r.logs)) {
              r.isSuccess should be(true)
            }
          }
          Await.result(t, Duration.Inf)
        }
      }

      "is any Circe jsonObject with placeholder" in {
        forAll { jsonOb: JsonObject ⇒
          val fullJsonObj = jsonOb.add("myKeyOther", Json.fromString("myOtherValue"))
          val session = Session.newEmpty.addValuesUnsafe(
            testKey -> Json.fromJsonObject(fullJsonObj).spaces2,
            "a-placeholder" -> "myOtherValue"
          )
          val fullPlaceholderJsonObj = jsonOb.add("myKeyOther", Json.fromString("<a-placeholder>"))
          val step = jsonStepBuilder.is(Json.fromJsonObject(fullPlaceholderJsonObj))
          val s = Scenario("scenario with JsonSteps", step :: Nil)
          val t = engine.runScenario(session)(s).map { r ⇒
            withClue(LogInstruction.renderLogs(r.logs)) {
              r.isSuccess should be(true)
            }
          }
          Await.result(t, Duration.Inf)
        }
      }

      "is Circe jsonObject with absent placeholder" in {
        forAll { jsonOb: JsonObject ⇒
          val fullJsonObj = jsonOb.add("myKeyOther", Json.fromString("myOtherValue"))
          val session = Session.newEmpty.addValuesUnsafe(testKey -> Json.fromJsonObject(fullJsonObj).spaces2)
          val fullPlaceholderJsonObj = jsonOb.add("myKeyOther", Json.fromString("<a-placeholder>"))
          val step = jsonStepBuilder.is(Json.fromJsonObject(fullPlaceholderJsonObj))
          val s = Scenario("scenario with JsonSteps", step :: Nil)
          val t = engine.runScenario(session)(s).map { r ⇒
            withClue(LogInstruction.renderLogs(r.logs)) {
              r.isSuccess should be(false)
            }
          }
          Await.result(t, Duration.Inf)
        }
      }

      "is case class asJson with placeholder" in {
        import io.circe.generic.auto._
        import io.circe.syntax._
        case class MyObj(myKey: String, myKeyOther: String)

        val jsonString = """{ "myKey" : "myValue", "myKeyOther" : "myOtherValue" }"""
        val session = Session.newEmpty.addValuesUnsafe(testKey -> jsonString, "a-placeholder" -> "myOtherValue")

        val instance = MyObj("myValue", "<a-placeholder>")

        val step = jsonStepBuilder.is(instance.asJson)
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }
    }

    "JsonArrayStepBuilder" must {
      "is not an array fail" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> "test")
        val step = jsonStepBuilder.asArray.is("test")
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(false)
        }
      }

      "is array" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """["a", "b", "c" ]""")
        val step = jsonStepBuilder.asArray.is("""["a", "b". "c" ]""")
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(false)
        }
      }

      "has array size" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """["a", "b", "c" ]""")
        val step = jsonStepBuilder.asArray.hasSize(3)
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "not empty array" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """["a", "b", "c" ]""")
        val step = jsonStepBuilder.asArray.isNotEmpty
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "not empty array fail" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """[]""")
        val step = jsonStepBuilder.asArray.isNotEmpty
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(false)
        }
      }

      "is empty array" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """[]""")
        val step = jsonStepBuilder.asArray.isEmpty
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "is empty array fail" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """["a", "b", "c" ]""")
        val step = jsonStepBuilder.asArray.isEmpty
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(false)
        }
      }

      "has array size fail" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """["a", "b", "c" ]""")
        val step = jsonStepBuilder.asArray.hasSize(2)
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(false)
        }
      }

      "is not ordered by default" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """["a", "b", "c" ]""")
        val step = jsonStepBuilder.asArray.is("""["a", "c", "b" ]""")
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "is in order by default" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """["a", "b", "c" ]""")
        val step = jsonStepBuilder.asArray.inOrder.is("""["a", "c", "b" ]""")
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(false)
        }
      }

      "contains" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """["a", "b", "c" ]""")
        val step = jsonStepBuilder.asArray.contains("b")
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "contains fail" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """["a", "b", "c" ]""")
        val step = jsonStepBuilder.asArray.contains("d")
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(false)
        }
      }

      "not contains fail" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """["a", "b", "c" ]""")
        val step = jsonStepBuilder.asArray.not_contains("d")
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "not contains" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """["a", "b", "c" ]""")
        val step = jsonStepBuilder.asArray.not_contains("d")
        val s = Scenario("scenario with JsonArraySteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "is json with ignoreEach" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """[{ "myKey" : "myValue", "myKeyOther" : "myOtherValue" }]""")
        val step = jsonStepBuilder.asArray.ignoringEach("myKey").is("""[{ "myKeyOther" : "myOtherValue" }]""")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }

      "is json with ignoreEach provided in expected anyway" in {
        val session = Session.newEmpty.addValuesUnsafe(testKey -> """[{ "myKey" : "myValue", "myKeyOther" : "myOtherValue" }]""")
        val step = jsonStepBuilder.asArray.ignoringEach("myKey").is("""[{ "myKey" : "myValue", "myKeyOther" : "myOtherValue" }]""")
        val s = Scenario("scenario with JsonSteps", step :: Nil)
        engine.runScenario(session)(s).map { r ⇒
          r.isSuccess should be(true)
        }
      }
    }
  }
}