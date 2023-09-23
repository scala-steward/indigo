package indigoplugin.generators

import scala.util.matching.Regex

// import scala.annotation.tailrec

object EmbedData {

  // Has a standard format, first row is headers, first column is keys.
  // Strings delimited with single or double quotes preserve the delimited
  // value, the quotes are dropped, but the other kind of quote within that
  // string is kept.
  // Cells cannot be empty.
  def generate(
      outDir: os.Path,
      moduleName: String,
      fullyQualifiedPackage: String,
      filePath: os.Path,
      delimiter: String,
      rowFilter: String => Boolean,
      asEnum: Boolean
  ): Seq[os.Path] = {

    val lines =
      if (!os.exists(filePath)) throw new Exception("Path to data file not found: " + filePath.toString())
      else {
        os.read.lines(filePath).filter(rowFilter)
      }

    val dataFrame =
      DataFrame.fromRows(
        lines.map(row => extractRowData(row, delimiter)).toList
      )

    val wd = outDir / Generators.OutputDirName

    os.makeDir.all(wd)

    val file = wd / s"$moduleName.scala"

    val contents =
      if (asEnum) {
        s"""package $fullyQualifiedPackage
        |
        |// DO NOT EDIT: Generated by Indigo.
        |${dataFrame.renderEnum(moduleName)}
        |""".stripMargin
      } else {
        s"""package $fullyQualifiedPackage
        |
        |// DO NOT EDIT: Generated by Indigo.
        |${dataFrame.renderMap(moduleName)}
        |""".stripMargin
      }

    os.write.over(file, contents)

    Seq(file)
  }

  def extractRows(rows: List[String], delimiter: String): List[List[DataType]] =
    rows.map(r => extractRowData(r, delimiter))

  def extractRowData(row: String, delimiter: String): List[DataType] =
    parse(delimiter)(row).map(_._1).collect {
      case d @ DataType.StringData(s) if s.nonEmpty => d
      case d: DataType.BooleanData                  => d
      case d: DataType.DoubleData                   => d
      case d: DataType.IntData                      => d
    }

  // A parser of things,
  // is a function from strings,
  // to a list of pairs
  // of things and strings.
  def parse(delimiter: String): String => List[(DataType, String)] = {
    val takeUpToDelimiter        = s"^(.*?)${delimiter}(.*)".r
    val takeMatchingSingleQuotes = s"^'(.*?)'${delimiter}(.*)".r
    val takeMatchingDoubleQuotes = s"""^\"(.*?)\"${delimiter}(.*)""".r

    (in: String) =>
      in match {
        case takeMatchingDoubleQuotes(take, left) =>
          List(DataType.decideType(take.trim) -> left) ++ parse(delimiter)(left.trim)

        case takeMatchingSingleQuotes(take, left) =>
          List(DataType.decideType(take.trim) -> left) ++ parse(delimiter)(left.trim)

        case takeUpToDelimiter(take, left) =>
          List(DataType.decideType(take.trim) -> left) ++ parse(delimiter)(left.trim)

        case take =>
          List(DataType.decideType(take.trim) -> "")
      }
  }
}

sealed trait DataType {

  def isString: Boolean =
    this match {
      case _: DataType.StringData => true
      case _                      => false
    }

  def isDouble: Boolean =
    this match {
      case _: DataType.DoubleData => true
      case _                      => false
    }

  def isInt: Boolean =
    this match {
      case _: DataType.IntData => true
      case _                   => false
    }

  def isBoolean: Boolean =
    this match {
      case _: DataType.BooleanData => true
      case _                       => false
    }

  def toStringData: DataType.StringData =
    this match {
      case s: DataType.StringData      => s
      case DataType.BooleanData(value) => DataType.StringData(value.toString)
      case DataType.DoubleData(value)  => DataType.StringData(value.toString)
      case DataType.IntData(value)     => DataType.StringData(value.toString)
    }

  def asString: String =
    this match {
      case s: DataType.StringData      => s""""${s.value}""""
      case DataType.BooleanData(value) => value.toString
      case DataType.DoubleData(value)  => value.toString
      case DataType.IntData(value)     => value.toString
    }

  def giveType: String =
    this match {
      case _: DataType.StringData  => "String"
      case _: DataType.BooleanData => "Boolean"
      case _: DataType.DoubleData  => "Double"
      case _: DataType.IntData     => "Int"
    }

}
object DataType {

  // Most to least specific: Boolean, Int, Double, String
  final case class BooleanData(value: Boolean) extends DataType
  final case class IntData(value: Int) extends DataType {
    def toDoubleData: DoubleData = DoubleData(value.toDouble)
  }
  final case class DoubleData(value: Double) extends DataType
  final case class StringData(value: String) extends DataType

  private val isBoolean: Regex = """^(true|false)$""".r
  private val isDouble: Regex  = """^([0-9]*?).([0-9]*)$""".r
  private val isInt: Regex     = """^([0-9]+)$""".r

