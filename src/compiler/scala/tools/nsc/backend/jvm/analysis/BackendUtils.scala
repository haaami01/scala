package scala.tools.nsc
package backend.jvm
package analysis

import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentHashMap

import scala.annotation.{switch, tailrec}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.ClassTag
import scala.reflect.internal.util.Position
import scala.tools.asm
import scala.tools.asm.Opcodes._
import scala.tools.asm.tree._
import scala.tools.asm.tree.analysis.Value
import scala.tools.asm.{Handle, Opcodes, Type}
import scala.tools.nsc.backend.jvm.BTypes._
import scala.tools.nsc.backend.jvm.GenBCode._
import scala.tools.nsc.backend.jvm.analysis.BackendUtils._
import scala.tools.nsc.backend.jvm.opt.BytecodeUtils._
import scala.util.control.{NoStackTrace, NonFatal}

/**
 * This component hosts tools and utilities used in the backend that require access to a `BTypes`
 * instance.
 *
 * TODO: move out of `analysis` package?
 */
abstract class BackendUtils extends PerRunInit {
  val postProcessor: PostProcessor

  import postProcessor.{bTypes, bTypesFromClassfile, callGraph}
  import bTypes._
  import coreBTypes._
  import frontendAccess.{compilerSettings, recordPerRunJavaMapCache}

  /**
   * Classes with indyLambda closure instantiations where the SAM type is serializable (e.g. Scala's
   * FunctionN) need a `$deserializeLambda$` method. This map contains classes for which such a
   * method has been generated. It is used during ordinary code generation, as well as during
   * inlining: when inlining an indyLambda instruction into a class, we need to make sure the class
   * has the method.
   */
  private val indyLambdaImplMethods: ConcurrentHashMap[InternalName, mutable.Map[MethodNode, mutable.Map[InvokeDynamicInsnNode, asm.Handle]]] =
    recordPerRunJavaMapCache(new ConcurrentHashMap)

  object analyzerCache {
    val enabled: LazyVar[Boolean] = perRunLazy(BackendUtils.this)(compilerSettings.optUseAnalyzerCache)

    // TODO: should remove keys where the soft references are empty
    private val cache: ConcurrentHashMap[MethodNode, SoftReference[mutable.Set[AsmAnalyzer[_]]]] =
      recordPerRunJavaMapCache(new ConcurrentHashMap)

    private def getImpl[A <: AsmAnalyzer[_]](methodNode: MethodNode, cond: AsmAnalyzer[_] => Boolean, constr: => A): A = {
      if (!enabled.get) constr
      else {
        var as: mutable.Set[AsmAnalyzer[_]] = null
        cache.compute(methodNode, (_, ref) => {
          if (ref == null) {
            as = mutable.Set.empty // keep a live reference to the set
            new SoftReference(as)
          } else {
            as = ref.get()
            if (as == null) {
              as = mutable.Set.empty
              new SoftReference(as)
            } else ref
          }
        })
        as.synchronized {
          as.find(cond) match {
            case Some(a) =>
              a.asInstanceOf[A]
            case _ =>
              val a = constr
              as += a
              a
          }
        }
      }
    }

    def get[A <: AsmAnalyzer[_] : ClassTag](methodNode: MethodNode)(constr: => A): A = {
      val c = implicitly[ClassTag[A]].runtimeClass
      getImpl[A](methodNode, _.getClass == c, constr)
    }

    def getCond(methodNode: MethodNode, cond: AsmAnalyzer[_] => Boolean)(constr: => AsmAnalyzer[_ <: Value]): AsmAnalyzer[_ <: Value] = {
      getImpl(methodNode, cond, constr)
    }

    def getAny(methodNode: MethodNode, classInternalName: InternalName): AsmAnalyzer[_ <: Value] = {
      getImpl(methodNode, _ => true, new BasicAnalyzer(methodNode, classInternalName))
    }

    def invalidate(methodNode: MethodNode): Unit = {
      if (enabled.get) cache.remove(methodNode)
    }
  }

  // unused objects created by these constructors are eliminated by pushPop
  private[this] lazy val sideEffectFreeConstructors: LazyVar[Set[(String, String)]] = perRunLazy(this) {
    val ownerDesc = (p: (InternalName, MethodNameAndType)) => (p._1, p._2.methodType.descriptor)
    primitiveBoxConstructors.map(ownerDesc).toSet ++
      srRefConstructors.map(ownerDesc) ++
      tupleClassConstructors.map(ownerDesc) ++ Set(
      (ObjectRef.internalName, MethodBType(Nil, UNIT).descriptor),
      (StringRef.internalName, MethodBType(Nil, UNIT).descriptor),
      (StringRef.internalName, MethodBType(List(StringRef), UNIT).descriptor),
      (StringRef.internalName, MethodBType(List(ArrayBType(CHAR)), UNIT).descriptor))
  }

  private[this] lazy val classesOfSideEffectFreeConstructors: LazyVar[Set[String]] = perRunLazy(this)(sideEffectFreeConstructors.get.map(_._1))

  lazy val classfileVersion: LazyVar[Int] = perRunLazy(this)(compilerSettings.target match {
    case "jvm-1.8" => asm.Opcodes.V1_8
  })


  lazy val majorVersion: LazyVar[Int] = perRunLazy(this)(classfileVersion.get & 0xFF)

  lazy val emitStackMapFrame: LazyVar[Boolean] = perRunLazy(this)(majorVersion.get >= 50)

  lazy val extraProc: LazyVar[Int] = perRunLazy(this)(
    asm.ClassWriter.COMPUTE_MAXS |
      (if (emitStackMapFrame.get) asm.ClassWriter.COMPUTE_FRAMES else 0)
  )

