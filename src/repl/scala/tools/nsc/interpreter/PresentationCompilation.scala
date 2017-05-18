/* NSC -- new Scala compiler
 * Copyright 2005-2015 LAMP/EPFL
 * @author Martin Odersky
 */
package scala.tools.nsc.interpreter

import scala.reflect.internal.util.RangePosition
import scala.reflect.io.AbstractFile
import scala.tools.nsc.backend.JavaPlatform
import scala.tools.nsc.util.ClassPath
import scala.tools.nsc.{interactive, Settings}
import scala.tools.nsc.reporters.StoreReporter
import scala.tools.nsc.classpath._

import scala.tools.nsc.interpreter.Results.{Result, Error}

trait PresentationCompilation {
  self: IMain =>

  /** Typecheck a line of REPL input, suitably wrapped with "interpreter wrapper" objects/classes, with the
    * presentation compiler. The result of this method gives access to the typechecked tree and to autocompletion
    * suggestions.
    *
    * The caller is responsible for calling [[PresentationCompileResult#cleanup]] to dispose of the compiler instance.
    */
  private[scala] def presentationCompile(line1: String): Either[Result, PresentationCompileResult] = {
    if (global == null) Left(Error)
    else {
      val compiler = newPresentationCompiler()
      val trees = compiler.newUnitParser(line1).parseStats()
      val importer = global.mkImporter(compiler)
      val request = new Request(line1, trees map (t => importer.importTree(t)), generousImports = true)
      val wrappedCode: String = request.ObjectSourceCode(request.handlers)
      val unit = compiler.newCompilationUnit(wrappedCode)
      import compiler._
      val richUnit = new RichCompilationUnit(unit.source)
      unitOfFile(richUnit.source.file) = richUnit
      enteringTyper(typeCheck(richUnit))
      val result = PresentationCompileResult(compiler)(richUnit, request.ObjectSourceCode.preambleLength)
      Right(result)
    }
  }

  /** Create an instance of the presentation compiler with a classpath comprising the REPL's configured classpath
    * and the classes output by previously compiled REPL lines.
    *
    * You may directly interact with this compiler from any thread, although you must not access it concurrently
    * from multiple threads.
    *
    * You may downcast the `reporter` to `StoreReporter` to access type errors.
    */
  def newPresentationCompiler(): interactive.Global = {
    def mergedFlatClasspath = {
      val replOutClasspath = ClassPathFactory.newClassPath(replOutput.dir, settings)
      AggregateClassPath(replOutClasspath :: global.platform.classPath :: Nil)
    }
    def copySettings: Settings = {
      val s = new Settings(_ => () /* ignores "bad option -nc" errors, etc */)
      s.processArguments(global.settings.recreateArgs, processAll = false)
      s.YpresentationAnyThread.value = true
      s
    }
    val storeReporter: StoreReporter = new StoreReporter
    val interactiveGlobal = new interactive.Global(copySettings, storeReporter) { self =>
      override lazy val platform: ThisPlatform = {
        new JavaPlatform {
          lazy val global: self.type = self
          override private[nsc] lazy val classPath: ClassPath = mergedFlatClasspath
        }
      }
    }
    new interactiveGlobal.TyperRun()
    interactiveGlobal
  }

  abstract class PresentationCompileResult {
    private[interpreter] val compiler: scala.tools.nsc.interactive.Global
    def unit: compiler.RichCompilationUnit
    /** The length of synthetic code the precedes the user written code */
    def preambleLength: Int
    def cleanup(): Unit = {
      compiler.askShutdown()
    }
    import compiler.CompletionResult

    def completionsAt(cursor: Int): CompletionResult = {
      val pos = unit.source.position(preambleLength + cursor)
      compiler.completionsAt(pos)
    }
    def typedTreeAt(code: String, selectionStart: Int, selectionEnd: Int): compiler.Tree = {
      val start = selectionStart + preambleLength
      val end   = selectionEnd + preambleLength
      val pos   = new RangePosition(unit.source, start, start, end)
      compiler.typedTreeAt(pos)
    }

    def tree(buf: String): compiler.Tree = {
      import compiler.{Locator, Template, Block}
      val offset = preambleLength
      val pos1 = unit.source.position(offset).withEnd(offset + buf.length)
      new Locator(pos1) locateIn unit.body match {
        case Template(_, _, constructor :: preambleEndMember :: (rest :+ last)) => if (rest.isEmpty) last else Block(rest, last)
        case t => t
      }
    }

    def typeString(tree: compiler.Tree): String =
      compiler.exitingTyper(tree.tpe.toString)

    def treeString(tree: compiler.Tree): String =
      compiler.showCode(tree)


  }

  object PresentationCompileResult {
    def apply(compiler0: interactive.Global)(unit0: compiler0.RichCompilationUnit, preambleLength0: Int) = new PresentationCompileResult {

      override val compiler = compiler0

      override def unit = unit0.asInstanceOf[compiler.RichCompilationUnit]

      override def preambleLength = preambleLength0
    }
  }
}