  def decideType: String => DataType = {
    case isBoolean(v)     => BooleanData(v.toBoolean)
    case isInt(v)         => IntData(v.toInt)
    case isDouble(v1, v2) => DoubleData(s"$v1.$v2".toDouble)
    case v                => StringData(v)
  }

  def sameType(a: DataType, b: DataType): Boolean =
    (a, b) match {
      case (_: DataType.StringData, _: DataType.StringData)   => true
      case (_: DataType.BooleanData, _: DataType.BooleanData) => true
      case (_: DataType.DoubleData, _: DataType.DoubleData)   => true
      case (_: DataType.IntData, _: DataType.IntData)         => true
      case _                                                  => false
    }

  def allSameType(l: List[DataType]): Boolean =
    l match {
      case Nil    => true
      case h :: t => t.forall(d => sameType(h, d))
    }

  def allNumericTypes(l: List[DataType]): Boolean =
    l.forall(d => d.isDouble || d.isInt)

  def convertToBestType(l: List[DataType]): List[DataType] =
    // Cases we can manage:
    // - They're all the same!
    // - Doubles and Ints, convert Ints to Doubles
    // - Fallback is that everything is a string.
    if (allSameType(l)) {
      // All the same! Great!
      l
    } else if (allNumericTypes(l)) {
      l.map {
        case v @ DataType.DoubleData(_) => v
        case v @ DataType.IntData(_)    => v.toDoubleData
        case s => throw new Exception(s"Unexpected non-numeric type '$s'") // Shouldn't get here.
      }
    } else {
      // Nothing else to do, but make everything a string
      l.map(_.toStringData)
    }

}

final case class DataFrame(data: Array[Array[DataType]], columnCount: Int) {
  def headers: Array[DataType.StringData] =
    data.head.map(_.toStringData)

  def rows: Array[Array[DataType]] =
    data.tail

  def alignColumnTypes: DataFrame = {
    val transposed                  = rows.transpose
    val stringKeys: Array[DataType] = transposed.head.map(_.toStringData)
    val typeRows: Array[Array[DataType]] = transposed.tail
      .map(d => DataType.convertToBestType(d.toList).toArray)
    val cleanedRows: Array[Array[DataType]] =
      (stringKeys +: typeRows).transpose

    this.copy(
      data = headers.asInstanceOf[Array[DataType]] +: cleanedRows
    )
  }

  def toSafeName: String => String = { name: String =>
    name.replaceAll("[^a-zA-Z0-9]", "-").split("-").toList.filterNot(_.isEmpty) match {
      case h :: t if h.take(1).matches("[0-9]") => ("_" :: h :: t.map(_.capitalize)).mkString
      case l                                    => l.map(_.capitalize).mkString
    }
  }

  def toSafeNameCamel: String => String = { name: String =>
    name.replaceAll("[^a-zA-Z0-9]", "-").split("-").toList.filterNot(_.isEmpty) match {
      case h :: t if h.take(1).matches("[0-9]") => ("_" :: h :: t.map(_.capitalize)).mkString
      case h :: t                               => (h :: t.map(_.capitalize)).mkString
      case l                                    => l.map(_.capitalize).mkString
    }
  }

  def renderVars: String = {
    val names = headers.drop(1).map(_.value)
    val types = rows.head.drop(1).map(_.giveType)
    names.zip(types).map { case (n, t) => s"val ${toSafeNameCamel(n)}: $t" }.mkString(", ")
  }

  def renderEnum(moduleName: String): String = {
    val renderedRows =
      rows
        .map { r =>
          s"""  case ${toSafeName(r.head.asString)} extends $moduleName(${r.tail
            .map(_.asString)
            .mkString(", ")})"""
        }
        .mkString("\n")

    s"""
    |enum $moduleName(${renderVars}):
    |${renderedRows}
    |""".stripMargin
  }

  def renderMap(moduleName: String): String = {
    val renderedRows =
      rows
        .map { r =>
          s"""      ${r.head.asString} -> $moduleName(${r.tail.map(_.asString).mkString(", ")})"""
        }
        .mkString(",\n")

    s"""
    |final case class $moduleName(${renderVars})
    |object $moduleName:
    |  val data: Map[String, $moduleName] =
    |    Map(
    |${renderedRows}
    |    )
    |""".stripMargin
  }
}
object DataFrame {

  private val standardMessage: String =
    "Embedded data must have two rows (minimum) of the same length (two columns minimum). The first row is the headers / field names. The first column are the keys. Cells cannot be empty."

  def fromRows(rows: List[List[DataType]]): DataFrame =
    rows match {
      case Nil =>
        throw new Exception("No data to create. " + standardMessage)

      case _ :: Nil =>
        throw new Exception("Only one row of data found. " + standardMessage)

      case h :: t =>
        val len = h.length

        if (len == 0) {
          throw new Exception("No data to create. " + standardMessage)
        } else if (len == 1) {
          throw new Exception("Only one column of data. " + standardMessage)
        } else if (!t.forall(_.length == len)) {
          throw new Exception(s"All rows must be the same length. Header row had '$len' columns. " + standardMessage)
        } else {
          DataFrame(rows.map(_.toArray).toArray, len).alignColumnTypes
        }
    }

}
