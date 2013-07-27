/* NSC -- new Scala compiler
 * Copyright 2007-2013 LAMP/EPFL
 * @author  Paul Phillips
 */

package scala.tools.nsc
package doc

import scala.tools.nsc.ast.parser.{ SyntaxAnalyzer, BracePatch }
import scala.reflect.internal.Chars._
import symtab._
import reporters.Reporter
import typechecker.Analyzer
import scala.reflect.internal.util.{ BatchSourceFile, RangePosition }

trait ScaladocGlobalTrait extends Global {
  outer =>

  override val useOffsetPositions = false
  override def newUnitParser(unit: CompilationUnit) = new syntaxAnalyzer.ScaladocUnitParser(unit, Nil)

  override lazy val syntaxAnalyzer = new ScaladocSyntaxAnalyzer[outer.type](outer) {
    val runsAfter = List[String]()
    val runsRightAfter = None
  }
  override lazy val loaders = new SymbolLoaders {
    val global: outer.type = outer

    // SI-5593 Scaladoc's current strategy is to visit all packages in search of user code that can be documented
    // therefore, it will rummage through the classpath triggering errors whenever it encounters package objects
    // that are not in their correct place (see bug for details)
    override protected def signalError(root: Symbol, ex: Throwable) {
      log(s"Suppressing error involving $root: $ex")
    }

    def lookupMemberAtTyperPhaseIfPossible(sym: Symbol, name: Name): Symbol = {
      def lookup = sym.info.member(name)
      // if loading during initialization of `definitions` typerPhase is not yet set.
      // in that case we simply load the member at the current phase
      if (currentRun.typerPhase eq null)
        lookup
      else
        enteringTyper { lookup }
    }
  }
}

class ScaladocGlobal(settings: doc.Settings, reporter: Reporter) extends Global(settings, reporter) with ScaladocGlobalTrait {
  override protected def computeInternalPhases() {
    phasesSet += syntaxAnalyzer
    phasesSet += analyzer.namerFactory
    phasesSet += analyzer.packageObjects
    phasesSet += analyzer.typerFactory
  }
  override def forScaladoc = true
  override lazy val analyzer = new {
    val global: ScaladocGlobal.this.type = ScaladocGlobal.this
  } with ScaladocAnalyzer
}