  /*
   * Add:
   *
   * private static Object $deserializeLambda$(SerializedLambda l) {
   *   try return indy[scala.runtime.LambdaDeserialize.bootstrap, targetMethodGroup$0](l)
   *   catch {
   *     case i: IllegalArgumentException =>
   *       try return indy[scala.runtime.LambdaDeserialize.bootstrap, targetMethodGroup$1](l)
   *       catch {
   *         case i: IllegalArgumentException =>
   *           ...
   *             return indy[scala.runtime.LambdaDeserialize.bootstrap, targetMethodGroup${NUM_GROUPS-1}](l)
   *       }
   *
   * We use invokedynamic here to enable caching within the deserializer without needing to
   * host a static field in the enclosing class. This allows us to add this method to interfaces
   * that define lambdas in default methods.
   *
   * SI-10232 we can't pass arbitrary number of method handles to the final varargs parameter of the bootstrap
   * method due to a limitation in the JVM. Instead, we emit a separate invokedynamic bytecode for each group of target
   * methods.
   */
  def addLambdaDeserialize(classNode: ClassNode, implMethods: Iterable[Handle]): Unit = {
    val cw = classNode

    // Make sure to reference the ClassBTypes of all types that are used in the code generated
    // here (e.g. java/util/Map) are initialized. Initializing a ClassBType adds it to the
    // `cachedClassBType` maps. When writing the classfile, the asm ClassWriter computes
    // stack map frames and invokes the `getCommonSuperClass` method. This method expects all
    // ClassBTypes mentioned in the source code to exist in the map.

    val serlamObjDesc = MethodBType(jliSerializedLambdaRef :: Nil, ObjectRef).descriptor
    val implMethodsArray = implMethods.toArray

    val mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC + ACC_SYNTHETIC, "$deserializeLambda$", serlamObjDesc, null, null)
    def emitLambdaDeserializeIndy(targetMethods: Seq[Handle]): Unit = {
      mv.visitVarInsn(ALOAD, 0)
      mv.visitInvokeDynamicInsn("lambdaDeserialize", serlamObjDesc, lambdaDeserializeBootstrapHandle, targetMethods: _*)
    }

    val targetMethodGroupLimit = 255 - 1 - 3 // JVM limit. See See MAX_MH_ARITY in CallSite.java
    val groups: Array[Array[Handle]] = implMethodsArray.grouped(targetMethodGroupLimit).toArray
    val numGroups = groups.length

    import scala.tools.asm.Label
    val initialLabels = Array.fill(numGroups - 1)(new Label())
    val terminalLabel = new Label
    def nextLabel(i: Int) = if (i == numGroups - 2) terminalLabel else initialLabels(i + 1)

