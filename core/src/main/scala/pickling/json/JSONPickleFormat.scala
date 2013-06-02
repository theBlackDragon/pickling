package scala.pickling

import scala.language.implicitConversions

package object json {
  implicit val pickleFormat: JSONPickleFormat = new JSONPickleFormat
  implicit def toJSONPickle(value: String): JSONPickle = JSONPickle(value)
}

package json {
  import scala.reflect.runtime.universe._
  import definitions._
  import scala.util.parsing.json._
  import scala.collection.mutable.{StringBuilder, Stack}

  case class JSONPickle(value: String) extends Pickle {
    type ValueType = String
    type PickleFormatType = JSONPickleFormat
  }

  class JSONPickleFormat extends PickleFormat {
    type PickleType = JSONPickle
    def createBuilder() = new JSONPickleBuilder(this)
    def createReader(pickle: JSONPickle, mirror: Mirror) = {
      JSON.parseRaw(pickle.value) match {
        case Some(raw) => new JSONPickleReader(raw, mirror, this)
        case None => throw new PicklingException("failed to parse \"" + pickle.value + "\" as JSON")
      }
    }
  }

  class JSONPickleBuilder(format: JSONPickleFormat) extends PickleBuilder with PickleTools {
    private val buf = new StringBuilder()
    private var nindent = 0
    private def indent() = nindent += 1
    private def unindent() = nindent -= 1
    private var pendingIndent = false
    private def append(s: String) = {
      val sindent = if (pendingIndent) "  " * nindent else ""
      buf ++= (sindent + s)
      pendingIndent = false
    }
    private def appendLine(s: String = "") = {
      append(s + "\n")
      pendingIndent = true
    }
    private val tags = new Stack[FastTypeTag[_]]()
    private def pickleArray(arr: Array[_], tag: FastTypeTag[_]) = {
      unindent()
      appendLine("[")
      hintStaticallyElidedType()
      hintTag(tag)
      pinHints()
      var i = 0
      while (i < arr.length) {
        putElement(b => b.beginEntry(arr(i)).endEntry())
        i += 1
      }
      unpinHints()
      appendLine("")
      append("]")
    }
    private val primitives = Map[String, Any => Unit](
      FastTypeTag.Null.key -> ((picklee: Any) => append("null")),
      FastTypeTag.Int.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Long.key -> ((picklee: Any) => append("\"" + JSONFormat.quoteString(picklee.toString) + "\"")),
      FastTypeTag.Short.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Double.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Float.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Boolean.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Byte.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Char.key -> ((picklee: Any) => append("\"" + JSONFormat.quoteString(picklee.toString) + "\"")),
      FastTypeTag.ScalaString.key -> ((picklee: Any) => append("\"" + JSONFormat.quoteString(picklee.toString) + "\"")),
      FastTypeTag.JavaString.key -> ((picklee: Any) => append("\"" + JSONFormat.quoteString(picklee.toString) + "\"")),
      FastTypeTag.ArrayInt.key -> ((picklee: Any) => pickleArray(picklee.asInstanceOf[Array[Int]], FastTypeTag.Int))
    )
    def beginEntry(picklee: Any): this.type = withHints { hints =>
      indent()
      tags.push(hints.tag)
      if (primitives.contains(hints.tag.key)) {
        if (hints.isElidedType) primitives(hints.tag.key)(picklee)
        else {
          appendLine("{")
          appendLine("\"tpe\": \"" + typeToString(hints.tag.tpe) + "\",")
          append("\"value\": ")
          indent()
          primitives(hints.tag.key)(picklee)
          unindent()
          appendLine("")
          unindent()
          append("}")
          indent()
        }
      } else {
        appendLine("{")
        if (!hints.isElidedType) append("\"tpe\": \"" + typeToString(hints.tag.tpe) + "\"")
      }
      this
    }
    def putField(name: String, pickler: this.type => Unit): this.type = {
      // assert(!primitives.contains(tags.top.key), tags.top)
      if (buf.toString.trim.last != '{') appendLine(",") // TODO: very inefficient, but here we don't care much about performance
      append("\"" + name + "\": ")
      pickler(this)
      this
    }
    def endEntry(): Unit = {
      unindent()
      if (primitives.contains(tags.pop().key)) () // do nothing
      else { appendLine(); append("}") }
    }
    def beginCollection(length: Int): this.type = {
      putField("elems", b => ())
      appendLine("[")
      // indent()
      this
    }
    def putElement(pickler: this.type => Unit): this.type = {
      if (buf.toString.trim.last != '[') appendLine(",") // TODO: very inefficient, but here we don't care much about performance
      pickler(this)
      this
    }
    def endCollection(l: Int): Unit = {
      appendLine()
      append("]")
      // unindent()
    }
    def result(): JSONPickle = {
      assert(tags.isEmpty, tags)
      JSONPickle(buf.toString)
    }
  }

  class JSONPickleReader(var datum: Any, val mirror: Mirror, format: JSONPickleFormat) extends PickleReader with PickleTools {
    private var lastReadTag: FastTypeTag[_] = null
    private val primitives = Map[String, () => Any](
      FastTypeTag.Null.key -> (() => null),
      FastTypeTag.Int.key -> (() => datum.asInstanceOf[Double].toInt),
      FastTypeTag.Short.key -> (() => datum.asInstanceOf[Double].toShort),
      FastTypeTag.Double.key -> (() => datum.asInstanceOf[Double]),
      FastTypeTag.Float.key -> (() => datum.asInstanceOf[Double].toFloat),
      FastTypeTag.Long.key -> (() => datum.asInstanceOf[String].toLong),
      FastTypeTag.Byte.key -> (() => datum.asInstanceOf[Double].toByte),
      FastTypeTag.Boolean.key -> (() => datum.asInstanceOf[Boolean]),
      FastTypeTag.Char.key -> (() => datum.asInstanceOf[String].head),
      FastTypeTag.ScalaString.key -> (() => datum.asInstanceOf[String]),
      FastTypeTag.JavaString.key -> (() => datum.asInstanceOf[String]),
      FastTypeTag.ArrayInt.key -> (() => datum.asInstanceOf[JSONArray].list.map(el => el.asInstanceOf[Double].toInt).toArray)
    )
    private def mkNestedReader(datum: Any) = {
      val nested = new JSONPickleReader(datum, mirror, format)
      if (this.areHintsPinned) {
        nested.areHintsPinned = true
        nested.hints = hints
        nested.lastReadTag = lastReadTag
      }
      nested
    }
    def beginEntryNoTag(): String = beginEntry().key
    def beginEntry(): FastTypeTag[_] = withHints { hints =>
      lastReadTag = {
        if (datum == null) FastTypeTag.Null
        else if (hints.isElidedType) hints.tag
        else {
          datum match {
            case JSONObject(fields) if fields.contains("tpe") => FastTypeTag(mirror, fields("tpe").asInstanceOf[String])
            case JSONObject(fields) => hints.tag
          }
        }
      }
      lastReadTag
    }
    def atPrimitive: Boolean = primitives.contains(lastReadTag.key)
    def readPrimitive(): Any = {
      datum match {
        case JSONArray(list) if lastReadTag.key != FastTypeTag.ArrayInt.key =>
          // now this is a hack!
          val value = mkNestedReader(list.head).primitives(lastReadTag.key)()
          datum = JSONArray(list.tail)
          value
        case JSONObject(fields) =>
          mkNestedReader(fields("value")).primitives(lastReadTag.key)()
        case _ =>
          primitives(lastReadTag.key)()
      }
    }
    def atObject: Boolean = datum.isInstanceOf[JSONObject]
    def readField(name: String): JSONPickleReader = {
      datum match {
        case JSONObject(fields) => mkNestedReader(fields(name))
      }
    }
    def endEntry(): Unit = {}
    def beginCollection(): PickleReader = readField("elems")
    def readLength(): Int = {
      datum match {
        case JSONArray(list) => list.length
      }
    }
    private var i = 0
    def readElement(): PickleReader = {
      val reader = {
        datum match {
          case JSONArray(list) => mkNestedReader(list(i))
        }
      }
      i += 1
      reader
    }
    def endCollection(): Unit = {}
  }
}