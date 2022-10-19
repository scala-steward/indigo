package indigo.macroshaders

import scala.annotation.tailrec
import scala.quoted.*

sealed trait ShaderAST
object ShaderAST:

  given ToExpr[ShaderAST] with {
    def apply(x: ShaderAST)(using Quotes): Expr[ShaderAST] =
      x match
        case v: Empty        => Expr(v)
        case v: Block        => Expr(v)
        case v: NamedBlock   => Expr(v)
        case v: Function     => Expr(v)
        case v: CallFunction => Expr(v)
        case v: DataTypes    => Expr(v)
        case v: Val          => Expr(v)
  }

  final case class Empty() extends ShaderAST
  object Empty:
    given ToExpr[Empty] with {
      def apply(x: Empty)(using Quotes): Expr[Empty] =
        '{ Empty() }
    }

  final case class Block(statements: List[ShaderAST]) extends ShaderAST
  object Block:
    given ToExpr[Block] with {
      def apply(x: Block)(using Quotes): Expr[Block] =
        '{ Block(${ Expr(x.statements) }) }
    }

    def apply(statements: ShaderAST*): Block =
      Block(statements.toList)

  final case class NamedBlock(namespace: String, id: String, statements: List[ShaderAST]) extends ShaderAST
  object NamedBlock:
    given ToExpr[NamedBlock] with {
      def apply(x: NamedBlock)(using Quotes): Expr[NamedBlock] =
        '{ NamedBlock(${ Expr(x.namespace) }, ${ Expr(x.id) }, ${ Expr(x.statements) }) }
    }

    def apply(namespace: String, id: String, statements: ShaderAST*): NamedBlock =
      NamedBlock(namespace, id, statements.toList)

  final case class Function(id: String, args: List[String], body: ShaderAST) extends ShaderAST
  object Function:
    given ToExpr[Function] with {
      def apply(x: Function)(using Quotes): Expr[Function] =
        '{ Function(${ Expr(x.id) }, ${ Expr(x.args) }, ${ Expr(x.body) }) }
    }
  final case class CallFunction(id: String, args: List[String]) extends ShaderAST
  object CallFunction:
    given ToExpr[CallFunction] with {
      def apply(x: CallFunction)(using Quotes): Expr[CallFunction] =
        '{ CallFunction(${ Expr(x.id) }, ${ Expr(x.args) }) }
    }

  final case class Val(id: String, value: ShaderAST) extends ShaderAST
  object Val:
    given ToExpr[Val] with {
      def apply(x: Val)(using Quotes): Expr[Val] =
        '{ Val(${ Expr(x.id) }, ${ Expr(x.value) }) }
    }

  enum DataTypes extends ShaderAST:
    case closure(body: ShaderAST, typeOf: Option[String])
    case ident(id: String)
    case float(v: Float)
    case vec2(args: List[ShaderAST])
    case vec3(args: List[ShaderAST])
    case vec4(args: List[ShaderAST])

  object DataTypes:
    given ToExpr[DataTypes] with {
      def apply(x: DataTypes)(using Quotes): Expr[DataTypes] =
        x match
          case v: DataTypes.closure => Expr(v)
          case v: DataTypes.ident   => Expr(v)
          case v: DataTypes.float   => Expr(v)
          case v: DataTypes.vec2    => Expr(v)
          case v: DataTypes.vec3    => Expr(v)
          case v: DataTypes.vec4    => Expr(v)
    }
    given ToExpr[ident] with {
      def apply(x: ident)(using Quotes): Expr[ident] =
        '{ ident(${ Expr(x.id) }) }
    }
    given ToExpr[closure] with {
      def apply(x: closure)(using Quotes): Expr[closure] =
        '{ closure(${ Expr(x.body) }, ${ Expr(x.typeOf) }) }
    }
    given ToExpr[float] with {
      def apply(x: float)(using Quotes): Expr[float] =
        '{ float(${ Expr(x.v) }) }
    }
    given ToExpr[vec2] with {
      def apply(x: vec2)(using Quotes): Expr[vec2] =
        '{ vec2(${ Expr(x.args) }) }
    }
    given ToExpr[vec3] with {
      def apply(x: vec3)(using Quotes): Expr[vec3] =
        '{ vec3(${ Expr(x.args) }) }
    }
    given ToExpr[vec4] with {
      def apply(x: vec4)(using Quotes): Expr[vec4] =
        '{ vec4(${ Expr(x.args) }) }
    }

  extension (ast: ShaderAST)
    @SuppressWarnings(Array("scalafix:DisableSyntax.throw"))
    def render: String =
      def rf(f: Float): String =
        val s = f.toString
        if s.contains(".") then s else s + ".0"

      def decideType(a: ShaderAST): Option[String] =
        a match
          case DataTypes.float(v)   => Option("float")
          case DataTypes.vec2(args) => Option("vec2")
          case DataTypes.vec3(args) => Option("vec3")
          case DataTypes.vec4(args) => Option("vec4")
          case _                    => None

      @tailrec
      def crush(statements: ShaderAST): ShaderAST =
        statements match
          case Block(List(s))            => crush(s)
          case NamedBlock(_, _, List(s)) => crush(s)
          case other                     => other

      def processStatements(statements: List[ShaderAST]): String =
        statements.map(s => crush(s).render).filterNot(_.isEmpty).mkString("", ";", ";")

      def processFunctionStatements(statements: List[ShaderAST]): (String, String) =
        val nonEmpty = statements.map(crush).filterNot {
          case Empty() => true
          case _       => false
        }
        val (init, last) =
          if nonEmpty.length > 1 then (nonEmpty.dropRight(1), nonEmpty.takeRight(1))
          else (Nil, nonEmpty)
        println("ORIG: " + nonEmpty.toString())
        println("FRST: " + init.headOption.toString())
        println("LAST: " + last.toString())
        val returnType = last.headOption.flatMap(decideType).getOrElse("void")
        val body =
          init.map(_.render).filterNot(_.isEmpty).mkString("", ";", ";") +
            last.headOption
              .map(ss => (if returnType != "void" then "return " else "") + ss.render + ";")
              .getOrElse("")
        (body, returnType)

      ast match
        case Empty() =>
          ""

        case Block(List(Block(statements))) =>
          Block(statements).render

        case Block(statements) =>
          processStatements(statements)

        case NamedBlock(_, "Shader", statements) =>
          val (body, returnType) = processFunctionStatements(statements)
          s"""void fragment(){$body}"""

        case NamedBlock(_, _, List(Block(statements))) =>
          Block(statements).render

        case NamedBlock(_, "Program", statements) =>
          processStatements(statements)

        case NamedBlock(namespace, id, statements) =>
          s"""$namespace$id {${processStatements(statements)}}"""

        case Function(id, args, body) if id.isEmpty =>
          throw new Exception("Failed to render shader, unnamed function definition found.")

        case Function(id, args, Block(statements)) =>
          val (body, returnType) = processFunctionStatements(statements)
          s"""$returnType $id(${args.mkString(",")}){$body}"""

        case Function(id, args, NamedBlock(_, _, statements)) =>
          val (body, returnType) = processFunctionStatements(statements)
          s"""$returnType $id(${args.mkString(",")}){$body}"""

        case Function(id, args, body) =>
          s"""void $id(${args.mkString(",")}){${body.render}}"""

        case CallFunction(id, args) =>
          s"""$id(${args.mkString(",")})"""

        case DataTypes.closure(body, typeOf) =>
          s"[closure $body $typeOf]"

        case DataTypes.ident(id) =>
          s"$id"

        case DataTypes.float(v) =>
          s"${rf(v)}"

        case DataTypes.vec2(args) =>
          s"vec2(${args.map(_.render).mkString(",")})"

        case DataTypes.vec3(args) =>
          s"vec3(${args.map(_.render).mkString(",")})"

        case DataTypes.vec4(args) =>
          s"vec4(${args.map(_.render).mkString(",")})"

        case Val(id, value) =>
          s"""${decideType(value).getOrElse("void")} $id=${value.render}"""