    for ((label, i) <- initialLabels.iterator.zipWithIndex) {
      mv.visitTryCatchBlock(label, nextLabel(i), nextLabel(i), jlIllegalArgExceptionRef.internalName)
    }
    for ((label, i) <- initialLabels.iterator.zipWithIndex) {
      mv.visitLabel(label)
      emitLambdaDeserializeIndy(groups(i))
      mv.visitInsn(ARETURN)
    }
    mv.visitLabel(terminalLabel)
    emitLambdaDeserializeIndy(groups(numGroups - 1))
    mv.visitInsn(ARETURN)
  }

  /**
   * Clone the instructions in `methodNode` into a new [[InsnList]], mapping labels according to
   * the `labelMap`.
   *
   * For invocation instructions, set the callGraph.callsitePositions to the `callsitePos`.
   *
   * Returns the new instruction list and a map from old to new instructions.
   */
  def cloneInstructions(methodNode: MethodNode, labelMap: Map[LabelNode, LabelNode], callsitePos: Position, keepLineNumbers: Boolean): (InsnList, Map[AbstractInsnNode, AbstractInsnNode]) = {
    val javaLabelMap = labelMap.asJava
    val result = new InsnList
    var map = Map.empty[AbstractInsnNode, AbstractInsnNode]
    for (ins <- methodNode.instructions.iterator.asScala) {
      if (keepLineNumbers || !ins.isInstanceOf[LineNumberNode]) {
        val cloned = ins.clone(javaLabelMap)
        ins match {
          case mi: MethodInsnNode =>
            val clonedMi = cloned.asInstanceOf[MethodInsnNode]
            callGraph.callsitePositions(clonedMi) = callsitePos
            if (callGraph.inlineAnnotatedCallsites(mi))
              callGraph.inlineAnnotatedCallsites += clonedMi
            if (callGraph.noInlineAnnotatedCallsites(mi))
              callGraph.noInlineAnnotatedCallsites += clonedMi
            if (callGraph.staticallyResolvedInvokespecial(mi))
              callGraph.staticallyResolvedInvokespecial += clonedMi
          case _ =>
        }
        result add cloned
        map += ((ins, cloned))
      }
    }
    (result, map)
  }

  def getBoxedUnit: FieldInsnNode = new FieldInsnNode(GETSTATIC, srBoxedUnitRef.internalName, "UNIT", srBoxedUnitRef.descriptor)

  private val anonfunAdaptedName = """.*\$anonfun\$.*\$\d+\$adapted""".r
  def isAnonfunAdaptedMethod(method: MethodNode): Boolean = isSyntheticMethod(method) && anonfunAdaptedName.matches(method.name)

  private def primitiveAsmTypeToBType(primitiveType: Type): PrimitiveBType = (primitiveType.getSort: @switch) match {
    case Type.BOOLEAN => BOOL
    case Type.BYTE    => BYTE
    case Type.CHAR    => CHAR
    case Type.SHORT   => SHORT
    case Type.INT     => INT
    case Type.LONG    => LONG
    case Type.FLOAT   => FLOAT
    case Type.DOUBLE  => DOUBLE
    case _            => null
  }

  def isScalaBox(insn: MethodInsnNode): Boolean = {
    insn.owner == srBoxesRunTimeRef.internalName && {
      val args = Type.getArgumentTypes(insn.desc)
      args.length == 1 && (srBoxesRuntimeBoxToMethods.get(primitiveAsmTypeToBType(args(0))) match {
        case Some(MethodNameAndType(name, tp)) => name == insn.name && tp.descriptor == insn.desc
        case _ => false
      })
    }
  }

  def getScalaBox(primitiveType: Type): MethodInsnNode = {
    val bType = primitiveAsmTypeToBType(primitiveType)
    val MethodNameAndType(name, methodBType) = srBoxesRuntimeBoxToMethods(bType)
    new MethodInsnNode(INVOKESTATIC, srBoxesRunTimeRef.internalName, name, methodBType.descriptor, /*itf =*/ false)
  }

  def isScalaUnbox(insn: MethodInsnNode): Boolean = {
    insn.owner == srBoxesRunTimeRef.internalName && (srBoxesRuntimeUnboxToMethods.get(primitiveAsmTypeToBType(Type.getReturnType(insn.desc))) match {
      case Some(MethodNameAndType(name, tp)) => name == insn.name && tp.descriptor == insn.desc
      case _ => false
    })
  }

  def getScalaUnbox(primitiveType: Type): MethodInsnNode = {
    val bType = primitiveAsmTypeToBType(primitiveType)
    val MethodNameAndType(name, methodBType) = srBoxesRuntimeUnboxToMethods(bType)
    new MethodInsnNode(INVOKESTATIC, srBoxesRunTimeRef.internalName, name, methodBType.descriptor, /*itf =*/ false)
  }

  private def calleeInMap(insn: MethodInsnNode, map: Map[InternalName, MethodNameAndType]): Boolean = map.get(insn.owner) match {
    case Some(MethodNameAndType(name, tp)) => insn.name == name && insn.desc == tp.descriptor
    case _ => false
  }

  def isJavaBox(insn: MethodInsnNode): Boolean = calleeInMap(insn, javaBoxMethods)
  def isJavaUnbox(insn: MethodInsnNode): Boolean = calleeInMap(insn, javaUnboxMethods)

  def isPredefAutoBox(insn: MethodInsnNode): Boolean = {
    insn.owner == PredefRef.internalName && (predefAutoBoxMethods.get(insn.name) match {
      case Some(tp) => insn.desc == tp.descriptor
      case _ => false
    })
  }

  def isPredefAutoUnbox(insn: MethodInsnNode): Boolean = {
    insn.owner == PredefRef.internalName && (predefAutoUnboxMethods.get(insn.name) match {
      case Some(tp) => insn.desc == tp.descriptor
      case _ => false
    })
  }

  def isRefCreate(insn: MethodInsnNode): Boolean = calleeInMap(insn, srRefCreateMethods)
  def isRefZero(insn: MethodInsnNode): Boolean = calleeInMap(insn, srRefZeroMethods)

  def runtimeRefClassBoxedType(refClass: InternalName): Type = Type.getArgumentTypes(srRefCreateMethods(refClass).methodType.descriptor)(0)

  def isSideEffectFreeCall(mi: MethodInsnNode): Boolean = {
    isScalaBox(mi) || isScalaUnbox(mi) ||
      isJavaBox(mi) || // not java unbox, it may NPE
      isSideEffectFreeConstructorCall(mi) ||
      isClassTagApply(mi)
  }

  // methods that are known to return a non-null result
  def isNonNullMethodInvocation(mi: MethodInsnNode): Boolean = {
    isJavaBox(mi) || isScalaBox(mi) || isPredefAutoBox(mi) || isRefCreate(mi) || isRefZero(mi) || isClassTagApply(mi)
  }

  lazy val modulesAllowSkipInitialization: Set[InternalName] =
    if (!compilerSettings.optAllowSkipCoreModuleInit) Set.empty
    else Set(
      "scala/Predef$",
      "scala/runtime/ScalaRunTime$",
      "scala/reflect/ClassTag$",
      "scala/reflect/ManifestFactory$",
      "scala/Array$",
      "scala/collection/ArrayOps$",
      "scala/collection/StringOps$",
    ) ++ primitiveTypes.keysIterator

  def isElidableModuleLoad(insn: AbstractInsnNode, nameMatches: InternalName => Boolean): Boolean = insn match {
    case fi: FieldInsnNode =>
      fi.getOpcode == GETSTATIC &&
        nameMatches(fi.owner) &&
        fi.name == "MODULE$" &&
        fi.desc.length == fi.owner.length + 2 &&
        fi.desc.regionMatches(1, fi.owner, 0, fi.owner.length)
    case _ => false
  }

  def isPredefLoad(insn: AbstractInsnNode): Boolean = isElidableModuleLoad(insn, _ == PredefRef.internalName)

  def isPrimitiveBoxConstructor(insn: MethodInsnNode): Boolean = calleeInMap(insn, primitiveBoxConstructors)
  def isRuntimeRefConstructor(insn: MethodInsnNode): Boolean = calleeInMap(insn, srRefConstructors)
  def isTupleConstructor(insn: MethodInsnNode): Boolean = calleeInMap(insn, tupleClassConstructors)


  def isSideEffectFreeConstructorCall(insn: MethodInsnNode): Boolean = {
    insn.name == INSTANCE_CONSTRUCTOR_NAME && sideEffectFreeConstructors.get((insn.owner, insn.desc))
  }

  def isNewForSideEffectFreeConstructor(insn: AbstractInsnNode): Boolean = {
    insn.getOpcode == NEW && {
      val ti = insn.asInstanceOf[TypeInsnNode]
      classesOfSideEffectFreeConstructors.get.contains(ti.desc)
    }
  }

  def isBoxedUnit(insn: AbstractInsnNode): Boolean = {
    insn.getOpcode == GETSTATIC && {
      val fi = insn.asInstanceOf[FieldInsnNode]
      fi.owner == srBoxedUnitRef.internalName && fi.name == "UNIT" && fi.desc == srBoxedUnitRef.descriptor
    }
  }

  def isTraitSuperAccessor(method: MethodNode, owner: ClassBType): Boolean = {
    owner.isInterface.get &&
      isSyntheticMethod(method) &&
      method.name.endsWith("$") &&
      isStaticMethod(method) &&
      findSingleCall(method, mi => mi.itf && mi.getOpcode == INVOKESPECIAL && mi.name + "$" == method.name).nonEmpty
  }

  def isMixinForwarder(method: MethodNode, owner: ClassBType): Boolean = {
    !owner.isInterface.get &&
      // isSyntheticMethod(method) && // mixin forwarders are not synthetic it seems
      !isStaticMethod(method) &&
      findSingleCall(method, mi => mi.itf && mi.getOpcode == INVOKESTATIC && mi.name == method.name + "$").nonEmpty
  }

  def isTraitSuperAccessorOrMixinForwarder(method: MethodNode, owner: ClassBType): Boolean = {
    isTraitSuperAccessor(method, owner) || isMixinForwarder(method, owner)
  }

  private class Collector extends NestedClassesCollector[ClassBType] {
    def declaredNestedClasses(internalName: InternalName): List[ClassBType] =
      bTypesFromClassfile.classBTypeFromParsedClassfile(internalName).info.get.nestedClasses.force

    def getClassIfNested(internalName: InternalName): Option[ClassBType] = {
      val c = bTypesFromClassfile.classBTypeFromParsedClassfile(internalName)
      if (c.isNestedClass.get) Some(c) else None
    }

    def raiseError(msg: String, sig: String, e: Option[Throwable]): Unit = {
      // don't crash on invalid generic signatures
    }
  }
  /**
   * Visit the class node and collect all referenced nested classes.
   */
  def collectNestedClasses(classNode: ClassNode): List[ClassBType] = {
    val c = new Collector
    c.visit(classNode)
    c.innerClasses.toList
  }

  /*
   * Populates the InnerClasses JVM attribute with `refedInnerClasses`. See also the doc on inner
   * classes in BTypes.scala.
   *
   * `refedInnerClasses` may contain duplicates, need not contain the enclosing inner classes of
   * each inner class it lists (those are looked up and included).
   *
   * This method serializes in the InnerClasses JVM attribute in an appropriate order, not
   * necessarily that given by `refedInnerClasses`.
   *
   * can-multi-thread
   */
  final def addInnerClasses(jclass: asm.ClassVisitor, refedInnerClasses: List[ClassBType]): Unit = {
    val allNestedClasses = refedInnerClasses.flatMap(_.enclosingNestedClassesChain.get).distinct

    // sorting ensures nested classes are listed after their enclosing class thus satisfying the Eclipse Java compiler
    for (nestedClass <- allNestedClasses.sortBy(_.internalName.toString)) {
      // Extract the innerClassEntry - we know it exists, enclosingNestedClassesChain only returns nested classes.
      val Some(e) = nestedClass.innerClassAttributeEntry.get
      jclass.visitInnerClass(e.name, e.outerName, e.innerName, e.flags)
    }
  }

  def onIndyLambdaImplMethodIfPresent[T](hostClass: InternalName)(action: mutable.Map[MethodNode, mutable.Map[InvokeDynamicInsnNode, asm.Handle]] => T): Option[T] =
    indyLambdaImplMethods.get(hostClass) match {
      case null => None
      case methods => Some(methods.synchronized(action(methods)))
    }

  def onIndyLambdaImplMethod[T](hostClass: InternalName)(action: mutable.Map[MethodNode, mutable.Map[InvokeDynamicInsnNode, asm.Handle]] => T): T = {
    val methods = indyLambdaImplMethods.computeIfAbsent(hostClass, _ => mutable.Map.empty)
    methods.synchronized(action(methods))
  }

  def addIndyLambdaImplMethod(hostClass: InternalName, method: MethodNode, indy: InvokeDynamicInsnNode, handle: asm.Handle): Unit = {
    onIndyLambdaImplMethod(hostClass)(_.getOrElseUpdate(method, mutable.Map.empty)(indy) = handle)
  }

  def removeIndyLambdaImplMethod(hostClass: InternalName, method: MethodNode, indy: InvokeDynamicInsnNode): Unit = {
    onIndyLambdaImplMethodIfPresent(hostClass)(_.get(method).foreach(_.remove(indy)))
  }

  /**
   * The methods used as lambda bodies for IndyLambda instructions within `hostClass`. Note that
   * the methods are not necessarily defined within the `hostClass` (when an IndyLambda is inlined
   * into a different class).
   */
  def indyLambdaBodyMethods(hostClass: InternalName): mutable.SortedSet[Handle] = {
    val res = mutable.TreeSet.empty[Handle](handleOrdering)
    onIndyLambdaImplMethodIfPresent(hostClass)(methods => res addAll methods.valuesIterator.flatMap(_.valuesIterator))
    res
  }

  /**
   * The methods used as lambda bodies for IndyLambda instructions within `method` of `hostClass`.
   */
  def indyLambdaBodyMethods(hostClass: InternalName, method: MethodNode): Map[InvokeDynamicInsnNode, Handle] = {
    onIndyLambdaImplMethodIfPresent(hostClass)(ms => ms.getOrElse(method, Nil).toMap).getOrElse(Map.empty)
  }
}

