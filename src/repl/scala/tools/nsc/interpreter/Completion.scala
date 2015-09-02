/* NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author Paul Phillips
 */

package scala.tools.nsc
package interpreter

import Completion._

/** An implementation-agnostic completion interface which makes no
 *  reference to the jline classes.
 */
trait Completion {
  def resetVerbosity(): Unit
  def completer(): ScalaCompleter
}
object NoCompletion extends Completion {
  def resetVerbosity() = ()
  def completer() = NullCompleter
}

object Completion {
  case class Candidates(cursor: Int, candidates: List[String]) { }
  val NoCandidates = Candidates(-1, Nil)

  object NullCompleter extends ScalaCompleter {
    def complete(buffer: String, cursor: Int): Candidates = NoCandidates
  }
  trait ScalaCompleter {
    def complete(buffer: String, cursor: Int): Candidates
  }

  def looksLikeInvocation(code: String) = (
        (code != null)
    &&  (code startsWith ".")
    && !(code == ".")
    && !(code startsWith "./")
    && !(code startsWith "..")
  )
}
