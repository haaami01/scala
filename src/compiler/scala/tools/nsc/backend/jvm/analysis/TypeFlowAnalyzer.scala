package scala.tools.nsc
package backend.jvm
package analysis

import scala.tools.asm.Type
import scala.tools.asm.tree.MethodNode
import scala.tools.asm.tree.analysis.{Analyzer, BasicInterpreter, BasicValue}
import scala.tools.nsc.backend.jvm.BTypes.InternalName

abstract class TypeFlowInterpreter extends BasicInterpreter(scala.tools.asm.Opcodes.ASM7_EXPERIMENTAL) {
  override def newValue(tp: Type) = {
    if (tp == null) super.newValue(tp)
    else if (isRef(tp)) new BasicValue(tp)
    else super.newValue(tp)
  }

  def isRef(tp: Type) = tp != null && (tp.getSort match {
    case Type.OBJECT | Type.ARRAY => true
    case _ => false
  })

  def refLub(a: BasicValue, b: BasicValue): BasicValue

  override def merge(a: BasicValue, b: BasicValue): BasicValue = {
    if (a == b) a
    else if (isRef(a.getType) && isRef(b.getType)) refLub(a, b)
    else BasicValue.UNINITIALIZED_VALUE
  }
}

/**
 * A [[TypeFlowInterpreter]] which collapses LUBs of non-equal reference types to Object.
 * This could be made more precise by looking up ClassBTypes for the two reference types and using
 * the `jvmWiseLUB` method.
 */
class NonLubbingTypeFlowInterpreter extends TypeFlowInterpreter {
  def refLub(a: BasicValue, b: BasicValue): BasicValue = BasicValue.REFERENCE_VALUE // java/lang/Object
}

class NonLubbingTypeFlowAnalyzer(methodNode: MethodNode, classInternalName: InternalName) extends AsmAnalyzer(methodNode, classInternalName, new Analyzer(new NonLubbingTypeFlowInterpreter))