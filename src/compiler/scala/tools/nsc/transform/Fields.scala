/*  NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author
 */

package scala.tools.nsc
package transform

import scala.annotation.tailrec
import symtab.Flags._


/** Lift rhs out of vals, synthesize accessors and field for each (strict) val owned by a class.
  *
  * For traits:
  *
  *   - Namers translates a definition `val x = rhs` into a getter `def x = rhs`
  *   - This phase moves `rhs` into a block nested under the template's local dummy.
  *     If the value is memoized (stored), the block's final expression assigns the value to the val,
  *     and the val itself is left without a rhs.
  *     (Dotty uses a method `x$compute` instead of a block, which is used in the constructor to initialize the val.
  *      It also unifies strict and lazy vals, in that the RHS is lifted to a method for both.)
  *     If the value of the rhs is a literal or unit, it is not stored and the final expression of the block is ().
  *     The val then retains this statically known value as its rhs, with its side-effects still lifted to the block.
  *   - This phases also synthesizes accessors and fields for any vals mixed into a non-trait class.
  *   - Constructors moves the expressions in the template into the constructor,
  *     which means it will initialize the values defined in this template.
  *
  * Runs before erasure (to get bridges), but before lambdalift/flatten, so nested functions/definitions must be considered.
  */
abstract class Fields extends InfoTransform with ast.TreeDSL with TypingTransformers {

  import global._
  import definitions._

  /** the following two members override abstract members in Transform */
  val phaseName: String = "fields"

  protected def newTransformer(unit: CompilationUnit): Transformer = new FieldsTransformer(unit)
  override def transformInfo(sym: Symbol, tp: Type): Type =
    if (sym.isJavaDefined || sym.isClass && (sym hasFlag SYNTHESIZE_IMPL_IN_SUBCLASS)) tp
    else {
      // only synthesize members once! TODO: don't overload this flag...
      if (sym.isClass) sym setFlag SYNTHESIZE_IMPL_IN_SUBCLASS
      synthFieldsAndAccessors(tp)
    }


  // we leave lazy vars/accessors and early-init vals alone for now
  private def excludedAccessorOrFieldByFlags(statSym: Symbol): Boolean = statSym hasFlag LAZY | PRESUPER

  // used for internal communication between info and tree transform of this phase -- not pickled, not in initialflags
  override def phaseNewFlags: Long = NEEDS_TREES | OVERRIDDEN_TRAIT_SETTER

  final val TRAIT_SETTER_FLAGS = NEEDS_TREES | DEFERRED

  private def accessorImplementedInSubclass(accessor: Symbol) = accessor hasAllFlags (ACCESSOR | SYNTHESIZE_IMPL_IN_SUBCLASS)

  private def concreteOrSynthImpl(sym: Symbol): Boolean = !(sym hasFlag DEFERRED) || (sym hasFlag SYNTHESIZE_IMPL_IN_SUBCLASS)

  private def setTraitAccessorFlags(accessor: Symbol): Unit =
    accessor setFlag lateDEFERRED | SYNTHESIZE_IMPL_IN_SUBCLASS

  private def setClonedTraitSetterFlags(clazz: Symbol, correspondingGetter: Symbol, cloneInSubclass: Symbol): Unit = {
    val overridden = isOverriddenAccessor(correspondingGetter, clazz)
    if (overridden) cloneInSubclass setFlag OVERRIDDEN_TRAIT_SETTER
    else if (correspondingGetter.isEffectivelyFinal) cloneInSubclass setFlag FINAL
  }

  private def setClonedAccessorFlags(orig: Symbol, cloneInSubclass: Symbol): Unit =
    cloneInSubclass setFlag NEEDS_TREES resetFlag DEFERRED | lateDEFERRED | SYNTHESIZE_IMPL_IN_SUBCLASS

  private def setFieldFlags(accessor: Symbol, fieldInSubclass: TermSymbol): Unit =
    fieldInSubclass setFlag ( NEEDS_TREES |
          PrivateLocal
        | (accessor getFlag MUTABLE | LAZY)
        | (if (accessor hasFlag STABLE) 0 else MUTABLE)
      )



  private def isOverriddenAccessor(member: Symbol, site: Symbol): Boolean = {
    val pre = site.thisType
    @tailrec def loop (bcs: List[Symbol]): Boolean = {
//      println(s"checking ${bcs.head} for member overriding $member (of ${member.owner})")
      bcs.nonEmpty && bcs.head != member.owner && (matchingAccessor(pre, member, bcs.head) != NoSymbol || loop(bcs.tail))
    }

    member.exists && loop(site.info.baseClasses)
  }


