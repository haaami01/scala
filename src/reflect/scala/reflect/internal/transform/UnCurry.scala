package scala
package reflect
package internal
package transform

import Flags._
import scala.collection.mutable

trait UnCurry {

  val global: SymbolTable
  import global._
  import definitions._

  /**
   * The synthetic Java vararg method symbol corresponding to a Scala vararg method
   * annotated with @varargs.
   */
  case class VarargsSymbolAttachment(varargMethod: Symbol)

  val uncurry: TypeMap = new TypeMap {
    def apply(tp: Type): Type = {
      tp match {
        case NullaryMethodType(restpe) =>
          apply(MethodType(List(), restpe))
        case DesugaredParameterType(desugaredTpe) =>
          apply(desugaredTpe)
        case _ =>
          mapOver(tp)
      }
    }
  }

  object DesugaredParameterType {
    def unapply(tpe: Type): Option[Type] = tpe match {
      case TypeRef(pre, ByNameParamClass, arg :: Nil) =>
        Some(functionType(List(), arg))
      case TypeRef(pre, RepeatedParamClass, arg :: Nil) =>
        Some(seqType(arg))
      case TypeRef(pre, JavaRepeatedParamClass, arg :: Nil) =>
        Some(arrayType(if (isUnboundedGeneric(arg)) ObjectTpe else arg))
      case _ =>
        None
      }
  }

  private val uncurryType = new TypeMap {
    def apply(tp: Type): Type = {
      tp match {
        case ClassInfoType(parents, decls, clazz) if !clazz.isJavaDefined =>
          val parents1 = parents mapConserve uncurry
          val varargOverloads = mutable.ListBuffer.empty[Symbol]

          // Not using `hasAnnotation` here because of dreaded cyclic reference errors:
          // it may happen that VarargsClass has not been initialized yet and we get here
          // while processing one of its superclasses (such as java.lang.Object). Since we
          // don't need the more precise `matches` semantics, we only check the symbol, which
          // is anyway faster and safer
          for (decl <- decls if decl.annotations.exists(_.symbol == VarargsClass)) {
            if (mexists(decl.paramss)(sym => definitions.isRepeatedParamType(sym.tpe))) {
              varargOverloads += varargForwarderSym(clazz, decl, exitingPhase(phase)(decl.info))
            }
          }
          if ((parents1 eq parents) && varargOverloads.isEmpty) tp
          else {
            val newDecls = decls.cloneScope
            varargOverloads.foreach(newDecls.enter)
            ClassInfoType(parents1, newDecls, clazz)
          } // @MAT normalize in decls??

        case PolyType(_, _) =>
          mapOver(tp)

        case _ =>
          tp
      }
    }
  }

  private def varargForwarderSym(currentClass: Symbol, origSym: Symbol, newInfo: Type): Symbol = {
    val forwSym = origSym.cloneSymbol(currentClass, VARARGS | SYNTHETIC | origSym.flags & ~DEFERRED, origSym.name.toTermName).withoutAnnotations

    // we are using `origSym.info`, which contains the type *before* the transformation
    // so we still see repeated parameter types (uncurry replaces them with Seq)
    val isRepeated = origSym.info.paramss.flatten.map(sym => definitions.isRepeatedParamType(sym.tpe))
    val oldPs = newInfo.paramss.head
    def toArrayType(tp: Type, newParam: Symbol): Type = {
      val arg = elementType(SeqClass, tp)
      val elem = if (arg.typeSymbol.isTypeParameterOrSkolem && !(arg <:< AnyRefTpe)) {
        // To prevent generation of an `Object` parameter from `Array[T]` parameter later
        // as this would crash the Java compiler which expects an `Object[]` array for varargs
        //   e.g.        def foo[T](a: Int, b: T*)
        //   becomes     def foo[T](a: Int, b: Array[Object])
        //   instead of  def foo[T](a: Int, b: Array[T]) ===> def foo[T](a: Int, b: Object)
        //
        // In order for the forwarder method to type check we need to insert a cast:
        //   def foo'[T'](a: Int, b: Array[Object]) = foo[T'](a, wrapRefArray(b).asInstanceOf[Seq[T']])
        // The target element type for that cast (T') is stored in the  TypeParamVarargsAttachment
//        val originalArg = arg.substSym(oldTps, tps)
        // Store the type parameter that was replaced by Object to emit the correct generic signature
        newParam.updateAttachment(new TypeParamVarargsAttachment(arg))
        ObjectTpe
      } else
        arg
      arrayType(elem)
    }

    foreach2(forwSym.paramss.flatten, isRepeated)((p, isRep) =>
      if (isRep) {
        p.setInfo(toArrayType(p.info, p))
      }
    )

    origSym.updateAttachment(VarargsSymbolAttachment(forwSym))
    forwSym
  }

  /** - return symbol's transformed type,
   *  - if symbol is a def parameter with transformed type T, return () => T
   *
   * @MAT: starting with this phase, the info of every symbol will be normalized
   */
  def transformInfo(sym: Symbol, tp: Type): Type =
    if (sym.isType) uncurryType(tp)
    else if ((sym hasFlag MODULE) && !sym.isStatic) { // see Fields::nonStaticModuleToMethod
      sym setFlag METHOD | STABLE
      MethodType(Nil, uncurry(tp))
    }
    else uncurry(tp)
}