object BackendUtils {
  /**
   * A pseudo-flag, added MethodNodes whose maxLocals / maxStack are computed. This allows invoking
   * `computeMaxLocalsMaxStack` whenever running an analyzer but performing the actual computation
   * only when necessary.
   *
   * The largest JVM flag (as of JDK 8) is ACC_MANDATED (0x8000), however the asm framework uses
   * the same trick and defines some pseudo flags
   *   - ACC_DEPRECATED = 0x20000
   *   - ACC_SYNTHETIC_ATTRIBUTE = 0x40000
   *   - ACC_CONSTRUCTOR = 0x80000
   *
   * I haven't seen the value picked here in use anywhere. We make sure to remove the flag when
   * it's no longer needed.
   */
  private val ACC_MAXS_COMPUTED = 0x1000000
  def isMaxsComputed(method: MethodNode) = (method.access & ACC_MAXS_COMPUTED) != 0
  def setMaxsComputed(method: MethodNode) = method.access |= ACC_MAXS_COMPUTED
  def clearMaxsComputed(method: MethodNode) = method.access &= ~ACC_MAXS_COMPUTED

  /**
   * A pseudo-flag indicating if a MethodNode's unreachable code has been eliminated.
   *
   * The ASM Analyzer class does not compute any frame information for unreachable instructions.
   * Transformations that use an analyzer (including inlining) therefore require unreachable code
   * to be eliminated.
   *
   * This flag allows running dead code elimination whenever an analyzer is used. If the method
   * is already optimized, DCE can return early.
   */
  private val ACC_DCE_DONE = 0x2000000
  def isDceDone(method: MethodNode) = (method.access & ACC_DCE_DONE) != 0
  def setDceDone(method: MethodNode) = method.access |= ACC_DCE_DONE
  def clearDceDone(method: MethodNode) = method.access &= ~ACC_DCE_DONE