  def matchingAccessor(pre: Type, member: Symbol, clazz: Symbol) =  {
    val res = member.matchingSymbol(clazz, pre) filter (sym => (sym hasFlag ACCESSOR) && concreteOrSynthImpl(sym))
//    if (res != NoSymbol) println(s"matching accessor for $member in $clazz = $res (under $pre)")
//    else println(s"no matching accessor for $member in $clazz (under $pre) among ${clazz.info.decls}")
    res
  }



  class FieldMemoization(accessorOrField: Symbol, site: Symbol) {
    private val tp = fieldTypeOfAccessorIn(accessorOrField, site.thisType)
    val memoized = !tp.isInstanceOf[ConstantType]

    // do not assign to field
    // getter for Unit-typed val, or setter for var that isn't stored (of type Unit)
    val effectOnly = isUnitType(tp) || (!site.isTrait && accessorOrField.isSetter && !memoized)

    val effectful = memoized || effectOnly

    val needsField = !effectOnly && memoized

    //    println(s"fieldMemoizationIn $sym $site = $tp")
    def assignSym: Symbol =
      if (effectOnly) NoSymbol
      else if (accessorOrField hasFlag ACCESSOR) accessorOrField.setterIn(site)
      else accessorOrField
  }

  private def fieldTypeForGetterIn(getter: Symbol, pre: Type): Type = getter.info.finalResultType.asSeenFrom(pre, getter.owner)
  private def fieldTypeForSetterIn(setter: Symbol, pre: Type): Type = setter.info.paramTypes.head.asSeenFrom(pre, setter.owner)

  // TODO: is there a more elegant way?
  def fieldTypeOfAccessorIn(accessor: Symbol, pre: Type) =
    if (accessor.isSetter) fieldTypeForSetterIn(accessor, pre)
    else fieldTypeForGetterIn(accessor, pre)


  // Constant/unit typed vals are not memoized (their value is so cheap it doesn't make sense to store it in a field)
  // for a unit-typed getter, we perform the effect at the appropriate time (constructor for eager ones, lzyCompute for lazy),
  // and have the getter just return Unit (who does that!?)
  // NOTE: this only considers type, filter on flags first!
  def fieldMemoizationIn(accessorOrField: Symbol, site: Symbol) = new FieldMemoization(accessorOrField, site)



  private object synthFieldsAndAccessors extends TypeMap {
    private def newTraitSetter(getter: Symbol, clazz: Symbol) = {
      // Add setter for an immutable, memoizing getter
      // (can't emit during namers because we don't yet know whether it's going to be memoized or not)
      // TODO: any flags to inherit from getter??? (probably not -- certainly exclude: stable, override, implicit, private, local)
      // TODO: stable/mutable? (we need to access the setter from the init method, so it needs to be in the interface)
      // TODO: annotations?
      // TODO: ARTIFACT or SYNTHETIC?? neither?
      // protected, because implemented by subclass, never used outside of hierarchy
      val setterFlags = (getter.flags & ~(STABLE | PrivateLocal | OVERRIDE | IMPLICIT | FINAL)) | MUTABLE | ACCESSOR | TRAIT_SETTER_FLAGS
      val setterName = nme.expandedSetterName(getter.name.setterName, clazz)
      val setter = clazz.newMethod(setterName, getter.pos.focus, setterFlags)
      val fieldTp = fieldTypeForGetterIn(getter, clazz.thisType)

//      println(s"newTraitSetter in $clazz for $getter = $setterName : $fieldTp")

      setter setInfo MethodType(List(setter.newSyntheticValueParam(fieldTp)), UnitTpe)
      setter
    }

