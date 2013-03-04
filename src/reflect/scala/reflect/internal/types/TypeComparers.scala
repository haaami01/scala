package scala.reflect
package internal
package types

import scala.collection.{ mutable, immutable, generic }
import generic.Clearable
import scala.ref.WeakReference
import mutable.ListBuffer
import Flags._
import scala.util.control.ControlThrowable
import scala.annotation.tailrec
import util.Statistics
import scala.runtime.ObjectRef
import util.ThreeValues._
import Variance._

trait TypeComparers {
  self: SymbolTable =>
  import definitions._

  def isSubType(tp1: Type, tp2: Type): Boolean = isSubType(tp1, tp2, AnyDepth)

  def isSubType(tp1: Type, tp2: Type, depth: Int): Boolean = try {
    subsametypeRecursions += 1

    //OPT cutdown on Function0 allocation
    //was:
    //    undoLog undoUnless { // if subtype test fails, it should not affect constraints on typevars
    //      if (subsametypeRecursions >= LogPendingSubTypesThreshold) {
    //        val p = new SubTypePair(tp1, tp2)
    //        if (pendingSubTypes(p))
    //          false
    //        else
    //          try {
    //            pendingSubTypes += p
    //            isSubType2(tp1, tp2, depth)
    //          } finally {
    //            pendingSubTypes -= p
    //          }
    //      } else {
    //        isSubType2(tp1, tp2, depth)
    //      }
    //    }

    undoLog.lock()
    try {
      val before = undoLog.log
      var result = false

      try result = { // if subtype test fails, it should not affect constraints on typevars
        if (subsametypeRecursions >= LogPendingSubTypesThreshold) {
          val p = new SubTypePair(tp1, tp2)
          if (pendingSubTypes(p))
            false
          else
            try {
              pendingSubTypes += p
              isSubType2(tp1, tp2, depth)
            } finally {
              pendingSubTypes -= p
            }
        } else {
          isSubType2(tp1, tp2, depth)
        }
      } finally if (!result) undoLog.undoTo(before)

      result
    } finally undoLog.unlock()
  } finally {
    subsametypeRecursions -= 1
    // XXX AM TODO: figure out when it is safe and needed to clear the log -- the commented approach below is too eager (it breaks #3281, #3866)
    // it doesn't help to keep separate recursion counts for the three methods that now share it
    // if (subsametypeRecursions == 0) undoLog.clear()
  }