  private val LABEL_REACHABLE_STATUS = 0x1000000
  private def isLabelFlagSet(l: LabelNode1, f: Int): Boolean = (l.flags & f) != 0

  private def setLabelFlag(l: LabelNode1, f: Int): Unit = {
    l.flags |= f
  }

  private def clearLabelFlag(l: LabelNode1, f: Int): Unit = {
    l.flags &= ~f
  }
  def isLabelReachable(label: LabelNode) = isLabelFlagSet(label.asInstanceOf[LabelNode1], LABEL_REACHABLE_STATUS)
  def setLabelReachable(label: LabelNode) = setLabelFlag(label.asInstanceOf[LabelNode1], LABEL_REACHABLE_STATUS)
  def clearLabelReachable(label: LabelNode) = clearLabelFlag(label.asInstanceOf[LabelNode1], LABEL_REACHABLE_STATUS)

  final case class LambdaMetaFactoryCall(indy: InvokeDynamicInsnNode, samMethodType: Type, implMethod: Handle, instantiatedMethodType: Type)

  object LambdaMetaFactoryCall {
    val lambdaMetaFactoryMetafactoryHandle = new Handle(
      Opcodes.H_INVOKESTATIC,
      "java/lang/invoke/LambdaMetafactory",
      "metafactory",
      "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
      /* itf = */ false)

    val lambdaMetaFactoryAltMetafactoryHandle = new Handle(
      Opcodes.H_INVOKESTATIC,
      "java/lang/invoke/LambdaMetafactory",
      "altMetafactory",
      "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
      /* itf = */ false)

    def unapply(insn: AbstractInsnNode): Option[(InvokeDynamicInsnNode, Type, Handle, Type, Array[Type])] = insn match {
      case indy: InvokeDynamicInsnNode if indy.bsm == lambdaMetaFactoryMetafactoryHandle || indy.bsm == lambdaMetaFactoryAltMetafactoryHandle =>
        indy.bsmArgs match {
          case Array(samMethodType: Type, implMethod: Handle, instantiatedMethodType: Type, _@_*) =>
            // LambdaMetaFactory performs a number of automatic adaptations when invoking the lambda
            // implementation method (casting, boxing, unboxing, and primitive widening, see Javadoc).
            //
            // The closure optimizer supports only one of those adaptations: it will cast arguments
            // to the correct type when re-writing a closure call to the body method. Example:
            //
            //   val fun: String => String = l => l
            //   val l = List("")
            //   fun(l.head)
            //
            // The samMethodType of Function1 is `(Object)Object`, while the instantiatedMethodType
            // is `(String)String`. The return type of `List.head` is `Object`.
            //
            // The implMethod has the signature `C$anonfun(String)String`.
            //
            // At the closure callsite, we have an `INVOKEINTERFACE Function1.apply (Object)Object`,
            // so the object returned by `List.head` can be directly passed into the call (no cast).
            //
            // The closure object will cast the object to String before passing it to the implMethod.
            //
            // When re-writing the closure callsite to the implMethod, we have to insert a cast.
            //
            // The check below ensures that
            //   (1) the implMethod type has the expected signature (captured types plus argument types
            //       from instantiatedMethodType)
            //   (2) the receiver of the implMethod matches the first captured type
            //   (3) all parameters that are not the same in samMethodType and instantiatedMethodType
            //       are reference types, so that we can insert casts to perform the same adaptation
            //       that the closure object would.

            val isStatic                   = implMethod.getTag == Opcodes.H_INVOKESTATIC
            val indyParamTypes             = Type.getArgumentTypes(indy.desc)
            val instantiatedMethodArgTypes = instantiatedMethodType.getArgumentTypes
            val expectedImplMethodType     = {
              val paramTypes = (if (isStatic) indyParamTypes else indyParamTypes.tail) ++ instantiatedMethodArgTypes
              Type.getMethodType(instantiatedMethodType.getReturnType, paramTypes: _*)
            }

            val isIndyLambda = (
              Type.getType(implMethod.getDesc) == expectedImplMethodType              // (1)
                && (isStatic || implMethod.getOwner == indyParamTypes(0).getInternalName)  // (2)
                && samMethodType.getArgumentTypes.corresponds(instantiatedMethodArgTypes)((samArgType, instArgType) =>
                samArgType == instArgType || isReference(samArgType) && isReference(instArgType)) // (3)
              )

            if (isIndyLambda) Some((indy, samMethodType, implMethod, instantiatedMethodType, indyParamTypes))
            else None

          case _ => None
        }
      case _ => None
    }
  }

