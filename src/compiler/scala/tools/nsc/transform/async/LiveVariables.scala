/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc.transform.async

import java.util.function.IntConsumer

import scala.collection.immutable.IntMap
import scala.collection.mutable
import scala.reflect.internal.Flags._

trait LiveVariables extends ExprBuilder {
  import global._

  /**
   *  Live variables data-flow analysis.
   *
   *  Find, for each lifted field, the last state where the field is used.
   *
   *  @param   asyncStates the states of an `async` block
   *  @param   liftables   the lifted fields
   *  @return              a map which indicates fields which are used for the final time in each state.
   */
  def fieldsToNullOut(asyncStates: List[AsyncState], finalState: AsyncState, liftables: List[Tree]): mutable.LinkedHashMap[Int, mutable.LinkedHashSet[Symbol]] = {
    val liftedSyms: Set[Symbol] = // include only vars
      liftables.iterator.filter {
        case ValDef(mods, _, _, _) => mods.hasFlag(MUTABLE)
        case _ => false
      }.map(_.symbol).toSet

    // determine which fields should be live also at the end (will not be nulled out)
    val noNull: Set[Symbol] = liftedSyms.filter { sym =>
      val tpSym = sym.info.typeSymbol
      (tpSym.isPrimitiveValueClass || tpSym == definitions.NothingClass) || liftables.exists { tree =>
        !liftedSyms.contains(tree.symbol) && tree.exists(_.symbol == sym)
      }
    }
    debuglog(s"fields never zero-ed out: ${noNull.mkString(", ")}")

    /**
     *  Traverse statements of an `AsyncState`, collect `Ident`-s referring to lifted fields.
     *
     *  @param  as  a state of an `async` expression
     *  @return     a set of lifted fields that are used within state `as`
     */
    def fieldsUsedIn(as: AsyncState): ReferencedFields = {
      class FindUseTraverser extends AsyncTraverser {
        var usedFields: Set[Symbol] = Set[Symbol]()
        var capturedFields: Set[Symbol] = Set[Symbol]()
        private def capturing[A](body: => A): A = {
          val saved = capturing
          try {
            capturing = true
            body
          } finally capturing = saved
        }
        private def capturingCheck(tree: Tree) = capturing(tree foreach check)
        private var capturing: Boolean = false
        private def check(tree: Tree): Unit = {
          tree match {
            case Ident(_) if liftedSyms(tree.symbol) =>
              if (capturing)
                capturedFields += tree.symbol
              else
                usedFields += tree.symbol
            case _ =>
          }
        }
        override def traverse(tree: Tree) = {
          check(tree)
          super.traverse(tree)
        }

        override def nestedClass(classDef: ClassDef): Unit = capturingCheck(classDef)

        override def nestedModuleClass(moduleClass: ClassDef): Unit = capturingCheck(moduleClass)

        override def nestedMethod(defdef: DefDef): Unit = capturingCheck(defdef)

        override def byNameArgument(arg: Tree): Unit = capturingCheck(arg)

        override def function(function: Function): Unit = capturingCheck(function)
        override def function(expandedFunction: ClassDef): Unit = capturingCheck(expandedFunction)
      }

      val findUses = new FindUseTraverser
      findUses.traverse(Block(as.stats: _*))
      ReferencedFields(findUses.usedFields, findUses.capturedFields)
    }
    case class ReferencedFields(used: Set[Symbol], captured: Set[Symbol]) {
      override def toString = s"used: ${used.mkString(",")}\ncaptured: ${captured.mkString(",")}"
    }

    if (settings.debug.value && shouldLogAtThisPhase) {
      for (as <- asyncStates)
        debuglog(s"fields used in state #${as.state}: ${fieldsUsedIn(as)}")
    }

    /* Backwards data-flow analysis. Computes live variables information at entry and exit
     * of each async state.
     *
     * Compute using a simple fixed point iteration:
     *
     * 1. currStates = List(finalState)
     * 2. for each cs \in currStates, compute LVentry(cs) from LVexit(cs) and used fields information for cs
     * 3. record if LVentry(cs) has changed for some cs.
     * 4. obtain predecessors pred of each cs \in currStates
     * 5. for each p \in pred, compute LVexit(p) as union of the LVentry of its successors
     * 6. currStates = pred
     * 7. repeat if something has changed
     */

    var LVentry = IntMap[Set[Symbol]]() withDefaultValue Set[Symbol]()
    var LVexit  = IntMap[Set[Symbol]]() withDefaultValue Set[Symbol]()

    // All fields are declared to be dead at the exit of the final async state, except for the ones
    // that cannot be nulled out at all (those in noNull), because they have been captured by a nested def.
    LVexit = LVexit + (finalState.state -> noNull)

    var currStates = List(finalState)    // start at final state
    var captured: Set[Symbol] = Set()

    def contains(as: Array[Int], a: Int): Boolean = {
      var i = 0
      while (i < as.length) {
        if (as(i) == a) return true
        i += 1
      }
      false
    }
    while (!currStates.isEmpty) {
      var entryChanged: List[AsyncState] = Nil

      for (cs <- currStates) {
        val LVentryOld = LVentry(cs.state)
        val referenced = fieldsUsedIn(cs)
        captured ++= referenced.captured
        val LVentryNew = LVexit(cs.state) ++ referenced.used
        if (!LVentryNew.sameElements(LVentryOld)) {
          LVentry = LVentry.updated(cs.state, LVentryNew)
          entryChanged ::= cs
        }
      }

      val pred = entryChanged.flatMap(cs => asyncStates.filter(state => contains(state.nextStates, cs.state)))
      var exitChanged: List[AsyncState] = Nil

      for (p <- pred) {
        val LVexitOld = LVexit(p.state)
        val LVexitNew = p.nextStates.flatMap(succ => LVentry(succ)).toSet
        if (!LVexitNew.sameElements(LVexitOld)) {
          LVexit = LVexit.updated(p.state, LVexitNew)
          exitChanged ::= p
        }
      }

      currStates = exitChanged
    }

    if(settings.debug.value && shouldLogAtThisPhase) {
      for (as <- asyncStates) {
        debuglog(s"LVentry at state #${as.state}: ${LVentry(as.state).mkString(", ")}")
        debuglog(s"LVexit  at state #${as.state}: ${LVexit(as.state).mkString(", ")}")
      }
    }

    def lastUsagesOf(field: Tree, at: AsyncState): StateSet = {
      val avoid = scala.collection.mutable.HashSet[AsyncState]()

      val result = new StateSet
      def lastUsagesOf0(field: Tree, at: AsyncState): Unit = {
        if (avoid(at)) ()
        else if (captured(field.symbol)) {
          ()
        }
        else LVentry get at.state match {
          case Some(fields) if fields.contains(field.symbol) =>
            result += at.state
          case _ =>
            avoid += at
            for (state <- asyncStates) {
              if (contains(state.nextStates, at.state)) {
                lastUsagesOf0(field, state)
              }
            }
        }
      }

      lastUsagesOf0(field, at)
      result
    }

    val lastUsages: mutable.LinkedHashMap[Symbol, StateSet] =
      mutable.LinkedHashMap(liftables.map(fld => fld.symbol -> lastUsagesOf(fld, finalState)): _*)

    if (settings.debug.value && shouldLogAtThisPhase) {
      for ((fld, lastStates) <- lastUsages)
        debuglog(s"field ${fld.name} is last used in states ${lastStates.iterator.mkString(", ")}")
    }

    if (settings.debug.value && shouldLogAtThisPhase) {
      for ((fld, killAt) <- lastUsages)
        debuglog(s"field ${fld.name} should be nulled out at the conclusion of states ${killAt.iterator.mkString(", ")}")
    }

    val assignsOf = mutable.LinkedHashMap[Int, mutable.LinkedHashSet[Symbol]]()

    for ((fld, where) <- lastUsages) {
      where.foreach { new IntConsumer { def accept(state: Int): Unit = {
        assignsOf.getOrElseUpdate(state, new mutable.LinkedHashSet()) += fld
      }}}
    }
    assignsOf
  }
}
