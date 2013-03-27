package org.anormcypher

import dispatch.{host => h, _}
import play.api.libs.json._
import play.api.libs.json.Json._
import scala.language.implicitConversions
import scala.util.Try

sealed trait Neo4j {
  def sendQuery(cypherStatement: CypherStatement): concurrent.Future[Stream[CypherResultRow]]
}

trait Neo4jRESTConfig {
  def host: String = "localhost"

  def port: Int = 7474

  def db: String = "db/data"

  def username: String = ""

  def password: String = ""
}

trait Neo4jREST extends Neo4j {
  self: Neo4jRESTConfig =>

  trait EnhancedUrlVerbs extends RequestVerbs {
    def ^/(segment: String) = {
      val uri = RawUri(subject.build.getUrl)
      val cleanSegment = if (segment.startsWith("/")) segment.substring(1) else segment
      val rawPath = uri.path.orElse(Some("/")).map {
        case u if u.endsWith("/") => u concat cleanSegment
        case u => s"$u/$cleanSegment"
      }
      subject.setUrl(uri.copy(path = rawPath).toString())
    }
  }

  class EnhancedRequestVerbs(val subject: com.ning.http.client.RequestBuilder) extends EnhancedUrlVerbs

  private val headers = Map(
    "Accept" -> "application/json",
    "Content-Type" -> "application/json",
    "X-Stream" -> "true",
    "User-Agent" -> "AnormCypher/0.5.0"
  )

  val http = Http.configure {
    builder => builder.setCompressionEnabled(true)
      .setAllowPoolingConnection(true)
      .setRequestTimeoutInMs(5000)
      .setMaximumConnectionsTotal(23)
  }

  implicit def requestBuilder2EnhancedRequestVerbs(rb: com.ning.http.client.RequestBuilder) =
    new EnhancedRequestVerbs(rb)

  val cypherRequest = (h(host, port) ^/ db) / "cypher" <:< headers as_!(username, password)

  implicit val mapFormat = new Format[Map[String, Any]] {
    def read(xs: Seq[(String, JsValue)]): Map[String, Any] = (xs map {
      case (k, JsBoolean(b)) => k -> b
      case (k, JsNumber(n)) => k -> n
      case (k, JsString(s)) => k -> s
      case (k, JsArray(bs)) if (bs.forall(_.isInstanceOf[JsBoolean])) =>
        k -> bs.asInstanceOf[Seq[JsBoolean]].map(_.value)
      case (k, JsArray(ns)) if (ns.forall(_.isInstanceOf[JsNumber])) =>
        k -> ns.asInstanceOf[Seq[JsNumber]].map(_.value)
      case (k, JsArray(ss)) if (ss.forall(_.isInstanceOf[JsString])) =>
        k -> ss.asInstanceOf[Seq[JsString]].map(_.value)
      case (k, JsObject(o)) => k -> read(o)
      case x => throw new RuntimeException(s"unsupported type $x")
    }).toMap

    def reads(json: JsValue) = json match {
      case JsObject(xs) => Try(read(xs)).map(JsSuccess(_)).recover {
        case e: RuntimeException => JsError(e.getLocalizedMessage)
      }.get
      case x => JsError(s"json not of type Map[String, Any]: $x")
    }

    def writes(map: Map[String, Any]) =
      Json.obj(map.map {
        case (key, value) => {
          val ret: (String, JsValueWrapper) = value match {
            case b: Boolean => key -> JsBoolean(b)
            case b: Byte => key -> JsNumber(b)
            case s: Short => key -> JsNumber(s)
            case i: Int => key -> JsNumber(i)
            case l: Long => key -> JsNumber(l)
            case f: Float => key -> JsNumber(f)
            case d: Double => key -> JsNumber(d)
            case c: Char => key -> JsNumber(c)
            case s: String => key -> JsString(s)
            case bs: Seq[_] if (bs.forall(_.isInstanceOf[Boolean])) =>
              key -> JsArray(bs.map(b => JsBoolean(b.asInstanceOf[Boolean])))
            case bs: Seq[_] if (bs.forall(_.isInstanceOf[Byte])) =>
              key -> JsArray(bs.map(b => JsNumber(b.asInstanceOf[Byte])))
            case ss: Seq[_] if (ss.forall(_.isInstanceOf[Short])) =>
              key -> JsArray(ss.map(s => JsNumber(s.asInstanceOf[Short])))
            case is: Seq[_] if (is.forall(_.isInstanceOf[Int])) =>
              key -> JsArray(is.map(i => JsNumber(i.asInstanceOf[Int])))
            case ls: Seq[_] if (ls.forall(_.isInstanceOf[Long])) =>
              key -> JsArray(ls.map(l => JsNumber(l.asInstanceOf[Long])))
            case fs: Seq[_] if (fs.forall(_.isInstanceOf[Float])) =>
              key -> JsArray(fs.map(f => JsNumber(f.asInstanceOf[Float])))
            case ds: Seq[_] if (ds.forall(_.isInstanceOf[Double])) =>
              key -> JsArray(ds.map(d => JsNumber(d.asInstanceOf[Double])))
            case cs: Seq[_] if (cs.forall(_.isInstanceOf[Char])) =>
              key -> JsArray(cs.map(c => JsNumber(c.asInstanceOf[Char])))
            case ss: Seq[_] if (ss.forall(_.isInstanceOf[String])) =>
              key -> JsArray(ss.map(s => JsString(s.asInstanceOf[String])))
            case sam: Map[_, _] if (sam.keys.forall(_.isInstanceOf[String])) =>
              key -> writes(sam.asInstanceOf[Map[String, Any]])
            case xs: Seq[_] => throw new RuntimeException(s"unsupported Neo4j array type: $xs (mixed types?)")
            case x => throw new RuntimeException(s"unsupported Neo4j type: $x")
          }
          ret
        }
      }.toSeq: _*)
  }