    def apply(tp0: Type): Type = mapOver(tp0) match {
      // TODO: make less destructive (name changes, decl additions, flag setting --
      // none of this is actually undone when travelling back in time using atPhase)
      case tp@ClassInfoType(parents, decls, clazz) if clazz.isTrait =>
        val newSetters = collection.mutable.ListBuffer[Symbol]()
        val origDecls = decls.toList

        // strict, memoized accessors will receive an implementation in first real class to extend this trait
        origDecls.foreach { accessor => if (accessor hasFlag ACCESSOR) {
          // check flags before calling makeNotPrivate
          val memoizedGetter = !(accessor hasFlag (DEFERRED | LAZY)) && fieldMemoizationIn(accessor, clazz).needsField

          // destructively mangle accessor's name (which may cause rehashing of decls), also sets flags
          if (accessor hasFlag PRIVATE) accessor makeNotPrivate clazz

          // Need to mark as notPROTECTED, so that it's carried over to the synthesized member in subclasses,
          // since the trait member will receive this flag later in ExplicitOuter, but the synthetic subclass member will not.
          // If we don't add notPROTECTED to the synthesized one, the member will not be seen as overriding the trait member.
          // Therefore, addForwarders's call to membersBasedOnFlags would see the deferred member in the trait,
          // instead of the concrete (desired) one in the class
          if (accessor hasFlag PROTECTED) accessor setFlag notPROTECTED

          // must not reset LOCAL, as we must maintain protected[this]ness to allow that variance hole
          // (not sure why this only problem only arose when we started setting the notPROTECTED flag)

          // derive trait setter after calling makeNotPrivate (so that names are mangled consistently)
          if (memoizedGetter) {
            setTraitAccessorFlags(accessor)

            if (accessor hasFlag STABLE) // TODO: check isGetter?
              newSetters += newTraitSetter(accessor, clazz)
          }
        }}

        if (newSetters.nonEmpty) {
//          println(s"newSetters for $clazz = $newSetters")
          val newDecls = newScope
          origDecls  foreach newDecls.enter
          newSetters foreach newDecls.enter
          ClassInfoType(parents, newDecls, clazz)
        } else tp

      // mix in fields & accessors for all mixed in traits

      case tp@ClassInfoType(parents, oldDecls, clazz) if !clazz.isPackageClass =>
        val site = clazz.thisType
        // TODO (1): improve logic below, which is used to avoid mixing in anything that would result in an error in refchecks
        // (a reason to run after refchecks? we should run before pickler, though, I think, so that the synthesized stats are pickled)

        // TODO (2): produce the right error message for (and all combos of val/var/def keywords for the two defs):
        //   trait OneVal { val x: Int = 123 }
        //   class Conflicting extends OneVal { def x: Int = 1 }

        // Currently, this wrongly produces:
        // test/files/trait-defaults/fields.scala:2: error: overriding variable x in trait OneVal of type Int;
        //  method x needs to be a stable, immutable value
        // class Conflicting extends OneVal { def x: Int = 1 }
        // test/files/trait-defaults/fields.scala:2: error: class Conflicting needs to be abstract, since variable x in trait OneVal of type Int is not defined
        // (Note that an abstract var requires a setter in addition to the getter)
        // class Conflicting extends OneVal { def x: Int = 1 }
        // two errors found
        //
        // it should simply say we need `override` for the def in Conflicting
        //
        // the setter for the immutable val in OneVal has already been synthesized above

        // TODO (3): which error should this produce?
        //   trait OneVal[T] { val x: Int = 123 }
        //   class OverridingVal extends OneVal[Int] { val x: Int }


        val accessorsMaybeNeedingImpl = clazz.mixinClasses.flatMap { mixin =>
          // afterOwnPhase, so traits receive trait setters for vals
          afterOwnPhase { mixin.info }.decls.toList.filter(accessorImplementedInSubclass)
        }

//        println(s"mixing in $accessorsMaybeNeedingImpl from ${clazz.mixinClasses}")

        // TODO: setter conflicts?
        def accessorConflictsExistingVal(accessor: Symbol): Boolean = {
          val existingGetter = oldDecls.lookup(accessor.name.getterName)
//          println(s"$existingGetter from $accessor to ${accessor.name.getterName}")
          val tp = fieldTypeOfAccessorIn(accessor, site)
          (existingGetter ne NoSymbol) && (tp matches (site memberInfo existingGetter)) // !existingGetter.isDeferred && -- see (3)
        }

        // mixin field accessors --
        // invariant: (accessorsMaybeNeedingImpl, mixedInAccessorAndFields).zipped.forall(case (acc, clone :: _) => `clone` is clone of `acc` case _ => true)
        val mixedInAccessorAndFields = accessorsMaybeNeedingImpl map { accessor =>
          def cloneAccessor() = {
            val clonedAccessor = (accessor cloneSymbol clazz) setPos clazz.pos
            setClonedAccessorFlags(accessor, clonedAccessor)

            // if we don't cloneInfo, method argument symbols are shared between trait and subclasses --> lambalift proxy crash
            // TODO: use derive symbol variant?
            //            println(s"cloning accessor $accessor to $clazz / $clonedInfo -> $relativeInfo")
            clonedAccessor setInfo ((clazz.thisType memberType accessor) cloneInfo clonedAccessor) // accessor.info.cloneInfo(clonedAccessor).asSeenFrom(clazz.thisType, accessor.owner)
          }

          // when considering whether to mix in the trait setter, forget about conflicts -- they will be reported for the getter
          // a trait setter for an overridden val will receive a unit body in the tree transform
          if (nme.isTraitSetterName(accessor.name)) {
            val getter = accessor.getterIn(accessor.owner)
//            println(s"mixing in trait setter ${accessor.defString}: $overridden")
            val clone = cloneAccessor()

            clone filterAnnotations (ai => !ai.matches(TraitSetterAnnotationClass)) // only supposed to be set in trait

            setClonedTraitSetterFlags(clazz, getter, clone)
//            println(s"mixed in trait setter ${clone.defString}")

            List(clone)
          }
          // avoid creating early errors in case of conflicts (wait until refchecks);
          // also, skip overridden accessors contributed by supertraits (only act on the last overriding one)
          else if (accessorConflictsExistingVal(accessor) || isOverriddenAccessor(accessor, clazz)) Nil
          else if (accessor.isGetter && fieldMemoizationIn(accessor, clazz).needsField) {
            // add field if needed
            val field = clazz.newValue(accessor.localName, accessor.pos) setInfo fieldTypeForGetterIn(accessor, clazz.thisType)

            setFieldFlags(accessor, field)

            // filter getter's annotations to exclude those only meant for the field
            // we must keep them around long enough to see them here, though, when we create the field
            field setAnnotations (accessor.annotations filter AnnotationInfo.mkFilter(FieldTargetClass, defaultRetention = true))

            List(cloneAccessor(), field)
          } else List(cloneAccessor())
        }

//        println(s"new decls for $clazz: $mixedInAccessorAndFields")

        // omit fields that are not memoized, retain all other members
        def omittableField(sym: Symbol) =
          sym.isValue && !sym.isMethod &&
            fieldMemoizationIn(sym, clazz).effectOnly // TODO: not yet `needsField`, to produce same bytecode as 2.12.0-M3

        val newDecls =
          if (mixedInAccessorAndFields.isEmpty) oldDecls.filterNot(omittableField)
          else {  // must not alter `decls` directly
            // compute subst from accessors to corresponding clonedAccessors in types in newDecls
            val (_origs, _mixedins) = (accessorsMaybeNeedingImpl, mixedInAccessorAndFields).zipped.collect {
              case (traitAccessor, mixedin :: _) => (traitAccessor, mixedin)
            }.unzip

            val (origs, mixedins) = (_origs.toList, _mixedins.toList)

            val newDecls = newScope
            val enterAndSubst = { sym: Symbol => newDecls enter sym.substInfo(origs, mixedins) }

            oldDecls foreach { d => if (!omittableField(d)) enterAndSubst(d) }
            mixedInAccessorAndFields foreach { _ foreach enterAndSubst }

            newDecls
          }

//        println(s"new decls: $newDecls")

        if (newDecls eq oldDecls) tp
        else ClassInfoType(parents, newDecls, clazz)

      case tp => tp
    }
  }