  /**
   * In order to run an Analyzer, the maxLocals / maxStack fields need to be available. The ASM
   * framework only computes these values during bytecode generation.
   *
   * NOTE 1: as explained in the `analysis` package object, the maxStack value used by the Analyzer
   * may be smaller than the correct maxStack value in the classfile (Analyzers only use a single
   * slot for long / double values). The maxStack computed here are correct for running an analyzer,
   * but not for writing in the classfile. We let the ClassWriter recompute max's.
   *
   * NOTE 2: the maxStack value computed here may be larger than the smallest correct value
   * that would allow running an analyzer, see `InstructionStackEffect.forAsmAnalysis` and
   * `InstructionStackEffect.maxStackGrowth`.
   *
   * NOTE 3: the implementation doesn't look at instructions that cannot be reached, it computes
   * the max local / stack size in the reachable code. These max's work just fine for running an
   * Analyzer: its implementation also skips over unreachable code in the same way.
   */
  def computeMaxLocalsMaxStack(method: MethodNode): Unit = {
    if (isAbstractMethod(method) || isNativeMethod(method)) {
      method.maxLocals = 0
      method.maxStack = 0
    } else if (!isMaxsComputed(method)) {
      val size = method.instructions.size

      var maxLocals = parametersSize(method)
      var maxStack = 0

      // queue of instruction indices where analysis should start
      var queue = new Array[Int](8)
      var top = -1
      def enq(i: Int): Unit = {
        if (top == queue.length - 1) {
          val nq = new Array[Int](queue.length * 2)
          Array.copy(queue, 0, nq, 0, queue.length)
          queue = nq
        }
        top += 1
        queue(top) = i
      }
      def deq(): Int = {
        val r = queue(top)
        top -= 1
        r
      }

      val subroutineRetTargets = new mutable.Stack[AbstractInsnNode]

      // for each instruction in the queue, contains the stack height at this instruction.
      // once an instruction has been treated, contains -1 to prevent re-enqueuing
      val stackHeights = new Array[Int](size)

      def enqInsn(insn: AbstractInsnNode, height: Int): Unit = {
        enqInsnIndex(method.instructions.indexOf(insn), height)
      }

      def enqInsnIndex(insnIndex: Int, height: Int): Unit = {
        if (insnIndex < size && stackHeights(insnIndex) != -1) {
          stackHeights(insnIndex) = height
          enq(insnIndex)
        }
      }

      val tcbIt = method.tryCatchBlocks.iterator
      while (tcbIt.hasNext) {
        val tcb = tcbIt.next()
        enqInsn(tcb.handler, 1)
        if (maxStack == 0) maxStack = 1
      }

      enq(0)
      while (top != -1) {
        val insnIndex = deq()
        val insn = method.instructions.get(insnIndex)
        val initHeight = stackHeights(insnIndex)
        stackHeights(insnIndex) = -1 // prevent i from being enqueued again

        if (insn.getOpcode == -1) { // frames, labels, line numbers
          enqInsnIndex(insnIndex + 1, initHeight)
        } else {
          val stackGrowth = InstructionStackEffect.maxStackGrowth(insn)
          val heightAfter = initHeight + stackGrowth
          if (heightAfter > maxStack) maxStack = heightAfter

          // update maxLocals
          insn match {
            case v: VarInsnNode =>
              val longSize = if (isSize2LoadOrStore(v.getOpcode)) 1 else 0
              maxLocals = math.max(maxLocals, v.`var` + longSize + 1) // + 1 because local numbers are 0-based

            case i: IincInsnNode =>
              maxLocals = math.max(maxLocals, i.`var` + 1)

            case _ =>
          }

          insn match {
            case j: JumpInsnNode =>
              if (j.getOpcode == JSR) {
                val jsrTargetHeight = heightAfter + 1
                if (jsrTargetHeight > maxStack) maxStack = jsrTargetHeight
                subroutineRetTargets.push(j.getNext)
                enqInsn(j.label, jsrTargetHeight)
              } else {
                enqInsn(j.label, heightAfter)
                val opc = j.getOpcode
                if (opc != GOTO) enqInsnIndex(insnIndex + 1, heightAfter) // jump is conditional, so the successor is also a possible control flow target
              }

            case l: LookupSwitchInsnNode =>
              var j = 0
              while (j < l.labels.size) {
                enqInsn(l.labels.get(j), heightAfter); j += 1
              }
              enqInsn(l.dflt, heightAfter)

            case t: TableSwitchInsnNode =>
              var j = 0
              while (j < t.labels.size) {
                enqInsn(t.labels.get(j), heightAfter); j += 1
              }
              enqInsn(t.dflt, heightAfter)

            case r: VarInsnNode if r.getOpcode == RET =>
              enqInsn(subroutineRetTargets.pop(), heightAfter)

            case _ =>
              val opc = insn.getOpcode
              if (opc != ATHROW && !isReturn(insn))
                enqInsnIndex(insnIndex + 1, heightAfter)
          }
        }
      }

      method.maxLocals = maxLocals
      method.maxStack = maxStack

      setMaxsComputed(method)
    }
  }

  abstract class NestedClassesCollector[T] extends GenericSignatureVisitor {
    val innerClasses = mutable.Set.empty[T]

    def declaredNestedClasses(internalName: InternalName): List[T]

    def getClassIfNested(internalName: InternalName): Option[T]

    def visit(classNode: ClassNode): Unit = {
      visitInternalName(classNode.name)
      innerClasses ++= declaredNestedClasses(classNode.name)

      visitInternalName(classNode.superName)
      classNode.interfaces.asScala foreach visitInternalName
      visitInternalName(classNode.outerClass)

      visitAnnotations(classNode.visibleAnnotations)
      visitAnnotations(classNode.visibleTypeAnnotations)
      visitAnnotations(classNode.invisibleAnnotations)
      visitAnnotations(classNode.invisibleTypeAnnotations)

      visitClassSignature(classNode.signature)

      for (f <- classNode.fields.asScala) {
        visitDescriptor(f.desc)
        visitAnnotations(f.visibleAnnotations)
        visitAnnotations(f.visibleTypeAnnotations)
        visitAnnotations(f.invisibleAnnotations)
        visitAnnotations(f.invisibleTypeAnnotations)
        visitFieldSignature(f.signature)
      }

      for (m <- classNode.methods.asScala) {
        visitDescriptor(m.desc)

        visitAnnotations(m.visibleAnnotations)
        visitAnnotations(m.visibleTypeAnnotations)
        visitAnnotations(m.invisibleAnnotations)
        visitAnnotations(m.invisibleTypeAnnotations)
        visitAnnotationss(m.visibleParameterAnnotations)
        visitAnnotationss(m.invisibleParameterAnnotations)
        visitAnnotations(m.visibleLocalVariableAnnotations)
        visitAnnotations(m.invisibleLocalVariableAnnotations)

        m.exceptions.asScala foreach visitInternalName
        for (tcb <- m.tryCatchBlocks.asScala) visitInternalName(tcb.`type`)

        val iter = m.instructions.iterator
        while (iter.hasNext) iter.next() match {
          case ti: TypeInsnNode           => visitInternalNameOrArrayReference(ti.desc)
          case fi: FieldInsnNode          => visitInternalNameOrArrayReference(fi.owner); visitDescriptor(fi.desc)
          case mi: MethodInsnNode         => visitInternalNameOrArrayReference(mi.owner); visitDescriptor(mi.desc)
          case id: InvokeDynamicInsnNode  => visitDescriptor(id.desc); visitHandle(id.bsm); id.bsmArgs foreach visitConstant
          case ci: LdcInsnNode            => visitConstant(ci.cst)
          case ma: MultiANewArrayInsnNode => visitDescriptor(ma.desc)
          case _ =>
        }

        visitMethodSignature(m.signature)
      }
    }