  implicit val cypherStatementWrites = Json.writes[CypherStatement]

  implicit val seqReads = new Reads[Seq[Any]] {
    def read(xs: Seq[JsValue]): Seq[Any] = xs map {
      case JsBoolean(b) => b
      case JsNumber(n) => n
      case JsString(s) => s
      case JsArray(arr) => read(arr)
      case JsNull => null
      case o: JsObject => o.as[Map[String, Any]]
      case x => throw new RuntimeException(s"unsupported type $x")
    }

    def reads(json: JsValue) = json match {
      case JsArray(xs) => Try(read(xs)).map(JsSuccess(_)).recover {
        case e: RuntimeException => JsError(e.getLocalizedMessage)
      }.get
      case _ => JsError("json not of type Seq[Any]")
    }
  }

  implicit val cypherRESTResultReads = Json.reads[CypherRESTResult]

  def sendQuery(cypherStatement: CypherStatement): concurrent.Future[Stream[CypherResultRow]] = {
    val p = concurrent.Promise[Stream[CypherResultRow]]()
    http {
      (cypherRequest << Json.prettyPrint(Json.toJson(cypherStatement))) OK as.String
    } onComplete {
      either => either.fold(
        throwable => p failure throwable,
        res => Json.fromJson[CypherRESTResult](Json.parse(res)).fold(
          errors => p failure new RuntimeException(s"unable to parse Neo4j's JSON response"),
          cypherResult => {
            val metadata = MetaData(cypherResult.columns.map(MetaDataItem(_, false, "String")).toList)
            p complete Try {
              cypherResult.data.map(d => CypherResultRow(metadata, d.toList)).toStream
            }
          }
        )
      )
    }
    p.future
  }
}

object RestHelper {

  object IdURLExtractor {
    def unapply(s: String) = s.lastIndexOf('/') match {
      case pos if pos >= 0 => Some(s.substring(pos + 1).toLong)
      case _ => None
    }
  }

  def asNode(msa: Map[String, Any]): MayErr[CypherRequestError, NeoNode] = (msa.get("self"), msa.get("data")) match {
    case (Some(IdURLExtractor(id)), Some(props: Map[_, _])) if props.keys.forall(_.isInstanceOf[String]) =>
      Right(NeoNode(id, props.asInstanceOf[Map[String, Any]]))
    case x => Left(TypeDoesNotMatch("Unexpected type while building a Node"))
  }

  def asRelationship(msa: Map[String, Any]): MayErr[CypherRequestError, NeoRelationship] =
    (msa.get("self"), msa.get("start"), msa.get("end"), msa.get("data")) match {
      case (Some(IdURLExtractor(id)), Some(IdURLExtractor(sId)), Some(IdURLExtractor(eId)), Some(props: Map[_, _]))
        if props.keys.forall(_.isInstanceOf[String]) =>
        Right(NeoRelationship(id, props.asInstanceOf[Map[String, Any]], sId, eId))
      case _ => Left(TypeDoesNotMatch("Unexpected type while building a relationship"))
    }
}

case class CypherRESTResult(columns: Vector[String], data: Seq[Seq[Any]])

case class NeoNode(id: Long, props: Map[String, Any])

case class NeoRelationship(id: Long, props: Map[String, Any], start: Long, end: Long)