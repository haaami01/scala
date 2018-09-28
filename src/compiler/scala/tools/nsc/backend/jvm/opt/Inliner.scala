/* NSC -- new Scala compiler
 * Copyright 2005-2014 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.nsc
package backend.jvm
package opt

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.tools.asm
import scala.tools.asm.Opcodes._
import scala.tools.asm.tree._
import scala.tools.nsc.backend.jvm.AsmUtils._
import scala.tools.nsc.backend.jvm.BTypes.InternalName
import scala.tools.nsc.backend.jvm.BackendReporting._
import scala.tools.nsc.backend.jvm.analysis.BackendUtils
import scala.tools.nsc.backend.jvm.opt.BytecodeUtils._

abstract class Inliner {
  val postProcessor: PostProcessor

  import postProcessor._
  import bTypes._
  import bTypesFromClassfile._
  import backendUtils._
  import callGraph._
  import frontendAccess.{backendReporting, compilerSettings}
  import inlinerHeuristics._

  // A callsite that was inlined and the IllegalAccessInstructions warning that was delayed.
  // The inliner speculatively inlines a callsite even if the method then has instructions that would
  // cause an IllegalAccessError in the target class. If all of those instructions are eliminated
  // (by inlining) in a later round, everything is fine. Otherwise the method is reverted.
  final case class InlinedCallsite(eliminatedCallsite: Callsite, warning: Option[IllegalAccessInstructions]) {
    // If this InlinedCallsite has a warning about a given instruction, return a copy where the warning
    // only contains that instruction.
    def filterForWarning(insn: AbstractInsnNode): Option[InlinedCallsite] = warning match {
      case Some(w) if w.instructions.contains(insn) => Some(this.copy(warning = Some(w.copy(instructions = List(insn)))))
      case _ => None
    }
  }

  // The state accumulated across inlining rounds for a single MethodNode
  final class MethodInlinerState {
    // Instructions that were copied into a method and would cause an IllegalAccess. They need to
    // be inlined in a later round, otherwise the method is rolled back to its original state.
    val illegalAccessInstructions = mutable.Set.empty[AbstractInsnNode]

    // A map from invocation instructions that were copied (inlined) into this method to the
    // inlined callsite from which they originate.
    // Note: entries are not removed from this map, even if an inlined callsite gets inlined in a
    // later round. This allows re-constructing the inline chain.
    val inlinedCalls = mutable.Map.empty[AbstractInsnNode, InlinedCallsite]

    var undoLog: UndoLog = NoUndoLogging

    override def clone(): MethodInlinerState = {
      val r = new MethodInlinerState
      r.illegalAccessInstructions ++= illegalAccessInstructions
      r.inlinedCalls ++= inlinedCalls
      // Skip undoLog: clone() is only called when undoLog == NoUndoLogging
      r
    }

    // The chain of inlined callsites that that lead to some (call) instruction. Don't include
    // synthetic forwarders if skipForwarders is true (don't show those in inliner warnings, as they
    // don't show up in the source code).
    // Also used to detect inlining cycles.
    def inlineChain(call: AbstractInsnNode, skipForwarders: Boolean): List[Callsite] = {
      @tailrec def impl(insn: AbstractInsnNode, res: List[Callsite]): List[Callsite] = inlinedCalls.get(insn) match {
        case Some(inlinedCallsite) =>
          val cs = inlinedCallsite.eliminatedCallsite
          val res1 = if (skipForwarders && backendUtils.isTraitSuperAccessorOrMixinForwarder(cs.callee.get.callee, cs.callee.get.calleeDeclarationClass)) res else cs :: res
          impl(cs.callsiteInstruction, res1)
        case _ =>
          res
      }
      impl(call, Nil)
    }

    // In a chain of inlined calls which lead to some (call) instruction, return the root `InlinedCallsite`
    // which has a delayed warning . When inlining `call` fails, warn about the root instruction instead of
    // the downstream inline request that tried to eliminate an illegalAccess instruction.
    def rootInlinedCallsiteWithWarning(call: AbstractInsnNode, skipForwarders: Boolean): Option[InlinedCallsite] = {
      @tailrec def impl(insn: AbstractInsnNode, res: Option[InlinedCallsite]): Option[InlinedCallsite] = inlinedCalls.get(insn) match {
        case Some(inlinedCallsite) =>
          val w = inlinedCallsite.filterForWarning(insn)
          if (w.isEmpty) res
          else {
            val cs = inlinedCallsite.eliminatedCallsite
            val nextRes = if (skipForwarders && backendUtils.isTraitSuperAccessorOrMixinForwarder(cs.callee.get.callee, cs.callee.get.calleeDeclarationClass)) res else w
            impl(cs.callsiteInstruction, nextRes)
          }

        case _ => res
      }
      impl(call, None)
    }
  }

  sealed trait InlineLog {
    def request: InlineRequest
  }
  final case class InlineLogSuccess(request: InlineRequest, sizeBefore: Int, sizeInlined: Int) extends InlineLog {
    var downstreamLog: mutable.Buffer[InlineLog] = mutable.ListBuffer.empty
  }
  final case class InlineLogFail(request: InlineRequest, warning: CannotInlineWarning) extends InlineLog
  final case class InlineLogRollback(request: InlineRequest, warnings: List[CannotInlineWarning]) extends InlineLog

  object InlineLog {
    private def shouldLog(request: InlineRequest): Boolean = compilerSettings.optLogInline match {
      case Some(v) =>
        def matchesName = {
          val prefix = v match {
            case "_" => ""
            case p => p
          }
          val name: String = request.callsite.callsiteClass.internalName + "." + request.callsite.callsiteMethod.name
          name startsWith prefix
        }
        upstream != null || (isTopLevel && matchesName)

      case _ => false
    }

    // indexed by callsite method
    private val logs = mutable.Map.empty[MethodNode, mutable.LinkedHashSet[InlineLog]]

    private var upstream: InlineLogSuccess = _
    private var isTopLevel = true

    def withInlineLogging[T](request: InlineRequest)(inlineRequest: => Unit)(inlinePost: => T): T = {
      def doInlinePost(): T = {
        val savedIsTopLevel = isTopLevel
        isTopLevel = false
        try inlinePost
        finally isTopLevel = savedIsTopLevel
      }
      if (shouldLog(request)) {
        val sizeBefore = request.callsite.callsiteMethod.instructions.size
        inlineRequest
        val log = InlineLogSuccess(request, sizeBefore, request.callsite.callee.get.callee.instructions.size)
        apply(log)

        val savedUpstream = upstream
        upstream = log
        try doInlinePost()
        finally upstream = savedUpstream
      } else {
        inlineRequest
        doInlinePost()
      }
    }

    def apply(log: => InlineLog): Unit = if (shouldLog(log.request)) {
      if (upstream != null) upstream.downstreamLog += log
      else {
        val methodLogs = logs.getOrElseUpdate(log.request.callsite.callsiteMethod, mutable.LinkedHashSet.empty)
        methodLogs += log
      }
    }

    def entryString(log: InlineLog, indent: Int = 0): String = {
      val callee = log.request.callsite.callee.get
      val calleeString = callee.calleeDeclarationClass.internalName + "." + callee.callee.name
      val indentString = " " * indent
      log match {
        case s @ InlineLogSuccess(_, sizeBefore, sizeInlined) =>
          val self = s"${indentString}inlined $calleeString. Before: $sizeBefore ins, inlined: $sizeInlined ins."
          if (s.downstreamLog.isEmpty) self
          else s.downstreamLog.iterator.map(entryString(_, indent + 2)).mkString(self + "\n", "\n", "")

        case InlineLogFail(_, w) =>
          s"${indentString}failed $calleeString. ${w.toString.replace('\n', ' ')}"

        case InlineLogRollback(_, _) =>
          s"${indentString}rolling back, nested inline failed."
      }
    }

    def print(): Unit = if (compilerSettings.optLogInline.isDefined) {
      val byClassAndMethod: List[(InternalName, mutable.Map[MethodNode, mutable.LinkedHashSet[InlineLog]])] = {
        logs.
          groupBy(_._2.head.request.callsite.callsiteClass.internalName).
          toList.sortBy(_._1)
      }
      for {
        (c, methodLogs) <- byClassAndMethod
        (m, mLogs) <- methodLogs.toList.sortBy(_._1.name)
        mLog <- mLogs // insertion order
      } {
        println(s"Inline into $c.${m.name}: ${entryString(mLog)}")
      }
    }
  }

  // True if all instructions (they would cause an IllegalAccessError otherwise) can potentially be
  // inlined in a later inlining round.
  // Note that this method has a side effect. It allows inlining `INVOKESPECIAL` calls of static
  // super accessors that we emit in traits. The inlined calls are marked in the call graph as
  // `staticallyResolvedInvokespecial`. When looking up the MethodNode for the cloned `INVOKESPECIAL`,
  // the call graph will always return the corresponding method in the trait.
  def maybeInlinedLater(callsite: Callsite, insns: List[AbstractInsnNode]): Boolean = {
    insns.forall({
      case mi: MethodInsnNode =>
        (mi.getOpcode != INVOKESPECIAL) || {
          // An invokespecial T.f that appears within T, and T defines f.
          // Such an instruction is inlined into a different class, but it needs to be inlined in
          // turn in a later inlining round.
          // The call graph needs to treat it specially: the normal dynamic lookup needs to be
          // avoided, it needs to resolve to T.f, no matter in which class the invocation appears.
          def hasMethod(c: ClassNode): Boolean = {
            val r = c.methods.iterator.asScala.exists(m => m.name == mi.name && m.desc == mi.desc)
            if (r) callGraph.staticallyResolvedInvokespecial += mi
            r
          }

          mi.name != GenBCode.INSTANCE_CONSTRUCTOR_NAME &&
            mi.owner == callsite.callee.get.calleeDeclarationClass.internalName &&
            byteCodeRepository.classNode(mi.owner).map(hasMethod).getOrElse(false)
        }
      case _ => false
    })
  }

  def runInlinerAndClosureOptimizer(): Unit = {
    val runClosureOptimizer = compilerSettings.optClosureInvocations
    var firstRound = true
    var changedByClosureOptimizer = Seq.empty[MethodNode]

    while (firstRound || changedByClosureOptimizer.nonEmpty) {
      val specificMethodsForInlining = if (firstRound) None else Some(changedByClosureOptimizer)
      val changedByInliner = runInliner(specificMethodsForInlining)

      if (runClosureOptimizer) {
        val specificMethodsForClosureRewriting = if (firstRound) None else Some(changedByInliner)
        changedByClosureOptimizer = closureOptimizer.rewriteClosureApplyInvocations(specificMethodsForClosureRewriting)
      }

      firstRound = false
    }
  }

  /**
   * @param methods The methods to check for callsites to inline. If not defined, check all methods.
   * @return The set of changed methods, in no deterministic order.
   */
  def runInliner(methods: Option[Seq[MethodNode]]): Iterable[MethodNode] = {
    // Inline requests are grouped by method for performance: we only update the call graph (which
    // runs analyzers) once all callsites are inlined.
    val requests: mutable.Queue[(MethodNode, List[InlineRequest])] =
      if (methods.isEmpty) collectAndOrderInlineRequests
      else mutable.Queue.empty

    val inlinerState = mutable.Map.empty[MethodNode, MethodInlinerState]

    // Methods that were changed (inlined into), they will be checked for more callsites to inline
    val changedMethods = {
      val r = mutable.Queue.empty[MethodNode]
      methods.foreach(r.addAll)
      r
    }

    // Don't try again to inline failed callsites
    val failed = mutable.Set.empty[MethodInsnNode]

    val overallChangedMethods = mutable.Set.empty[MethodNode]

    var currentMethodRolledBack = false

    // Show chain of inlines that lead to a failure in inliner warnings
    def inlineChainSuffix(callsite: Callsite, chain: List[Callsite]): String =
      if (chain.isEmpty) "" else
        s"""
           |Note that this callsite was itself inlined into ${BackendReporting.methodSignature(callsite.callsiteClass.internalName, callsite.callsiteMethod)}
           |by inlining the following methods:
           |${chain.map(cs => BackendReporting.methodSignature(cs.callee.get.calleeDeclarationClass.internalName, cs.callee.get.callee)).mkString("  - ", "\n  - ", "")}""".stripMargin

    while (requests.nonEmpty || changedMethods.nonEmpty) {
      // First inline all requests that were initially collected. Then check methods that changed
      // for more callsites to inline.
      // Alternatively, we could find more callsites directly after inlining the initial requests
      // of a method, before inlining into other methods. But that could cause work duplication. If
      // a callee is inlined before the inliner has run on it, the inliner needs to do the work on
      // both the callee and the cloned version(s).
      if (requests.nonEmpty) {
        val (method, rs) = requests.dequeue()
        val state = inlinerState.getOrElseUpdate(method, new MethodInlinerState)
        var changed = false

        def doInline(r: InlineRequest, w: Option[IllegalAccessInstructions]): Map[AbstractInsnNode, AbstractInsnNode] = {
          val instructionMap = inlineCallsite(r.callsite, updateCallGraph = false)
          val inlined = InlinedCallsite(r.callsite, w.map(iw => iw.copy(instructions = iw.instructions.map(instructionMap))))
          instructionMap.valuesIterator foreach {
            case mi: MethodInsnNode => state.inlinedCalls(mi) = inlined
            case _ =>
          }
          for (warn <- w; ins <- warn.instructions) {
            state.illegalAccessInstructions += instructionMap(ins)
          }
          val callInsn = r.callsite.callsiteInstruction
          state.illegalAccessInstructions.remove(callInsn)
          if (state.illegalAccessInstructions.isEmpty)
            state.undoLog = NoUndoLogging
          changed = true
          instructionMap
        }

        for (r <- rs) if (!currentMethodRolledBack) {
          canInlineCallsite(r.callsite) match {
            case None =>
              doInline(r, None)

            case Some(w: IllegalAccessInstructions) if maybeInlinedLater(r.callsite, w.instructions) =>
              if (state.undoLog == NoUndoLogging) {
                val undo = new UndoLog()
                val currentState = state.clone()
                undo.saveMethodState(r.callsite.callsiteClass.internalName, method)
                undo {
                  failed += r.callsite.callsiteInstruction
                  inlinerState(method) = currentState
                  currentMethodRolledBack = true
                  // method is not in changedMethods in both places where `rollback` is invoked
                  changedMethods.enqueue(method)
                  BackendUtils.clearDceDone(method)
                  analyzerCache.invalidate(method)
                }
                state.undoLog = undo
              }
              doInline(r, Some(w))

            case Some(w) =>
              val callInsn = r.callsite.callsiteInstruction

              if (state.illegalAccessInstructions(callInsn))
                state.undoLog.rollback()

              state.rootInlinedCallsiteWithWarning(r.callsite.callsiteInstruction, skipForwarders = true) match {
                case Some(inlinedCallsite) =>
                  val rw = inlinedCallsite.warning.get
                  if (rw.emitWarning(compilerSettings)) {
                    backendReporting.inlinerWarning(
                      inlinedCallsite.eliminatedCallsite.callsitePosition,
                      rw.toString + inlineChainSuffix(r.callsite, state.inlineChain(inlinedCallsite.eliminatedCallsite.callsiteInstruction, skipForwarders = true)))
                  }
                case _ =>
                  if (w.emitWarning(compilerSettings))
                    backendReporting.inlinerWarning(
                      r.callsite.callsitePosition,
                      w.toString + inlineChainSuffix(r.callsite, state.inlineChain(r.callsite.callsiteInstruction, skipForwarders = true)))
              }
          }
        }

        if (changed) {
          callGraph.refresh(method, rs.head.callsite.callsiteClass)
          changedMethods.enqueue(method)
          overallChangedMethods += method
        }

      } else {
        // look at all callsites in a methods again, also those that were previously not selected for
        // inlining. after inlining, types might get more precise and make a callsite inlineable.
        val method = changedMethods.dequeue()
        val state = inlinerState.getOrElseUpdate(method, new MethodInlinerState)

        def isLoop(call: MethodInsnNode, callee: Callee): Boolean =
          callee.callee == method || {
            state.inlineChain(call, skipForwarders = false).exists(_.callee.get.callee == callee.callee)
          }

        val rs = mutable.ListBuffer.empty[InlineRequest]
        callGraph.callsites(method).valuesIterator foreach {
          // Don't inline: recursive calls, callsites that failed inlining before
          case cs: Callsite if !failed(cs.callsiteInstruction) && cs.callee.isRight && !isLoop(cs.callsiteInstruction, cs.callee.get) =>
            inlineRequest(cs) match {
              case Some(Right(req)) => rs += req
              case _ =>
            }
          case _ =>
        }
        val newRequests = rs.toList.sorted(callsiteOrdering)

        state.illegalAccessInstructions.find(insn => newRequests.forall(_.callsite.callsiteInstruction != insn)) match {
          case None =>
            if (newRequests.isEmpty)
              inlinerState.remove(method) // we're done with this method
            else
              requests.enqueue(method -> newRequests)


          case Some(notInlinedIllegalInsn) =>
            state.undoLog.rollback()
            state.rootInlinedCallsiteWithWarning(notInlinedIllegalInsn, skipForwarders = true) match {
              case Some(inlinedCallsite) =>
                val w = inlinedCallsite.warning.get
                if (w.emitWarning(compilerSettings))
                  backendReporting.inlinerWarning(inlinedCallsite.eliminatedCallsite.callsitePosition, w.toString + inlineChainSuffix(inlinedCallsite.eliminatedCallsite, state.inlineChain(inlinedCallsite.eliminatedCallsite.callsiteInstruction, skipForwarders = true)))
              case _ =>
                // TODO: replace by dev warning after testing
                assert(false, "should not happen")
            }
        }

        currentMethodRolledBack = false
      }
    }
    // todo inline logging
//    InlineLog.print()
    overallChangedMethods
  }

  /**
   * Ordering for inline requests. Required to make the inliner deterministic:
   *   - Always remove the same request when breaking inlining cycles
   *   - Perform inlinings in a consistent order
   */
  object callsiteOrdering extends Ordering[InlineRequest] {
    override def compare(x: InlineRequest, y: InlineRequest): Int = {
      if (x eq y) return 0

      val xCs = x.callsite
      val yCs = y.callsite
      val cls = xCs.callsiteClass.internalName compareTo yCs.callsiteClass.internalName
      if (cls != 0) return cls

      val name = xCs.callsiteMethod.name compareTo yCs.callsiteMethod.name
      if (name != 0) return name

      val desc = xCs.callsiteMethod.desc compareTo yCs.callsiteMethod.desc
      if (desc != 0) return desc

      def pos(c: Callsite) = c.callsiteMethod.instructions.indexOf(c.callsiteInstruction)
      pos(xCs) - pos(yCs)
    }
  }

  /**
   * Returns the callsites that can be inlined, grouped by method. Ensures that the returned inline
   * request graph does not contain cycles.
   *
   * The resulting list is sorted such that the leaves of the inline request graph are on the left.
   * Once these leaves are inlined, the successive elements will be leaves, etc.
   */
  private def collectAndOrderInlineRequests: mutable.Queue[(MethodNode, List[InlineRequest])] = {
    val requestsByMethod = selectCallsitesForInlining withDefaultValue Set.empty

    val elided = mutable.Set.empty[InlineRequest]
    def nonElidedRequests(methodNode: MethodNode): Set[InlineRequest] = requestsByMethod(methodNode) diff elided

    /**
     * Break cycles in the inline request graph by removing callsites.
     *
     * The list `requests` is traversed left-to-right, removing those callsites that are part of a
     * cycle. Elided callsites are also removed from the `inlineRequestsForMethod` map.
     */
    def breakInlineCycles: List[(MethodNode, List[InlineRequest])] = {
      // is there a path of inline requests from start to goal?
      def isReachable(start: MethodNode, goal: MethodNode): Boolean = {
        @tailrec def reachableImpl(check: Set[MethodNode], visited: Set[MethodNode]): Boolean = {
          if (check.isEmpty) false
          else {
            val x = check.head
            if (x == goal) true
            else if (visited(x)) reachableImpl(check - x, visited)
            else {
              val callees = nonElidedRequests(x).map(_.callsite.callee.get.callee)
              reachableImpl(check - x ++ callees, visited + x)
            }
          }
        }
        reachableImpl(Set(start), Set.empty)
      }

      val requests = requestsByMethod.valuesIterator.flatten.toArray
      // sort the inline requests to ensure that removing requests is deterministic
      // Callsites within the same method are next to each other in the sorted array.
      java.util.Arrays.sort(requests, callsiteOrdering)

      val result = new mutable.ListBuffer[(MethodNode, List[InlineRequest])]()
      var currentMethod: MethodNode = null
      val currentMethodRequests = mutable.ListBuffer.empty[InlineRequest]
      for (r <- requests) {
        // is there a chain of inlining requests that would inline the callsite method into the callee?
        if (isReachable(r.callsite.callee.get.callee, r.callsite.callsiteMethod))
          elided += r
        else {
          val m = r.callsite.callsiteMethod
          if (m == currentMethod) {
            currentMethodRequests += r
          } else {
            if (currentMethod != null)
              result += ((currentMethod, currentMethodRequests.toList))
            currentMethod = m
            currentMethodRequests.clear()
            currentMethodRequests += r
          }
        }
      }
      if (currentMethod != null)
        result += ((currentMethod, currentMethodRequests.toList))
      result.toList
    }

    // sort the remaining inline requests such that the leaves appear first, then those requests
    // that become leaves, etc.
    def leavesFirst(requests: List[(MethodNode, List[InlineRequest])]): mutable.Queue[(MethodNode, List[InlineRequest])] = {
      val result = mutable.Queue.empty[(MethodNode, List[InlineRequest])]
      val visited = mutable.Set.empty[MethodNode]

      @tailrec def impl(toAdd: List[(MethodNode, List[InlineRequest])]): Unit =
        if (toAdd.nonEmpty) {
          val rest = mutable.ListBuffer.empty[(MethodNode, List[InlineRequest])]
          toAdd.foreach { case r @ (_, rs) =>
            val callees = rs.iterator.map(_.callsite.callee.get.callee)
            if (callees.forall(c => visited(c) || nonElidedRequests(c).isEmpty)) {
              result += r
              visited += r._1
            } else
              rest += r
          }
          impl(rest.toList)
        }

      impl(requests)
      result
    }

    leavesFirst(breakInlineCycles)
  }

  class UndoLog(active: Boolean = true) {
    import java.util.{ArrayList => JArrayList}

    private var actions = List.empty[() => Unit]

    def apply(a: => Unit): Unit = if (active) actions = (() => a) :: actions
    def rollback(): Unit = if (active) actions.foreach(_.apply())

    def saveMethodState(ownerClass: InternalName, methodNode: MethodNode): Unit = if (active) {
      val currentInstructions = methodNode.instructions.toArray
      val currentLocalVariables = new JArrayList(methodNode.localVariables)
      val currentTryCatchBlocks = new JArrayList(methodNode.tryCatchBlocks)
      val currentMaxLocals = methodNode.maxLocals
      val currentMaxStack = methodNode.maxStack

      val currentCallsites = callsites(methodNode)
      val currentClosureInstantiations = closureInstantiations(methodNode)

      val currentIndyLambdaBodyMethods = indyLambdaBodyMethods(ownerClass, methodNode)

      // We don't save / restore the CallGraph's
      //   - callsitePositions
      //   - inlineAnnotatedCallsites
      //   - noInlineAnnotatedCallsites
      //   - staticallyResolvedInvokespecial
      // These contain instructions, and we never remove from them. So when rolling back a method's
      // instruction list, the old instructions are still in there.

      apply {
        // `methodNode.instructions.clear()` doesn't work: it keeps the `prev` / `next` / `index` of
        // instruction nodes. `instructions.removeAll(true)` would work, but is not public.
        methodNode.instructions.iterator.asScala.toList.foreach(methodNode.instructions.remove)
        for (i <- currentInstructions) methodNode.instructions.add(i)

        methodNode.localVariables.clear()
        methodNode.localVariables.addAll(currentLocalVariables)

        methodNode.tryCatchBlocks.clear()
        methodNode.tryCatchBlocks.addAll(currentTryCatchBlocks)

        methodNode.maxLocals = currentMaxLocals
        methodNode.maxStack = currentMaxStack

        callsites(methodNode) = currentCallsites
        closureInstantiations(methodNode) = currentClosureInstantiations

        onIndyLambdaImplMethodIfPresent(ownerClass)(_.remove(methodNode))
        if (currentIndyLambdaBodyMethods.nonEmpty)
          onIndyLambdaImplMethod(ownerClass)(ms => ms(methodNode) = mutable.Map.empty ++= currentIndyLambdaBodyMethods)
      }
    }
  }

  val NoUndoLogging = new UndoLog(active = false)

  /**
   * Copy and adapt the instructions of a method to a callsite.
   *
   * Preconditions:
   *   - The callsite can safely be inlined (canInlineBody is true)
   *   - The maxLocals and maxStack values of the callsite method are correctly computed
   *
   * @return A map associating instruction nodes of the callee with the corresponding cloned
   *         instruction in the callsite method.
   */
  def inlineCallsite(callsite: Callsite, updateCallGraph: Boolean = true): Map[AbstractInsnNode, AbstractInsnNode] = {
    import callsite._
    val Right(callsiteCallee) = callsite.callee
    import callsiteCallee.{callee, calleeDeclarationClass, sourceFilePath}

    // Inlining requires the callee not to have unreachable code, the analyzer used below should not
    // return any `null` frames. Note that inlining a method can create unreachable code. Example:
    //   def f = throw e
    //   def g = f; println() // println is unreachable after inlining f
    // If we have an inline request for a call to g, and f has been already inlined into g, we
    // need to run DCE on g's body before inlining g.
    localOpt.minimalRemoveUnreachableCode(callee, calleeDeclarationClass.internalName)

    // If the callsite was eliminated by DCE, do nothing.
    if (!callGraph.containsCallsite(callsite)) return Map.empty

    // New labels for the cloned instructions
    val labelsMap = cloneLabels(callee)
    val sameSourceFile = sourceFilePath match {
      case Some(calleeSource) => byteCodeRepository.compilingClasses.get(callsiteClass.internalName) match {
        case Some((_, `calleeSource`)) => true
        case _ => false
      }
      case _ => false
    }
    val (clonedInstructions, instructionMap) = cloneInstructions(callee, labelsMap, callsitePosition, keepLineNumbers = sameSourceFile)

    // local vars in the callee are shifted by the number of locals at the callsite
    val localVarShift = callsiteMethod.maxLocals
    clonedInstructions.iterator.asScala foreach {
      case varInstruction: VarInsnNode => varInstruction.`var` += localVarShift
      case iinc: IincInsnNode          => iinc.`var` += localVarShift
      case _ => ()
    }

    // add a STORE instruction for each expected argument, including for THIS instance if any
    val argStores = new InsnList
    var nextLocalIndex = callsiteMethod.maxLocals
    if (!isStaticMethod(callee)) {
      if (!receiverKnownNotNull) {
        argStores.add(new InsnNode(DUP))
        val nonNullLabel = newLabelNode
        argStores.add(new JumpInsnNode(IFNONNULL, nonNullLabel))
        argStores.add(new InsnNode(ACONST_NULL))
        argStores.add(new InsnNode(ATHROW))
        argStores.add(nonNullLabel)
      }
      argStores.add(new VarInsnNode(ASTORE, nextLocalIndex))
      nextLocalIndex += 1
    }

    // We just use an asm.Type here, no need to create the MethodBType.
    val calleAsmType = asm.Type.getMethodType(callee.desc)
    val calleeParamTypes = calleAsmType.getArgumentTypes

    for(argTp <- calleeParamTypes) {
      val opc = argTp.getOpcode(ISTORE) // returns the correct xSTORE instruction for argTp
      argStores.insert(new VarInsnNode(opc, nextLocalIndex)) // "insert" is "prepend" - the last argument is on the top of the stack
      nextLocalIndex += argTp.getSize
    }

    clonedInstructions.insert(argStores)

    // label for the exit of the inlined functions. xRETURNs are replaced by GOTOs to this label.
    val postCallLabel = newLabelNode
    clonedInstructions.add(postCallLabel)
    if (sameSourceFile) {
      BytecodeUtils.previousLineNumber(callsiteInstruction) match {
        case Some(line) =>
          BytecodeUtils.nextExecutableInstruction(callsiteInstruction).flatMap(BytecodeUtils.previousLineNumber) match {
            case Some(line1) =>
              if (line == line1)
              // SD-479 code follows on the same line, restore the line number
                clonedInstructions.add(new LineNumberNode(line, postCallLabel))
            case None =>
          }
        case None =>
      }
    }

    // replace xRETURNs:
    //   - store the return value (if any)
    //   - clear the stack of the inlined method (insert DROPs)
    //   - load the return value
    //   - GOTO postCallLabel

    val returnType = calleAsmType.getReturnType
    val hasReturnValue = returnType.getSort != asm.Type.VOID
    val returnValueIndex = callsiteMethod.maxLocals + callee.maxLocals
    nextLocalIndex += returnType.getSize

    def returnValueStore(returnInstruction: AbstractInsnNode) = {
      val opc = returnInstruction.getOpcode match {
        case IRETURN => ISTORE
        case LRETURN => LSTORE
        case FRETURN => FSTORE
        case DRETURN => DSTORE
        case ARETURN => ASTORE
      }
      new VarInsnNode(opc, returnValueIndex)
    }

    // We run an interpreter to know the stack height at each xRETURN instruction and the sizes
    // of the values on the stack.
    // We don't need to worry about the method being too large for running an analysis. Callsites of
    // large methods are not added to the call graph.
    val analyzer = analyzerCache.getAny(callee, calleeDeclarationClass.internalName)

    for (originalReturn <- callee.instructions.iterator.asScala if isReturn(originalReturn)) {
      val frame = analyzer.frameAt(originalReturn)
      var stackHeight = frame.getStackSize

      val inlinedReturn = instructionMap(originalReturn)
      val returnReplacement = new InsnList

      def drop(slot: Int) = returnReplacement add getPop(frame.peekStack(slot).getSize)

      // for non-void methods, store the stack top into the return local variable
      if (hasReturnValue) {
        returnReplacement add returnValueStore(originalReturn)
        stackHeight -= 1
      }

      // drop the rest of the stack
      for (i <- 0 until stackHeight) drop(i)

      returnReplacement add new JumpInsnNode(GOTO, postCallLabel)
      clonedInstructions.insert(inlinedReturn, returnReplacement)
      clonedInstructions.remove(inlinedReturn)
    }

    // Load instruction for the return value
    if (hasReturnValue) {
      val retVarLoad = {
        val opc = returnType.getOpcode(ILOAD)
        new VarInsnNode(opc, returnValueIndex)
      }
      clonedInstructions.insert(postCallLabel, retVarLoad)
    }

    callsiteMethod.instructions.insert(callsiteInstruction, clonedInstructions)
    callsiteMethod.instructions.remove(callsiteInstruction)

    callsiteMethod.localVariables.addAll(cloneLocalVariableNodes(callee, labelsMap, callee.name, localVarShift).asJava)
    // prepend the handlers of the callee. the order of handlers matters: when an exception is thrown
    // at some instruction, the first handler guarding that instruction and having a matching exception
    // type is executed. prepending the callee's handlers makes sure to test those handlers first if
    // an exception is thrown in the inlined code.
    callsiteMethod.tryCatchBlocks.addAll(0, cloneTryCatchBlockNodes(callee, labelsMap).asJava)

    callsiteMethod.maxLocals += returnType.getSize + callee.maxLocals
    val maxStackOfInlinedCode = {
      // One slot per value is correct for long / double, see comment in the `analysis` package object.
      val numStoredArgs = calleeParamTypes.length + (if (isStaticMethod(callee)) 0 else 1)
      callee.maxStack + callsiteStackHeight - numStoredArgs
    }
    val stackHeightAtNullCheck = {
      // When adding a null check for the receiver, a DUP is inserted, which might cause a new maxStack.
      // If the callsite has other argument values than the receiver on the stack, these are pop'ed
      // and stored into locals before the null check, so in that case the maxStack doesn't grow.
      val stackSlotForNullCheck = if (!isStaticMethod(callee) && !receiverKnownNotNull && calleeParamTypes.isEmpty) 1 else 0
      callsiteStackHeight + stackSlotForNullCheck
    }

    callsiteMethod.maxStack = math.max(callsiteMethod.maxStack, math.max(stackHeightAtNullCheck, maxStackOfInlinedCode))

    lazy val callsiteLambdaBodyMethods = onIndyLambdaImplMethod(callsiteClass.internalName)(_.getOrElseUpdate(callsiteMethod, mutable.Map.empty))
    onIndyLambdaImplMethodIfPresent(calleeDeclarationClass.internalName)(methods => methods.getOrElse(callee, Nil) foreach {
      case (indy, handle) => instructionMap.get(indy) match {
        case Some(clonedIndy: InvokeDynamicInsnNode) =>
          callsiteLambdaBodyMethods(clonedIndy) = handle
        case _ =>
      }
    })

    // Don't remove the inlined instruction from callsitePositions, inlineAnnotatedCallsites so that
    // the information is still there in case the method is rolled back (UndoLog).

    if (updateCallGraph) callGraph.refresh(callsiteMethod, callsiteClass)

    // Inlining a method body can render some code unreachable, see example above in this method.
    BackendUtils.clearDceDone(callsiteMethod)
    analyzerCache.invalidate(callsiteMethod)

    instructionMap
  }

  /**
   * Check whether an inlining can be performed. This method performs tests that don't change even
   * if the body of the callee is changed by the inliner / optimizer, so it can be used early
   * (when looking at the call graph and collecting inline requests for the program).
   *
   * The tests that inspect the callee's instructions are implemented in method `canInlineBody`,
   * which is queried when performing an inline.
   *
   * @return `Some(message)` if inlining cannot be performed, `None` otherwise
   */
  def earlyCanInlineCheck(callsite: Callsite): Option[CannotInlineWarning] = {
    import callsite.{callsiteClass, callsiteMethod}
    val Right(callsiteCallee) = callsite.callee
    import callsiteCallee.{callee, calleeDeclarationClass}

    if (isSynchronizedMethod(callee)) {
      // Could be done by locking on the receiver, wrapping the inlined code in a try and unlocking
      // in finally. But it's probably not worth the effort, scala never emits synchronized methods.
      Some(SynchronizedMethod(calleeDeclarationClass.internalName, callee.name, callee.desc, callsite.isInlineAnnotated))
    } else if (isStrictfpMethod(callsiteMethod) != isStrictfpMethod(callee)) {
      Some(StrictfpMismatch(
        calleeDeclarationClass.internalName, callee.name, callee.desc, callsite.isInlineAnnotated,
        callsiteClass.internalName, callsiteMethod.name, callsiteMethod.desc))
    } else
      None
  }

  /**
   * Check whether the body of the callee contains any instructions that prevent the callsite from
   * being inlined. See also method `earlyCanInlineCheck`.
   *
   * The result of this check depends on changes to the callee method's body. For example, if the
   * callee initially invokes a private method, it cannot be inlined into a different class. If the
   * private method is inlined into the callee, inlining the callee becomes possible. Therefore
   * we don't query it while traversing the call graph and selecting callsites to inline - it might
   * rule out callsites that can be inlined just fine.
   *
   * Returns
   *  - `None` if the callsite can be inlined
   *  - `Some((message, Nil))` if there was an issue performing the access checks, for example
   *    because of a missing classfile
   *  - `Some((message, instructions))` if inlining `instructions` into the callsite method would
   *    cause an IllegalAccessError
   */
  def canInlineCallsite(callsite: Callsite): Option[CannotInlineWarning] = {
    import callsite.{callsiteClass, callsiteInstruction, callsiteMethod, callsiteStackHeight}
    val Right(callsiteCallee) = callsite.callee
    import callsiteCallee.{callee, calleeDeclarationClass}

    def calleeDesc = s"${callee.name} of type ${callee.desc} in ${calleeDeclarationClass.internalName}"
    def methodMismatch = s"Wrong method node for inlining ${textify(callsiteInstruction)}: $calleeDesc"
    assert(callsiteInstruction.name == callee.name, methodMismatch)
    assert(callsiteInstruction.desc == callee.desc, methodMismatch)
    assert(!isConstructor(callee), s"Constructors cannot be inlined: $calleeDesc")
    assert(!BytecodeUtils.isAbstractMethod(callee), s"Callee is abstract: $calleeDesc")
    assert(callsiteMethod.instructions.contains(callsiteInstruction), s"Callsite ${textify(callsiteInstruction)} is not an instruction of $calleeDesc")

    // When an exception is thrown, the stack is cleared before jumping to the handler. When
    // inlining a method that catches an exception, all values that were on the stack before the
    // call (in addition to the arguments) would be cleared (scala/bug#6157). So we don't inline methods
    // with handlers in case there are values on the stack.
    // Alternatively, we could save all stack values below the method arguments into locals, but
    // that would be inefficient: we'd need to pop all parameters, save the values, and push the
    // parameters back for the (inlined) invocation. Similarly for the result after the call.
    def stackHasNonParameters: Boolean = {
      val expectedArgs = asm.Type.getArgumentTypes(callsiteInstruction.desc).length + (callsiteInstruction.getOpcode match {
        case INVOKEVIRTUAL | INVOKESPECIAL | INVOKEINTERFACE => 1
        case INVOKESTATIC => 0
        case INVOKEDYNAMIC =>
          assertionError(s"Unexpected opcode, cannot inline ${textify(callsiteInstruction)}")
      })
      callsiteStackHeight > expectedArgs
    }

    if (codeSizeOKForInlining(callsiteMethod, callee)) {
      val warning = ResultingMethodTooLarge(
        calleeDeclarationClass.internalName, callee.name, callee.desc, callsite.isInlineAnnotated,
        callsiteClass.internalName, callsiteMethod.name, callsiteMethod.desc)
      Some(warning)
    } else if (!callee.tryCatchBlocks.isEmpty && stackHasNonParameters) {
      val warning = MethodWithHandlerCalledOnNonEmptyStack(
        calleeDeclarationClass.internalName, callee.name, callee.desc, callsite.isInlineAnnotated,
        callsiteClass.internalName, callsiteMethod.name, callsiteMethod.desc)
      Some(warning)
    } else findIllegalAccess(callee.instructions, calleeDeclarationClass, callsiteClass) match {
      case Right(Nil) =>
        None

      case Right(illegalAccessInsns) =>
        val warning = IllegalAccessInstructions(
          calleeDeclarationClass.internalName, callee.name, callee.desc, callsite.isInlineAnnotated,
          callsiteClass.internalName, illegalAccessInsns)
        Some(warning)

      case Left((illegalAccessIns, cause)) =>
        val warning = IllegalAccessCheckFailed(
          calleeDeclarationClass.internalName, callee.name, callee.desc, callsite.isInlineAnnotated,
          callsiteClass.internalName, illegalAccessIns, cause)
        Some(warning)
    }
  }

  /**
   * Check if a type is accessible to some class, as defined in JVMS 5.4.4.
   *  (A1) C is public
   *  (A2) C and D are members of the same run-time package
   */
  def classIsAccessible(accessed: BType, from: ClassBType): Either[OptimizerWarning, Boolean] = (accessed: @unchecked) match {
    // TODO: A2 requires "same run-time package", which seems to be package + classloader (JVMS 5.3.). is the below ok?
    case c: ClassBType     => c.isPublic.map(_ || c.packageInternalName == from.packageInternalName)
    case a: ArrayBType     => classIsAccessible(a.elementType, from)
    case _: PrimitiveBType => Right(true)
  }

  /**
   * Check if a member reference is accessible from the [[destinationClass]], as defined in the
   * JVMS 5.4.4. Note that the class name in a field / method reference is not necessarily the
   * class in which the member is declared:
   *
   *   class A { def f = 0 }; class B extends A { f }
   *
   * The INVOKEVIRTUAL instruction uses a method reference "B.f ()I". Therefore this method has
   * two parameters:
   *
   * @param memberDeclClass The class in which the member is declared (A)
   * @param memberRefClass  The class used in the member reference (B)
   *
   * (B0) JVMS 5.4.3.2 / 5.4.3.3: when resolving a member of class C in D, the class C is resolved
   * first. According to 5.4.3.1, this requires C to be accessible in D.
   *
   * JVMS 5.4.4 summary: A field or method R is accessible to a class D (destinationClass) iff
   *  (B1) R is public
   *  (B2) R is protected, declared in C (memberDeclClass) and D is a subclass of C.
   *       If R is not static, R must contain a symbolic reference to a class T (memberRefClass),
   *       such that T is either a subclass of D, a superclass of D, or D itself.
   *       Also (P) needs to be satisfied.
   *  (B3) R is either protected or has default access and declared by a class in the same
   *       run-time package as D.
   *       If R is protected, also (P) needs to be satisfied.
   *  (B4) R is private and is declared in D.
   *
   *  (P) When accessing a protected instance member, the target object on the stack (the receiver)
   *      has to be a subtype of D (destinationClass). This is enforced by classfile verification
   *      (https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.10.1.8).
   *
   * TODO: we cannot currently implement (P) because we don't have the necessary information
   * available. Once we have a type propagation analysis implemented, we can extract the receiver
   * type from there (https://github.com/scala-opt/scala/issues/13).
   */
  def memberIsAccessible(memberFlags: Int, memberDeclClass: ClassBType, memberRefClass: ClassBType, from: ClassBType): Either[OptimizerWarning, Boolean] = {
    // TODO: B3 requires "same run-time package", which seems to be package + classloader (JVMS 5.3.). is the below ok?
    def samePackageAsDestination = memberDeclClass.packageInternalName == from.packageInternalName
    def targetObjectConformsToDestinationClass = false // needs type propagation analysis, see above

    def memberIsAccessibleImpl = {
      val key = (ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE) & memberFlags
      key match {
        case ACC_PUBLIC => // B1
          Right(true)

        case ACC_PROTECTED => // B2
          val isStatic = (ACC_STATIC & memberFlags) != 0
          tryEither {
            val condB2 = from.isSubtypeOf(memberDeclClass).orThrow && {
              isStatic || memberRefClass.isSubtypeOf(from).orThrow || from.isSubtypeOf(memberRefClass).orThrow
            }
            Right(
              (condB2 || samePackageAsDestination /* B3 (protected) */) &&
              (isStatic || targetObjectConformsToDestinationClass) // (P)
            )
          }

        case 0 => // B3 (default access)
          Right(samePackageAsDestination)

        case ACC_PRIVATE => // B4
          Right(memberDeclClass == from)
      }
    }

    classIsAccessible(memberDeclClass, from) match { // B0
      case Right(true) => memberIsAccessibleImpl
      case r => r
    }
  }

  /**
   * Returns
   *   - `Right(Nil)` if all instructions can be safely inlined
   *   - `Right(insns)` if inlining any of `insns` would cause a [[java.lang.IllegalAccessError]]
   *     when inlined into the `destinationClass`
   *   - `Left((insn, warning))` if validity of some instruction could not be checked because an
   *     error occurred
   */
  def findIllegalAccess(instructions: InsnList, calleeDeclarationClass: ClassBType, destinationClass: ClassBType): Either[(AbstractInsnNode, OptimizerWarning), List[AbstractInsnNode]] = {
    /**
     * Check if `instruction` can be transplanted to `destinationClass`.
     *
     * If the instruction references a class, method or field that cannot be found in the
     * byteCodeRepository, it is considered as not legal. This is known to happen in mixed
     * compilation: for Java classes there is no classfile that could be parsed, nor does the
     * compiler generate any bytecode.
     *
     * Returns a warning message describing the problem if checking the legality for the instruction
     * failed.
     */
    def isLegal(instruction: AbstractInsnNode): Either[OptimizerWarning, Boolean] = instruction match {
      case ti: TypeInsnNode  =>
        // NEW, ANEWARRAY, CHECKCAST or INSTANCEOF. For these instructions, the reference
        // "must be a symbolic reference to a class, array, or interface type" (JVMS 6), so
        // it can be an internal name, or a full array descriptor.
        classIsAccessible(bTypeForDescriptorOrInternalNameFromClassfile(ti.desc), destinationClass)

      case ma: MultiANewArrayInsnNode =>
        // "a symbolic reference to a class, array, or interface type"
        classIsAccessible(bTypeForDescriptorOrInternalNameFromClassfile(ma.desc), destinationClass)

      case fi: FieldInsnNode =>
        val fieldRefClass = classBTypeFromParsedClassfile(fi.owner)
        for {
          (fieldNode, fieldDeclClassNode) <- byteCodeRepository.fieldNode(fieldRefClass.internalName, fi.name, fi.desc): Either[OptimizerWarning, (FieldNode, InternalName)]
          fieldDeclClass                  =  classBTypeFromParsedClassfile(fieldDeclClassNode)
          res                             <- memberIsAccessible(fieldNode.access, fieldDeclClass, fieldRefClass, destinationClass)
        } yield {
          res
        }

      case mi: MethodInsnNode =>
        if (mi.owner.charAt(0) == '[') Right(true) // array methods are accessible
        else {
          def canInlineCall(opcode: Int, methodFlags: Int, methodDeclClass: ClassBType, methodRefClass: ClassBType): Either[OptimizerWarning, Boolean] = {
            opcode match {
              case INVOKESPECIAL if mi.name != GenBCode.INSTANCE_CONSTRUCTOR_NAME =>
                // invokespecial is used for private method calls, super calls and instance constructor calls.
                // private method and super calls can only be inlined into the same class.
                Right(destinationClass == calleeDeclarationClass)

              case _ => // INVOKEVIRTUAL, INVOKESTATIC, INVOKEINTERFACE and INVOKESPECIAL of constructors
                memberIsAccessible(methodFlags, methodDeclClass, methodRefClass, destinationClass)
            }
          }

          val methodRefClass = classBTypeFromParsedClassfile(mi.owner)
          for {
            (methodNode, methodDeclClassNode) <- byteCodeRepository.methodNode(methodRefClass.internalName, mi.name, mi.desc): Either[OptimizerWarning, (MethodNode, InternalName)]
            methodDeclClass                   =  classBTypeFromParsedClassfile(methodDeclClassNode)
            res                               <- canInlineCall(mi.getOpcode, methodNode.access, methodDeclClass, methodRefClass)
          } yield {
            res
          }
        }

      case _: InvokeDynamicInsnNode if destinationClass == calleeDeclarationClass =>
        // within the same class, any indy instruction can be inlined
         Right(true)

      // does the InvokeDynamicInsnNode call LambdaMetaFactory?
      case LambdaMetaFactoryCall(_, _, implMethod, _) =>
        // an indy instr points to a "call site specifier" (CSP) [1]
        //  - a reference to a bootstrap method [2]
        //    - bootstrap method name
        //    - references to constant arguments, which can be:
        //      - constant (string, long, int, float, double)
        //      - class
        //      - method type (without name)
        //      - method handle
        //  - a method name+type
        //
        // execution [3]
        //  - resolve the CSP, yielding the bootstrap method handle, the static args and the name+type
        //    - resolution entails accessibility checking [4]
        //  - execute the `invoke` method of the bootstrap method handle (which is signature polymorphic, check its javadoc)
        //    - the descriptor for the call is made up from the actual arguments on the stack:
        //      - the first parameters are "MethodHandles.Lookup, String, MethodType", then the types of the constant arguments,
        //      - the return type is CallSite
        //    - the values for the call are
        //      - the bootstrap method handle of the CSP is the receiver
        //      - the Lookup object for the class in which the callsite occurs (obtained as through calling MethodHandles.lookup())
        //      - the method name of the CSP
        //      - the method type of the CSP
        //      - the constants of the CSP (primitives are not boxed)
        //  - the resulting `CallSite` object
        //    - has as `type` the method type of the CSP
        //    - is popped from the operand stack
        //  - the `invokeExact` method (signature polymorphic!) of the `target` method handle of the CallSite is invoked
        //    - the method descriptor is that of the CSP
        //    - the receiver is the target of the CallSite
        //    - the other argument values are those that were on the operand stack at the indy instruction (indyLambda: the captured values)
        //
        // [1] http://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.4.10
        // [2] http://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.7.23
        // [3] http://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.invokedynamic
        // [4] http://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3

        // We cannot generically check if an `invokedynamic` instruction can be safely inlined into
        // a different class, that depends on the bootstrap method. The Lookup object passed to the
        // bootstrap method is a capability to access private members of the callsite class. We can
        // only move the invokedynamic to a new class if we know that the bootstrap method doesn't
        // use this capability for otherwise non-accessible members.
        // In the case of indyLambda, it depends on the visibility of the implMethod handle. If
        // the implMethod is public, lambdaMetaFactory doesn't use the Lookup object's extended
        // capability, and we can safely inline the instruction into a different class.

        val methodRefClass = classBTypeFromParsedClassfile(implMethod.getOwner)
        for {
          (methodNode, methodDeclClassNode) <- byteCodeRepository.methodNode(methodRefClass.internalName, implMethod.getName, implMethod.getDesc): Either[OptimizerWarning, (MethodNode, InternalName)]
          methodDeclClass                   =  classBTypeFromParsedClassfile(methodDeclClassNode)
          res                               <- memberIsAccessible(methodNode.access, methodDeclClass, methodRefClass, destinationClass)
        } yield {
          res
        }

      case _: InvokeDynamicInsnNode => Left(UnknownInvokeDynamicInstruction)

      case ci: LdcInsnNode => ci.cst match {
        case t: asm.Type => classIsAccessible(bTypeForDescriptorOrInternalNameFromClassfile(t.getInternalName), destinationClass)
        case _           => Right(true)
      }

      case _ => Right(true)
    }

    val it = instructions.iterator.asScala
    val illegalAccess = mutable.ListBuffer.empty[AbstractInsnNode]
    while (it.hasNext) {
      val i = it.next()
      isLegal(i) match {
        case Left(warning) => return Left((i, warning)) // checking isLegal for i failed
        case Right(false)  => illegalAccess += i        // an illegal instruction was found
        case _ =>
      }
    }
    Right(illegalAccess.toList)
  }
}
