package indigoplugin.generators

object EmbedGLSLShaderPair {

  def generate(
      outDir: os.Path,
      moduleName: String,
      fullyQualifiedPath: String,
      vertex: os.Path,
      fragment: os.Path,
      runValidator: Boolean
  ): Seq[os.Path] = {

    val shaderFiles: Seq[os.Path] =
      Seq(vertex, fragment)

    // Bail out if the file is missing or not a file.
    shaderFiles.foreach { f =>
      if (!os.exists(f)) throw new Exception("Shader file not found: " + f.toString())
      else if (os.isDir(f)) throw new Exception("Shader path given was a directory, not a file: " + f.toString())
      else ()
    }

    val wd = outDir / Generators.OutputDirName

    os.makeDir.all(wd)

    if (runValidator) {
      val glslValidatorExitCode = os.proc("glslangValidator", "-v").call(os.pwd).exitCode

      if (glslValidatorExitCode == 0)
        shaderFiles.foreach { f =>
          val exitCode = os.proc("glslangValidator", f.toString).call(os.pwd).exitCode

          if (exitCode != 0) throw new Exception("GLSL Validation Error in: " + f.toString)
        }
      else
        throw new Exception("Validation was requested, but the GLSL Validator is not installed.")
    }

    val shaderDetails: Seq[ShaderDetails] =
      shaderFiles.map(f => extractDetails(f))

    val contents: String =
      shaderDetails
        .flatMap { d =>
          extractShaderCode(d.shaderCode, "vertex", d.newName) ++
            extractShaderCode(d.shaderCode, "fragment", d.newName) ++
            extractShaderCode(d.shaderCode, "prepare", d.newName) ++
            extractShaderCode(d.shaderCode, "light", d.newName) ++
            extractShaderCode(d.shaderCode, "composite", d.newName)
        }
        .map { snippet =>
          s"""  val ${snippet.variableName}: String =
          |    ${Generators.TripleQuotes}${snippet.snippet}${Generators.TripleQuotes}
          |
          """.stripMargin
        }
        .mkString("\n")

    val file: os.Path =
      wd / s"$moduleName.scala"

    val newContents: String =
      template(moduleName, fullyQualifiedPath, contents)

    os.write(file, newContents)

    Seq(file)
  }

  def sanitiseName(name: String, ext: String): String = {
    val noExt = if (ext.nonEmpty && name.endsWith(ext)) name.dropRight(ext.length) else name
    noExt.replaceAll("[^A-Za-z0-9]", "-").split("-").map(_.capitalize).mkString
  }

  def extractDetails(file: os.Path): ShaderDetails = {
    val name      = file.last
    val ext       = file.ext
    val sanitised = sanitiseName(file.last, file.ext)

    ShaderDetails(sanitised, name, ext, os.read(file))
  }

  def template(moduleName: String, fullyQualifiedPath: String, contents: String): String =
    s"""package $fullyQualifiedPath
    |
    |object $moduleName {
    |
    |$contents
    |
    |}
    """.stripMargin

  def extractShaderCode(text: String, tag: String, newName: String): List[ShaderSnippet] =
    s"""//<indigo-$tag>\n((.|\n|\r)*)//</indigo-$tag>""".r
      .findAllIn(text)
      .toList
      .map(_.toString)
      .map(_.split('\n').drop(1).dropRight(1).mkString("\n"))
      .map(program => ShaderSnippet(newName + tag.split("-").map(_.capitalize).mkString, program))

  case class ShaderDetails(newName: String, originalName: String, ext: String, shaderCode: String)
  case class ShaderSnippet(variableName: String, snippet: String)
}