    private def containsChar(s: String, offset: Int, length: Int, char: Char): Boolean = {
      val ix = s.indexOf(char, offset)
      !(ix == -1 || ix >= offset + length)
    }

    def visitInternalName(internalName: String, offset: Int, length: Int): Unit = if (internalName != null && containsChar(internalName, offset, length, '$')) {
      for (c <- getClassIfNested(internalName.substring(offset, length)))
        innerClasses += c
    }

    // either an internal/Name or [[Linternal/Name; -- there are certain references in classfiles
    // that are either an internal name (without the surrounding `L;`) or an array descriptor
    // `[Linternal/Name;`.
    def visitInternalNameOrArrayReference(ref: String): Unit = if (ref != null) {
      val bracket = ref.lastIndexOf('[')
      if (bracket == -1) visitInternalName(ref)
      else if (ref.charAt(bracket + 1) == 'L') visitInternalName(ref, bracket + 2, ref.length - 1)
    }

    // we are only interested in the class references in the descriptor, so we can skip over
    // primitives and the brackets of array descriptors
    def visitDescriptor(desc: String): Unit = (desc.charAt(0): @switch) match {
      case '(' =>
        var i = 1
        while (i < desc.length) {
          if (desc.charAt(i) == 'L') {
            val start = i + 1 // skip the L
            while (desc.charAt(i) != ';') i += 1
            visitInternalName(desc, start, i)
          }
          // skips over '[', ')', primitives
          i += 1
        }

      case 'L' =>
        visitInternalName(desc, 1, desc.length - 1)

      case '[' =>
        visitInternalNameOrArrayReference(desc)

      case _ => // skip over primitive types
    }

    def visitConstant(const: AnyRef): Unit = const match {
      case t: Type => visitDescriptor(t.getDescriptor)
      case _ =>
    }

    // in principle we could references to annotation types, as they only end up as strings in the
    // constant pool, not as class references. however, the java compiler still includes nested
    // annotation classes in the innerClass table, so we do the same. explained in detail in the
    // large comment in class BTypes.
    def visitAnnotation(annot: AnnotationNode): Unit = {
      visitDescriptor(annot.desc)
      if (annot.values != null) annot.values.asScala foreach visitConstant
    }

    def visitAnnotations(annots: java.util.List[_ <: AnnotationNode]) = if (annots != null) annots.asScala foreach visitAnnotation
    def visitAnnotationss(annotss: Array[java.util.List[AnnotationNode]]) = if (annotss != null) annotss foreach visitAnnotations

    def visitHandle(handle: Handle): Unit = {
      visitInternalNameOrArrayReference(handle.getOwner)
      visitDescriptor(handle.getDesc)
    }
  }

  abstract class GenericSignatureVisitor {
    final def visitInternalName(internalName: String): Unit = visitInternalName(internalName, 0, if (internalName eq null) 0 else internalName.length)
    def visitInternalName(internalName: String, offset: Int, length: Int): Unit

    def raiseError(msg: String, sig: String, e: Option[Throwable] = None): Unit

    def visitClassSignature(sig: String): Unit = if (sig != null) {
      val p = new Parser(sig)
      p.safely { p.classSignature() }
    }

    def visitMethodSignature(sig: String): Unit = if (sig != null) {
      val p = new Parser(sig)
      p.safely { p.methodSignature() }
    }

    def visitFieldSignature(sig: String): Unit = if (sig != null) {
      val p = new Parser(sig)
      p.safely { p.fieldSignature() }
    }

    private final class Parser(sig: String) {
      // For performance, `Char => Boolean` is not specialized
      private trait CharBooleanFunction { def apply(c: Char): Boolean }

      private var index = 0
      private val end = sig.length

      private val Aborted: Throwable = new NoStackTrace { }
      private def abort(): Nothing = throw Aborted

      @inline def safely(f: => Unit): Unit = try f catch {
        case Aborted =>
        case NonFatal(e) => raiseError(s"Exception thrown during signature parsing", sig, Some(e))
      }

      private def current = {
        if (index >= end) {
          raiseError(s"Out of bounds, $index >= $end", sig)
          abort() // Don't continue, even if `notifyInvalidSignature` returns
        }
        sig.charAt(index)
      }

      private def accept(c: Char): Unit = {
        if (current != c) {
          raiseError(s"Expected $c at $index, found $current", sig)
          abort()
        }
        index += 1
      }

      private def skip(): Unit = { index += 1 }
      private def getCurrentAndSkip(): Char = { val c = current; skip(); c }

      private def skipUntil(isDelimiter: CharBooleanFunction): Unit = {
        while (!isDelimiter(current)) { index += 1 }
      }
      private def skipUntilDelimiter(delimiter: Char): Unit = {
        sig.indexOf(delimiter, index) match {
          case -1 =>
            raiseError(s"Out of bounds", sig)
            abort() // Don't continue, even if `notifyInvalidSignature` returns
          case i =>
            index = i
        }
      }

      private def appendUntil(builder: java.lang.StringBuilder, isDelimiter: CharBooleanFunction): Unit = {
        val start = index
        skipUntil(isDelimiter)
        builder.append(sig, start, index)
      }

      def isBaseType(c: Char): Boolean = c match {
        case 'B' | 'C' | 'D' | 'F' | 'I' | 'J' | 'S' | 'Z' => true
        case _ => false
      }

      private val isClassNameEnd: CharBooleanFunction = (c: Char) => c == '<' || c == '.' || c == ';'