  /** Does type `tp1` conform to `tp2`? */
  /*TODO private*/ def isSubType2(tp1: Type, tp2: Type, depth: Int): Boolean = {
    if ((tp1 eq tp2) || isErrorOrWildcard(tp1) || isErrorOrWildcard(tp2)) return true
    if ((tp1 eq NoType) || (tp2 eq NoType)) return false
    if (tp1 eq NoPrefix) return (tp2 eq NoPrefix) || tp2.typeSymbol.isPackageClass // !! I do not see how the "isPackageClass" would be warranted by the spec
    if (tp2 eq NoPrefix) return tp1.typeSymbol.isPackageClass
    if (isSingleType(tp1) && isSingleType(tp2) || isConstantType(tp1) && isConstantType(tp2)) return tp1 =:= tp2
    if (tp1.isHigherKinded || tp2.isHigherKinded) return isHKSubType0(tp1, tp2, depth)

    /** First try, on the right:
      *   - unwrap Annotated types, BoundedWildcardTypes,
      *   - bind TypeVars  on the right, if lhs is not Annotated nor BoundedWildcard
      *   - handle common cases for first-kind TypeRefs on both sides as a fast path.
      */
    def firstTry = tp2 match {
      // fast path: two typerefs, none of them HK
      case tr2: TypeRef =>
        tp1 match {
          case tr1: TypeRef =>
            val sym1 = tr1.sym
            val sym2 = tr2.sym
            val pre1 = tr1.pre
            val pre2 = tr2.pre
            (((if (sym1 == sym2) phase.erasedTypes || sym1.owner.hasPackageFlag || isSubType(pre1, pre2, depth)
            else (sym1.name == sym2.name && !sym1.isModuleClass && !sym2.isModuleClass &&
              (isUnifiable(pre1, pre2) ||
                isSameSpecializedSkolem(sym1, sym2, pre1, pre2) ||
                sym2.isAbstractType && isSubPre(pre1, pre2, sym2)))) &&
              isSubArgs(tr1.args, tr2.args, sym1.typeParams, depth))
              ||
              sym2.isClass && {
                val base = tr1 baseType sym2
                (base ne tr1) && isSubType(base, tr2, depth)
              }
              ||
              thirdTryRef(tr1, tr2))
          case _ =>
            secondTry
        }
      case AnnotatedType(_, _, _) =>
        isSubType(tp1.withoutAnnotations, tp2.withoutAnnotations, depth) &&
          annotationsConform(tp1, tp2)
      case BoundedWildcardType(bounds) =>
        isSubType(tp1, bounds.hi, depth)
      case tv2 @ TypeVar(_, constr2) =>
        tp1 match {
          case AnnotatedType(_, _, _) | BoundedWildcardType(_) =>
            secondTry
          case _ =>
            tv2.registerBound(tp1, true)
        }
      case _ =>
        secondTry
    }

    /** Second try, on the left:
      *   - unwrap AnnotatedTypes, BoundedWildcardTypes,
      *   - bind typevars,
      *   - handle existential types by skolemization.
      */
    def secondTry = tp1 match {
      case AnnotatedType(_, _, _) =>
        isSubType(tp1.withoutAnnotations, tp2.withoutAnnotations, depth) &&
          annotationsConform(tp1, tp2)
      case BoundedWildcardType(bounds) =>
        isSubType(tp1.bounds.lo, tp2, depth)
      case tv @ TypeVar(_,_) =>
        tv.registerBound(tp2, false)
      case ExistentialType(_, _) =>
        try {
          skolemizationLevel += 1
          isSubType(tp1.skolemizeExistential, tp2, depth)
        } finally {
          skolemizationLevel -= 1
        }
      case _ =>
        thirdTry
    }

    def thirdTryRef(tp1: Type, tp2: TypeRef): Boolean = {
      val sym2 = tp2.sym
      sym2 match {
        case NotNullClass => tp1.isNotNull
        case SingletonClass => tp1.isStable || fourthTry
        case _: ClassSymbol =>
          if (isRawType(tp2))
            isSubType(tp1, rawToExistential(tp2), depth)
          else if (sym2.name == tpnme.REFINE_CLASS_NAME)
            isSubType(tp1, sym2.info, depth)
          else
            fourthTry
        case _: TypeSymbol =>
          if (sym2 hasFlag DEFERRED) {
            val tp2a = tp2.bounds.lo
            isDifferentTypeConstructor(tp2, tp2a) &&
              isSubType(tp1, tp2a, depth) ||
              fourthTry
          } else {
            isSubType(tp1.normalize, tp2.normalize, depth)
          }
        case _ =>
          fourthTry
      }
    }

    /** Third try, on the right:
      *   - decompose refined types.
      *   - handle typerefs, existentials, and notnull types.
      *   - handle left+right method types, polytypes, typebounds
      */
    def thirdTry = tp2 match {
      case tr2: TypeRef =>
        thirdTryRef(tp1, tr2)
      case rt2: RefinedType =>
        (rt2.parents forall (isSubType(tp1, _, depth))) &&
          (rt2.decls forall (specializesSym(tp1, _, depth)))
      case et2: ExistentialType =>
        et2.withTypeVars(isSubType(tp1, _, depth), depth) || fourthTry
      case nn2: NotNullType =>
        tp1.isNotNull && isSubType(tp1, nn2.underlying, depth)
      case mt2: MethodType =>
        tp1 match {
          case mt1 @ MethodType(params1, res1) =>
            val params2 = mt2.params
            val res2 = mt2.resultType
            (sameLength(params1, params2) &&
              mt1.isImplicit == mt2.isImplicit &&
              matchingParams(params1, params2, mt1.isJava, mt2.isJava) &&
              isSubType(res1.substSym(params1, params2), res2, depth))
          // TODO: if mt1.params.isEmpty, consider NullaryMethodType?
          case _ =>
            false
        }
      case pt2 @ NullaryMethodType(_) =>
        tp1 match {
          // TODO: consider MethodType mt for which mt.params.isEmpty??
          case pt1 @ NullaryMethodType(_) =>
            isSubType(pt1.resultType, pt2.resultType, depth)
          case _ =>
            false
        }
      case TypeBounds(lo2, hi2) =>
        tp1 match {
          case TypeBounds(lo1, hi1) =>
            isSubType(lo2, lo1, depth) && isSubType(hi1, hi2, depth)
          case _ =>
            false
        }
      case _ =>
        fourthTry
    }

    /** Fourth try, on the left:
      *   - handle typerefs, refined types, notnull and singleton types.
      */
    def fourthTry = tp1 match {
      case tr1 @ TypeRef(pre1, sym1, _) =>
        sym1 match {
          case NothingClass => true
          case NullClass =>
            tp2 match {
              case TypeRef(_, sym2, _) =>
                containsNull(sym2)
              case _ =>
                isSingleType(tp2) && isSubType(tp1, tp2.widen, depth)
            }
          case _: ClassSymbol =>
            if (isRawType(tp1))
              isSubType(rawToExistential(tp1), tp2, depth)
            else if (sym1.isModuleClass) tp2 match {
              case SingleType(pre2, sym2) => equalSymsAndPrefixes(sym1.sourceModule, pre1, sym2, pre2)
              case _                      => false
            }
            else if (sym1.isRefinementClass)
              isSubType(sym1.info, tp2, depth)
            else false

          case _: TypeSymbol =>
            if (sym1 hasFlag DEFERRED) {
              val tp1a = tp1.bounds.hi
              isDifferentTypeConstructor(tp1, tp1a) && isSubType(tp1a, tp2, depth)
            } else {
              isSubType(tp1.normalize, tp2.normalize, depth)
            }
          case _ =>
            false
        }
      case RefinedType(parents1, _) =>
        parents1 exists (isSubType(_, tp2, depth))
      case _: SingletonType | _: NotNullType =>
        isSubType(tp1.underlying, tp2, depth)
      case _ =>
        false
    }

    firstTry
  }

}