  class FieldsTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {
    def mkTypedUnit(pos: Position) = localTyper.typedPos(pos)(CODE.UNIT)
    def deriveUnitDef(stat: Tree)  = deriveDefDef(stat)(_ => mkTypedUnit(stat.pos))

    // synth trees for accessors/fields and trait setters when they are mixed into a class
    def fieldsAndAccessors(templateSym: Symbol): List[ValOrDefDef] = {
      val clazz = templateSym.owner
      def fieldAccess(accessor: Symbol) = {
        val fieldName = accessor.localName
        val field = clazz.info.decl(fieldName)
        assert(field.exists, s"Field '$fieldName' not found in ${clazz.info.decls} of $clazz for $accessor")
        Select(This(clazz), field)
      }

      val accessorsAndFieldsNeedingTrees = clazz.info.decls.toList.filter(_ hasFlag NEEDS_TREES)
      accessorsAndFieldsNeedingTrees foreach (_ resetFlag NEEDS_TREES) // emitting the needed trees now

//      println(s"accessorsAndFieldsNeedingTrees: $accessorsAndFieldsNeedingTrees")
      def setterBody(setter: Symbol): Tree = {
        // trait setter in trait
        if (clazz.isTrait) EmptyTree
        // trait setter for overridden val in class
        else if (setter hasFlag OVERRIDDEN_TRAIT_SETTER) { setter resetFlag OVERRIDDEN_TRAIT_SETTER ; mkTypedUnit(setter.pos) }
        // trait val/var setter mixed into class
        else Assign(fieldAccess(setter), Ident(setter.firstParam))
      }

      accessorsAndFieldsNeedingTrees map {
        case setter if setter.isSetter   => localTyper.typedPos(setter.pos)(DefDef(setter, setterBody(setter))).asInstanceOf[DefDef]
        case getter if getter.isAccessor => localTyper.typedPos(getter.pos)(DefDef(getter, fieldAccess(getter))).asInstanceOf[DefDef]
        case field                       => assert(field.isValue && !field.isMethod, s"Expecting field, got $field")
          localTyper.typedPos(field.pos)(ValDef(field)).asInstanceOf[ValDef]
      }
    }

