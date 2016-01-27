package com.github.agourlay.cornichon.http

import com.github.agourlay.cornichon.CornichonFeature
import com.github.agourlay.cornichon.core.RunnableStep._
import com.github.agourlay.cornichon.core._
import com.github.agourlay.cornichon.dsl.{ BodyElementCollector, Dsl }
import com.github.agourlay.cornichon.http.HttpDslErrors._
import com.github.agourlay.cornichon.json.CornichonJson._
import com.github.agourlay.cornichon.json.{ JsonPath, NotAnArrayError, WhiteListError }
import org.json4s._

import scala.concurrent.duration._

trait HttpDsl extends Dsl {
  this: CornichonFeature ⇒

  import HttpService._

  sealed trait Request {
    val name: String
  }

  sealed trait WithoutPayload extends Request {
    def apply(url: String, params: (String, String)*)(implicit headers: Seq[(String, String)] = Seq.empty) =
      effectful(
        title = {
        val base = s"$name $url"
        if (params.isEmpty) base
        else s"$base with params ${displayTuples(params)}"
      },
        effect =
        s ⇒ this match {
          case GET    ⇒ http.Get(url, params, headers)(s)
          case DELETE ⇒ http.Delete(url, params, headers)(s)
        }
      )
  }

  sealed trait WithPayload extends Request {
    def apply(url: String, payload: String, params: (String, String)*)(implicit headers: Seq[(String, String)] = Seq.empty) =
      effectful(
        title = {
        val base = s"$name to $url with payload $payload"
        if (params.isEmpty) base
        else s"$base with params ${displayTuples(params)}"
      },
        effect =
        s ⇒ this match {
          case POST ⇒ http.Post(url, payload, params, headers)(s)
          case PUT  ⇒ http.Put(url, payload, params, headers)(s)
        }
      )
  }

  sealed trait Streamed extends Request {
    def apply(url: String, takeWithin: FiniteDuration, params: (String, String)*)(implicit headers: Seq[(String, String)] = Seq.empty) =
      effectful(
        title = {
        val base = s"$name $url"
        if (params.isEmpty) base
        else s"$base with params ${displayTuples(params)}"
      },
        effect =
        s ⇒ this match {
          case GET_SSE ⇒ http.GetSSE(url, takeWithin, params, headers)(s)
          case GET_WS  ⇒ http.GetWS(url, takeWithin, params, headers)(s)
        }
      )
  }

  case object GET extends WithoutPayload {
    val name = "GET"
  }

  case object DELETE extends WithoutPayload {
    val name = "DELETE"
  }

  case object POST extends WithPayload {
    val name = "POST"
  }

  case object PUT extends WithPayload {
    val name = "PUT"
  }

  case object GET_SSE extends Streamed {
    val name = "GET SSE"
  }

  case object GET_WS extends Streamed {
    val name = "GET WS"
  }

  val root = JsonPath.root

  def status(status: Int) =
    RunnableStep(
      title = s"status is '$status'",
      action = s ⇒ {
      (s, DetailedStepAssertion(
        expected = status.toString,
        result = s.get(LastResponseStatusKey),
        details = statusError(status, s.get(LastResponseBodyKey))
      ))
    }
    )

  def headers_contain(headers: (String, String)*) =
    from_session_step(
      key = LastResponseHeadersKey,
      expected = s ⇒ true,
      (session, sessionHeaders) ⇒ {
        val sessionHeadersValue = sessionHeaders.split(",")
        headers.forall { case (name, value) ⇒ sessionHeadersValue.contains(s"$name$HeadersKeyValueDelim$value") }
      }, title = s"headers contain ${headers.mkString(", ")}"
    )

  def body[A](jsonPath: JsonPath, expected: A, ignoring: JsonPath*): RunnableStep[JValue] =
    from_session_step(
      key = LastResponseBodyKey,
      expected = s ⇒ resolveAndParse(expected, s),
      (s, sessionValue) ⇒ {
        val mapped = selectJsonPath(jsonPath, sessionValue)
        if (ignoring.isEmpty) mapped
        else removeFieldsByPath(mapped, ignoring)
      },
      title = if (jsonPath.isRoot) s"response body is '$expected'" else s"response body's field '${jsonPath.pretty}' is '$expected'"
    )

  //Duplication has overloading with the one above fails
  def body[A](expected: A, ignoring: JsonPath*) =
    from_session_step(
      key = LastResponseBodyKey,
      title = titleBuilder(s"response body is '$expected'", ignoring),
      expected = s ⇒ resolveAndParse(expected, s),
      mapValue =
        (session, sessionValue) ⇒ {
          val jsonSessionValue = parseJson(sessionValue)
          if (ignoring.isEmpty) jsonSessionValue
          else removeFieldsByPath(jsonSessionValue, ignoring)
        }
    )

  def body(whiteList: Boolean = false, expected: String): RunnableStep[JValue] = {
    from_session_step(
      key = LastResponseBodyKey,
      title = s"response body is '$expected' with whiteList=$whiteList",
      expected = s ⇒ resolveAndParse(expected, s),
      mapValue =
      (session, sessionValue) ⇒ {
        val expectedJson = resolveAndParse(expected, session)
        val sessionValueJson = parseJson(sessionValue)
        if (whiteList) {
          val Diff(changed, _, deleted) = expectedJson.diff(sessionValueJson)
          if (deleted != JNothing) throw new WhiteListError(s"White list error - '$deleted' is not defined in object '$sessionValueJson")
          if (changed != JNothing) changed else expectedJson
        } else sessionValueJson
      }
    )
  }