      private def typeArguments(): Unit = if (current == '<') {
        skip()
        while (current != '>') current match {
          case '*' | '+' | '-' =>
            skip()
          case _ =>
            referenceTypeSignature()
        }
        accept('>')
      }

      @tailrec private def referenceTypeSignature(): Unit = getCurrentAndSkip() match {
        case 'L' =>
          val names = new java.lang.StringBuilder(32)

          appendUntil(names, isClassNameEnd)
          visitInternalName(names.toString)
          typeArguments()

          while (current == '.') {
            skip()
            names.append('$')
            appendUntil(names, isClassNameEnd)
            visitInternalName(names.toString)
            typeArguments()
          }
          accept(';')

        case 'T' =>
          skipUntilDelimiter(';')
          skip()

        case '[' =>
          if (isBaseType(current)) skip()
          else referenceTypeSignature()
      }

      private def typeParameters(): Unit = if (current == '<') {
        skip()
        while (current != '>') {
          skipUntilDelimiter(':'); skip()
          val c = current
          // The ClassBound can be missing, but only if there's an InterfaceBound after.
          // This is an assumption that's not in the spec, see https://stackoverflow.com/q/44284928
          if (c != ':' && c != '>') { referenceTypeSignature() }
          while (current == ':') { skip(); referenceTypeSignature() }
        }
        accept('>')
      }

      def classSignature(): Unit = {
        typeParameters()
        while (index < end) referenceTypeSignature()
      }

      def methodSignature(): Unit = {
        typeParameters()

        accept('(')
        while (current != ')') {
          if (isBaseType(current)) skip()
          else referenceTypeSignature()
        }
        accept(')')

        if (current == 'V' || isBaseType(current)) skip()
        else referenceTypeSignature()

        while (index < end) {
          accept('^')
          referenceTypeSignature()
        }
      }

      def fieldSignature(): Unit = if (sig != null) safely {
        referenceTypeSignature()
      }
    }
  }

  object handleOrdering extends Ordering[Handle] {
    override def compare(x: Handle, y: Handle): Int = {
      if (x eq y) return 0

      val t = Ordering.Int.compare(x.getTag, y.getTag)
      if (t != 0) return t

      val i = Ordering.Boolean.compare(x.isInterface, y.isInterface)
      if (x.isInterface != y.isInterface) return i

      val o = x.getOwner compareTo y.getOwner
      if (o != 0) return o

      val n = x.getName compareTo y.getName
      if (n != 0) return n

      x.getDesc compareTo y.getDesc
    }
  }

  def isArrayGetLength(mi: MethodInsnNode): Boolean = mi.owner == "java/lang/reflect/Array" && mi.name == "getLength" && mi.desc == "(Ljava/lang/Object;)I"

  // If argument i of the method is null-checked, the bit `i+1` of the result is 1
  def nullCheckedArguments(mi: MethodInsnNode): Long = {
    if (isArrayGetLength(mi)) 1
    else 0
  }

  def classTagNewArrayArg(mi: MethodInsnNode, prodCons: ProdConsAnalyzer): InternalName = {
    if (mi.name == "newArray" && mi.owner == "scala/reflect/ClassTag" && mi.desc == "(I)Ljava/lang/Object;") {
      val prods = prodCons.initialProducersForValueAt(mi, prodCons.frameAt(mi).stackTop - 1)
      if (prods.size == 1) prods.head match {
        case ctApply: MethodInsnNode =>
          if (ctApply.name == "apply" && ctApply.owner == "scala/reflect/ClassTag$" && ctApply.desc == "(Ljava/lang/Class;)Lscala/reflect/ClassTag;") {
            val clsProd = prodCons.initialProducersForValueAt(ctApply, prodCons.frameAt(ctApply).stackTop)
            if (clsProd.size == 1) clsProd.head match {
              case ldc: LdcInsnNode =>
                ldc.cst match {
                  case tp: Type if tp.getSort == Type.OBJECT => // TODO: support nested arrays?
                    return tp.getInternalName
                  case _ =>
                }
              case _ =>
            }
          }
        case _ =>
      }
    }
    null
  }

  def isArrayGetLength(mi: MethodInsnNode, typeAnalyzer: NonLubbingTypeFlowAnalyzer): Boolean = {
    mi.name == "getLength" && mi.owner == "java/lang/reflect/Array" && mi.desc == "(Ljava/lang/Object;)I" && {
      val f = typeAnalyzer.frameAt(mi)
      f.getValue(f.stackTop).getType.getSort == Type.ARRAY
    }
  }

  def getClassKnownType(mi: MethodInsnNode, typeAnalyzer: NonLubbingTypeFlowAnalyzer): Type = {
    if (mi.name == "getClass" && mi.owner == "java/lang/Object" && mi.desc == "()Ljava/lang/Class;") {
      val f = typeAnalyzer.frameAt(mi)
      val tp = f.getValue(f.stackTop).getType
      if (tp.getSort == Type.ARRAY) {
        if (tp.getElementType.getSort != Type.OBJECT)
          return tp
      }
    }
    null
  }

  lazy val primitiveTypes: Map[String, Type] = Map(
    ("Unit", Type.VOID_TYPE),
    ("Boolean", Type.BOOLEAN_TYPE),
    ("Char", Type.CHAR_TYPE),
    ("Byte", Type.BYTE_TYPE),
    ("Short", Type.SHORT_TYPE),
    ("Int", Type.INT_TYPE),
    ("Float", Type.FLOAT_TYPE),
    ("Long", Type.LONG_TYPE),
    ("Double", Type.DOUBLE_TYPE))

  private val primitiveManifestApplies: Map[String, String] = primitiveTypes map {
    case (k, _) => (k, s"()Lscala/reflect/ManifestFactory$$${k}Manifest;")
  }

  def isClassTagApply(mi: MethodInsnNode): Boolean = {
    mi.owner == "scala/reflect/ClassTag$" && {
      mi.name == "apply" && mi.desc == "(Ljava/lang/Class;)Lscala/reflect/ClassTag;" ||
        primitiveManifestApplies.get(mi.name).contains(mi.desc)
    }
  }
}