    private def transformStat(exprOwner: Symbol)(stat: Tree): List[Tree] = {
      val clazz = currentOwner
      val statSym = stat.symbol

      // println(s"transformStat $statSym in ${exprOwner.ownerChain}")
      //        currentRun.trackerFactory.snapshot()

      // Which symbol should own `rhs`, as the expression is lifted from `statSym` to the class's template?
      // Normally, the local dummy (`exprOwner`) is the owner for stats in the template (not `clazz`!)
      // when we get owners wrong, the following fails:
      //   - stackoverflow in uncurry when bootstrapping
      //   - compiling scala.util.Properties,
      //     where some references from the impl class are still pointing to the trait interface, not the impl class
      //   - test/files/trait-defaults/ultimate-nesting.scala,
      //     where a nested module class is separated from its module, as well as from the `settings` reference
      //     (this was due to changeOwner not changing a module's moduleClass's owner in addition to the module's owner)
      def initEffect(rhs: Tree, assignSym: Symbol) = {
        // println(s"initEffect: owner $statSym --> $exprOwner (currentOwner= $currentOwner)")
        // println(s"initEffect for $assignSym is $rhs")

        val lifted = atOwner(exprOwner)(super.transform(rhs.changeOwner(statSym -> exprOwner)))

        if (assignSym eq NoSymbol) lifted
        else localTyper.typedPos(rhs.pos) {
          val qual = Select(This(clazz), assignSym)
          if (assignSym.isSetter) Apply(qual, List(lifted))
          else Assign(qual, lifted)
        }
      }

      stat match {
        case DefDef(_, _, _, _, _, rhs) if (rhs ne EmptyTree) && (statSym hasFlag ACCESSOR) && !excludedAccessorOrFieldByFlags(statSym) =>
          val fieldMemoization = fieldMemoizationIn(statSym, clazz)
          def getterRhs(x: Tree) = if (fieldMemoization.effectOnly) mkTypedUnit(statSym.pos) else EmptyTree

          // TODO: consolidate with ValDef case
          if (clazz.isTrait) {
            // there's a synthetic setter if val is not mutable (symbol is created in info transform)
            if (fieldMemoization.effectful) deriveDefDef(stat)(getterRhs) :: initEffect(rhs, fieldMemoization.assignSym) :: Nil
            else stat :: Nil
          } else (
            // regular getter -- field will be preserved (see case ValDef)
            if (fieldMemoization.needsField) stat
            // getter for Unit-typed val, or setter for var that isn't stored (of type Unit)
            else if (fieldMemoization.effectOnly) deriveUnitDef(stat)
            // getter for constant, emit literal for its body
            else deriveDefDef(stat)(_ => gen.mkAttributedQualifier(rhs.tpe))
          ) :: Nil

        // If a val needs a field, an empty valdef and an assignment to its rhs go into the template
        case ValDef(mods, _, _, rhs) if (rhs ne EmptyTree) && !excludedAccessorOrFieldByFlags(statSym) =>
          val fieldMemoization = fieldMemoizationIn(statSym, clazz)

          if (fieldMemoization.needsField) deriveValDef(stat)(_ => EmptyTree) :: initEffect(rhs, fieldMemoization.assignSym) :: Nil
          else if (fieldMemoization.effectOnly) initEffect(rhs, NoSymbol) :: Nil // drop the val entirely -- it could not have been referenced outside accesors
          else Nil

        case tree => List(
          if (exprOwner != currentOwner && tree.isTerm) atOwner(exprOwner)(super.transform(tree))
          else super.transform(tree)
        )
      }
    }

    override def transformStats(stats: List[Tree], exprOwner: Symbol): List[Tree] =
      if (!currentOwner.isClass || currentOwner.isPackageClass || currentOwner.isInterface) super.transformStats(stats, exprOwner)
      else afterOwnPhase {
        fieldsAndAccessors(exprOwner) ++ (stats flatMap transformStat(exprOwner)) // TODO use thicket encoding of multi-tree transformStat?
      }
  }
}