  def body[A](ordered: Boolean, expected: A, ignoring: JsonPath*): RunnableStep[Iterable[JValue]] =
    if (ordered)
      body_array_transform(_.arr.map(removeFieldsByPath(_, ignoring)), titleBuilder(s"response body is '$expected'", ignoring), s ⇒ {
        resolveAndParse(expected, s) match {
          case expectedArray: JArray ⇒ expectedArray.arr
          case _                     ⇒ throw new NotAnArrayError(expected)
        }
      })
    else
      body_array_transform(s ⇒ s.arr.map(removeFieldsByPath(_, ignoring)).toSet, titleBuilder(s"response body array not ordered is '$expected'", ignoring), s ⇒ {
        resolveAndParse(expected, s) match {
          case expectedArray: JArray ⇒ expectedArray.arr.toSet
          case _                     ⇒ throw new NotAnArrayError(expected)
        }
      })

  def save_body_key(args: (String, String)*) = {
    val inputs = args.map {
      case (key, t) ⇒ FromSessionSetter(LastResponseBodyKey, s ⇒ (parseJson(s) \ key).values.toString, t)
    }
    save_from_session(inputs)
  }

  def save_body_path(args: (JsonPath, String)*) = {
    val inputs = args.map {
      case (k, t) ⇒ FromSessionSetter(LastResponseBodyKey, s ⇒ selectJsonPath(k, s).values.toString, t)
    }
    save_from_session(inputs)
  }

  def show_last_status = show_session(LastResponseStatusKey)

  def show_last_response_body = show_session(LastResponseBodyKey)

  def show_last_response_body_as_json = show_key_as_json(LastResponseBodyKey)

  def show_last_response_headers = show_session(LastResponseHeadersKey)

  def show_key_as_json(key: String) = show_session(key, v ⇒ prettyPrint(parseJson(v)))

  private def titleBuilder(baseTitle: String, ignoring: Seq[JsonPath]): String =
    if (ignoring.isEmpty) baseTitle
    else s"$baseTitle ignoring keys ${ignoring.map(v ⇒ s"'${v.pretty}'").mkString(", ")}"

  def body_array_transform[A](mapFct: JArray ⇒ A, title: String, expected: Session ⇒ A): RunnableStep[A] =
    from_session_step[A](
      title = title,
      key = LastResponseBodyKey,
      expected = s ⇒ expected(s),
      mapValue =
      (session, sessionValue) ⇒ {
        val jarr = parseArray(sessionValue)
        mapFct(jarr)
      }
    )

  def body_array_size(size: Int): RunnableStep[Int] = body_array_size(root, size)

  def body_array_size(jsonPath: JsonPath, size: Int) = {
    val title = if (jsonPath.isRoot) s"response body array size is '$size'" else s"response body's array '${jsonPath.pretty}' size is '$size'"
    from_session_detail_step(
      title = title,
      key = LastResponseBodyKey,
      expected = s ⇒ size,
      mapValue = (s, sessionValue) ⇒ {
      val jarr = if (jsonPath.isRoot) parseArray(sessionValue)
      else selectArrayJsonPath(jsonPath, sessionValue)
      (jarr.arr.size, arraySizeError(size, prettyPrint(jarr)))
    }
    )
  }

  def body_array_contains[A](element: A): RunnableStep[Boolean] = body_array_contains(root, element)

  def body_array_contains[A](jsonPath: JsonPath, element: A) = {
    val title = if (jsonPath.isRoot) s"response body array contains '$element'" else s"response body's array '${jsonPath.pretty}' contains '$element'"
    from_session_detail_step(
      title = title,
      key = LastResponseBodyKey,
      expected = s ⇒ true,
      mapValue = (s, sessionValue) ⇒ {
      val jarr = if (jsonPath.isRoot) parseArray(sessionValue)
      else selectArrayJsonPath(jsonPath, sessionValue)
      (jarr.arr.contains(parseJson(element)), arrayDoesNotContainError(element.toString, prettyPrint(jarr)))
    }
    )
  }

  def WithHeaders(headers: (String, String)*) =
    BodyElementCollector[Step, Seq[Step]] { steps ⇒
      val saveStep = save(WithHeadersKey, headers.map { case (name, value) ⇒ s"$name$HeadersKeyValueDelim$value" }.mkString(",")).copy(show = false)
      val removeStep = remove(WithHeadersKey).copy(show = false)

      saveStep +: steps :+ removeStep
    }

  def json_equality_for(k1: String, k2: String, ignoring: JsonPath*) = RunnableStep(
    title = titleBuilder(s"JSON content of key '$k1' is equal to JSON content of key '$k2'", ignoring),
    action = s ⇒ {
      val v1 = removeFieldsByPath(s.getJson(k1), ignoring)
      val v2 = removeFieldsByPath(s.getJson(k2), ignoring)
      (s, SimpleStepAssertion(v1, v2))
    }
  )

  private def resolveAndParse[A](input: A, session: Session): JValue =
    parseJsonUnsafe {
      input match {
        case string: String ⇒ resolver.fillPlaceholdersUnsafe(string)(session).asInstanceOf[A]
        case _              ⇒ input
      }
    }
}