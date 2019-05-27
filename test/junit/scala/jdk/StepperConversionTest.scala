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

package scala.jdk

import org.junit.Test
import org.junit.Assert.{assertFalse, assertTrue}
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

import scala.collection.{AnyStepper, DoubleStepper, IntStepper, LongStepper, Stepper, concurrent => cc, immutable => ci, mutable => cm}
import scala.{collection => co}

@RunWith(classOf[JUnit4])
class StepperConversionTest {
  def isAcc[X](x: X): Boolean = x match {
    case _: AnyAccumulatorStepper[_] => true
    case _: DoubleAccumulatorStepper => true
    case _: IntAccumulatorStepper => true
    case _: LongAccumulatorStepper => true
    case _ => false
  }

  trait SpecCheck {
    def check[X](x: X): Boolean
    def msg[X](x: X): String
    def assert(x: Any): Unit =
      if(!check(x)) assertTrue(msg(x), false)
  }
  object SpecCheck {
    def apply(f: Any => Boolean, err: Any => String = _ => "SpecCheck failed") = new SpecCheck {
      def check[X](x: X): Boolean = f(x)
      def msg[X](x: X): String = err(x)
    }
  }

  def yay(x: => Stepper[_])(implicit correctSpec: SpecCheck): Unit = {
    correctSpec.assert(x)
    assertTrue(isAcc(x))
  }

  def nay[X](x: => Stepper[_])(implicit correctSpec: SpecCheck): Unit = {
    correctSpec.assert(x)
    assertFalse(isAcc(x))
  }

  @Test
  def comprehensivelyGeneric(): Unit = {
    implicit val spec = SpecCheck(_.isInstanceOf[AnyStepper[_]])

    // Collection section
    nay( co.Iterator[String]("salmon").buffered.stepper )
    nay( co.IndexedSeq[String]("salmon").stepper )
    nay( co.Iterable[String]("salmon").stepper )
    nay( co.Iterable[String]("salmon").view.stepper )
    nay( co.Iterator[String]("salmon").stepper )
    nay( co.LinearSeq[String]("salmon").stepper )
    nay( co.Map[String, String]("fish" -> "salmon").stepper )
    nay( co.Map[String, String]("fish" -> "salmon").keysStepper )
    nay( co.Map[String, String]("fish" -> "salmon").valuesStepper )
    nay( co.Seq[String]("salmon").stepper )
    nay( co.Seq[String]("salmon").view.stepper )
    nay( co.Set[String]("salmon").stepper )
    nay( co.SortedMap[String, String]("fish" -> "salmon").stepper )
    nay( co.SortedMap[String, String]("fish" -> "salmon").keysStepper )
    nay( co.SortedMap[String, String]("fish" -> "salmon").valuesStepper )
    nay( co.SortedSet[String]("salmon").stepper )
    yay( co.Iterable[String]("salmon").to(Accumulator).stepper )
    yay( (co.Iterator[String]("salmon"): co.IterableOnce[String]).to(Accumulator).stepper )
    yay( co.Iterable[String]("salmon").view.to(Accumulator).stepper )

    // Immutable section
    nay( ci.::("salmon", Nil).stepper )
    nay( (ci.HashMap[String, String]("fish" -> "salmon"): ci.AbstractMap[String, String]).stepper )
    nay( (ci.HashMap[String, String]("fish" -> "salmon"): ci.AbstractMap[String, String]).keysStepper )
    nay( (ci.HashMap[String, String]("fish" -> "salmon"): ci.AbstractMap[String, String]).valuesStepper )
    nay( ci.HashSet[String]("salmon").stepper )
    nay( ci.IndexedSeq[String]("salmon").stepper )
    nay( ci.IntMap[String](123456 -> "salmon").stepper )
    nay( ci.IntMap[String](123456 -> "salmon").valuesStepper )
    nay( ci.Iterable[String]("salmon").stepper )
    nay( ci.LinearSeq[String]("salmon").stepper )
    nay( ci.List[String]("salmon").stepper )
    nay( ci.ListMap[String, String]("fish" -> "salmon").stepper )
    nay( ci.ListMap[String, String]("fish" -> "salmon").keysStepper )
    nay( ci.ListMap[String, String]("fish" -> "salmon").valuesStepper )
    nay( ci.ListSet[String]("salmon").stepper )
    nay( ci.LongMap[String](9876543210L -> "salmon").stepper )
    nay( ci.LongMap[String](9876543210L -> "salmon").valuesStepper )
    nay( ci.Map[String, String]("fish" -> "salmon").stepper )
    nay( ci.Map[String, String]("fish" -> "salmon").keysStepper )
    nay( ci.Map[String, String]("fish" -> "salmon").valuesStepper )
    nay( ci.Queue[String]("salmon").stepper )
    nay( ci.Seq[String]("salmon").stepper )
    nay( ci.Set[String]("salmon").stepper )
    nay( ci.SortedMap[String, String]("fish" -> "salmon").stepper )
    nay( ci.SortedMap[String, String]("fish" -> "salmon").keysStepper )
    nay( ci.SortedMap[String, String]("fish" -> "salmon").valuesStepper )
    nay( ci.SortedSet[String]("salmon").stepper )
    nay( ci.Stream[String]("salmon").stepper )
    nay( ci.Stream[String]("salmon").view.stepper )
    nay( ci.LazyList[String]("salmon").stepper )
    nay( ci.LazyList[String]("salmon").view.stepper )
    yay( ci.Iterable[String]("salmon").to(Accumulator).stepper )
    nay( ci.TreeMap[String, String]("fish" -> "salmon").stepper )
    nay( ci.TreeMap[String, String]("fish" -> "salmon").keysStepper )
    nay( ci.TreeMap[String, String]("fish" -> "salmon").valuesStepper )
    nay( ci.TreeSet[String]("salmon").stepper )
    nay( ci.Vector[String]("salmon").stepper )

    // Mutable section
    nay( (cm.ArrayBuffer[String]("salmon"): cm.AbstractBuffer[String]).stepper )
    nay( (cm.PriorityQueue[String]("salmon"): cm.AbstractIterable[String]).stepper )
    nay( (cm.HashMap[String, String]("fish" -> "salmon"): cm.AbstractMap[String, String]).stepper )
    nay( (cm.HashMap[String, String]("fish" -> "salmon"): cm.AbstractMap[String, String]).keysStepper )
    nay( (cm.HashMap[String, String]("fish" -> "salmon"): cm.AbstractMap[String, String]).valuesStepper )
    nay( (cm.ArrayBuffer[String]("salmon"): cm.AbstractSeq[String]).stepper )
    nay( (cm.HashSet[String]("salmon"): cm.AbstractSet[String]).stepper )
    nay( cm.AnyRefMap[String,String]("fish" -> "salmon").stepper )
    nay( cm.AnyRefMap[String,String]("fish" -> "salmon").keysStepper )
    nay( cm.AnyRefMap[String,String]("fish" -> "salmon").valuesStepper )
    nay( cm.ArrayBuffer[String]("salmon").stepper )
    nay( (Array("salmon"): cm.ArraySeq[String]).stepper )
    nay( cm.ArraySeq[String]("salmon").stepper )
    nay( cm.ArrayStack[String]("salmon").stepper )
    nay( (cm.ArrayBuffer[String]("salmon"): cm.Buffer[String]).stepper )
    nay( cm.HashMap[String, String]("fish" -> "salmon").stepper )
    nay( cm.HashMap[String, String]("fish" -> "salmon").keysStepper )
    nay( cm.HashMap[String, String]("fish" -> "salmon").valuesStepper )
    nay( cm.HashSet[String]("salmon").stepper )
    nay( cm.IndexedSeq[String]("salmon").stepper )
    nay( cm.IndexedSeq[String]("salmon").view.stepper )
    nay( cm.Iterable[String]("salmon").stepper )
    nay( cm.LinkedHashMap[String, String]("fish" -> "salmon").stepper )
    nay( cm.LinkedHashMap[String, String]("fish" -> "salmon").keysStepper )
    nay( cm.LinkedHashMap[String, String]("fish" -> "salmon").valuesStepper )
    nay( cm.LinkedHashSet[String]("salmon").stepper )
    nay( cm.ListBuffer[String]("salmon").stepper )
    nay( cm.ListMap[String, String]("fish" -> "salmon").stepper )
    nay( cm.ListMap[String, String]("fish" -> "salmon").keysStepper )
    nay( cm.ListMap[String, String]("fish" -> "salmon").keysStepper )
    nay( cm.LongMap[String](9876543210L -> "salmon").stepper )
    nay( cm.LongMap[String](9876543210L -> "salmon").valuesStepper )
    nay( cm.Map[String, String]("fish" -> "salmon").stepper )
    nay( cm.Map[String, String]("fish" -> "salmon").keysStepper )
    nay( cm.Map[String, String]("fish" -> "salmon").valuesStepper )
    nay( cm.OpenHashMap[String, String]("fish" -> "salmon").stepper )
    nay( cm.OpenHashMap[String, String]("fish" -> "salmon").keysStepper )
    nay( cm.OpenHashMap[String, String]("fish" -> "salmon").valuesStepper )
    nay( cm.PriorityQueue[String]("salmon").stepper )
    nay( cm.Queue[String]("salmon").stepper ) // Used to be `Good` in 2.12, in 2.13 `Queue` is no longer a `LinearSeq`
    nay( cm.Seq[String]("salmon").stepper )
    nay( cm.Set[String]("salmon").stepper )
    nay( cm.SortedSet[String]("salmon").stepper )
    nay( cm.Stack[String]("salmon").stepper ) // Used to be `Good` in 2.12, in 2.13 `Stack` is no longer a `LinearSeq`
    yay( cm.Iterable[String]("salmon").to(Accumulator).stepper )
    nay( cm.TreeSet[String]("salmon").stepper )
    nay( cm.UnrolledBuffer[String]("salmon").stepper )
    nay( cm.WeakHashMap[String, String]("fish" -> "salmon").stepper )
    nay( cm.WeakHashMap[String, String]("fish" -> "salmon").keysStepper )
    nay( cm.WeakHashMap[String, String]("fish" -> "salmon").valuesStepper )

    // Java 6 converters section

    // Concurrent section
    nay( cc.TrieMap[String, String]("fish" -> "salmon").stepper )
    nay( cc.TrieMap[String, String]("fish" -> "salmon").keysStepper )
    nay( cc.TrieMap[String, String]("fish" -> "salmon").keysStepper )
    nay( (cc.TrieMap[String, String]("fish" -> "salmon"): cc.Map[String, String]).stepper )
    nay( (cc.TrieMap[String, String]("fish" -> "salmon"): cc.Map[String, String]).keysStepper )
    nay( (cc.TrieMap[String, String]("fish" -> "salmon"): cc.Map[String, String]).valuesStepper )
  }
  @Test
  def comprehensivelyDouble(): Unit = {
    implicit val spec = SpecCheck(_.isInstanceOf[DoubleStepper])
    //Double-specific tests

    // Collection section
    nay( co.Iterator[Double](3.14159).buffered.stepper )
    nay( co.IndexedSeq[Double](3.14159).stepper )
    nay( co.Iterable[Double](3.14159).stepper )
    nay( co.Iterable[Double](3.14159).view.stepper )
    nay( co.Iterator[Double](3.14159).stepper )
    nay( co.LinearSeq[Double](3.14159).stepper )
    nay( co.Map[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( co.Map[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( co.Seq[Double](3.14159).stepper )
    nay( co.Seq[Double](3.14159).view.stepper )
    nay( co.Set[Double](3.14159).stepper )
    nay( co.SortedMap[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( co.SortedMap[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( co.SortedSet[Double](3.14159).stepper )
    yay( co.Iterable[Double](3.14159).to(Accumulator).stepper )
    yay( (co.Iterator[Double](3.14159): co.IterableOnce[Double]).to(Accumulator).stepper )
    yay( co.Iterable[Double](3.14159).view.to(Accumulator).stepper )

    // Immutable section
    nay( ci.::(3.14159, Nil).stepper )
    nay( (ci.HashMap[Double, Double](2.718281828 -> 3.14159): ci.AbstractMap[Double, Double]).keysStepper )
    nay( (ci.HashMap[Double, Double](2.718281828 -> 3.14159): ci.AbstractMap[Double, Double]).valuesStepper )
    nay( ci.HashSet[Double](3.14159).stepper )
    nay( ci.IndexedSeq[Double](3.14159).stepper )
    nay( ci.IntMap[Double](123456 -> 3.14159).valuesStepper )
    nay( ci.Iterable[Double](3.14159).stepper )
    nay( ci.LinearSeq[Double](3.14159).stepper )
    nay( ci.List[Double](3.14159).stepper )
    nay( ci.ListMap[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( ci.ListMap[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( ci.ListSet[Double](3.14159).stepper )
    nay( ci.LongMap[Double](9876543210L -> 3.14159).valuesStepper )
    nay( ci.Map[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( ci.Map[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( ci.Queue[Double](3.14159).stepper )
    nay( ci.Seq[Double](3.14159).stepper )
    nay( ci.Set[Double](3.14159).stepper )
    nay( ci.SortedMap[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( ci.SortedMap[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( ci.SortedSet[Double](3.14159).stepper )
    nay( ci.Stream[Double](3.14159).stepper )
    nay( ci.Stream[Double](3.14159).view.stepper )
    nay( ci.LazyList[Double](3.14159).stepper )
    nay( ci.LazyList[Double](3.14159).view.stepper )
    yay( ci.Iterable[Double](3.14159).to(Accumulator).stepper )
    nay( ci.TreeMap[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( ci.TreeMap[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( ci.TreeSet[Double](3.14159).stepper )
    nay( ci.Vector[Double](3.14159).stepper )

    // Mutable section
    nay( (cm.ArrayBuffer[Double](3.14159): cm.AbstractBuffer[Double]).stepper )
    nay( (cm.PriorityQueue[Double](3.14159): cm.AbstractIterable[Double]).stepper )
    nay( (cm.HashMap[Double, Double](2.718281828 -> 3.14159): cm.AbstractMap[Double, Double]).keysStepper )
    nay( (cm.HashMap[Double, Double](2.718281828 -> 3.14159): cm.AbstractMap[Double, Double]).valuesStepper )
    nay( (cm.ArrayBuffer[Double](3.14159): cm.AbstractSeq[Double]).stepper )
    nay( (cm.HashSet[Double](3.14159): cm.AbstractSet[Double]).stepper )
    nay( cm.AnyRefMap[String,Double]("fish" -> 3.14159).valuesStepper )
    nay( cm.ArrayBuffer[Double](3.14159).stepper )
    nay( (Array(3.14159): cm.ArraySeq[Double]).stepper )
    nay( cm.ArraySeq[Double](3.14159).stepper )
    nay( cm.ArrayStack[Double](3.14159).stepper )
    nay( (cm.ArrayBuffer[Double](3.14159): cm.Buffer[Double]).stepper )
    nay( cm.HashMap[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( cm.HashMap[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( cm.HashSet[Double](3.14159).stepper )
    nay( cm.IndexedSeq[Double](3.14159).stepper )
    nay( cm.IndexedSeq[Double](3.14159).view.stepper )
    nay( cm.Iterable[Double](3.14159).stepper )
    nay( cm.LinkedHashMap[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( cm.LinkedHashMap[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( cm.LinkedHashSet[Double](3.14159).stepper )
    nay( cm.ListBuffer[Double](3.14159).stepper )
    nay( cm.ListMap[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( cm.ListMap[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( cm.LongMap[Double](9876543210L -> 3.14159).valuesStepper )
    nay( cm.Map[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( cm.Map[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( cm.OpenHashMap[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( cm.OpenHashMap[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( cm.PriorityQueue[Double](3.14159).stepper )
    nay( cm.Queue[Double](3.14159).stepper ) // Used to be `Good` in 2.12, in 2.13 `Queue` is no longer a `LinearSeq`
    nay( cm.Seq[Double](3.14159).stepper )
    nay( cm.Set[Double](3.14159).stepper )
    nay( cm.SortedSet[Double](3.14159).stepper )
    nay( cm.Stack[Double](3.14159).stepper ) // Used to be `Good` in 2.12, in 2.13 `Stack` is no longer a `LinearSeq`
    yay( cm.Iterable[Double](3.14159).to(Accumulator).stepper )
    nay( cm.TreeSet[Double](3.14159).stepper )
    nay( cm.UnrolledBuffer[Double](3.14159).stepper )
    nay( cm.WeakHashMap[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( cm.WeakHashMap[Double, Double](2.718281828 -> 3.14159).valuesStepper )

    // Java 6 converters section

    // Concurrent section
    nay( cc.TrieMap[Double, Double](2.718281828 -> 3.14159).keysStepper )
    nay( cc.TrieMap[Double, Double](2.718281828 -> 3.14159).valuesStepper )
    nay( (cc.TrieMap[Double, Double](2.718281828 -> 3.14159): cc.Map[Double, Double]).keysStepper )
    nay( (cc.TrieMap[Double, Double](2.718281828 -> 3.14159): cc.Map[Double, Double]).valuesStepper )
  }

  @Test
  def comprehensivelyInt(): Unit = {
    implicit val spec = SpecCheck(_.isInstanceOf[IntStepper], x => s"$x should be an IntStepper")

    // Int-specific tests
    nay( co.BitSet(42).stepper )
    nay( ci.BitSet(42).stepper )
    nay( ci.NumericRange(123456, 123458, 1).stepper )
    nay( cm.BitSet(42).stepper )
    nay( (1 until 2).stepper )
    nay( ci.IntMap[String](123456 -> "salmon").keysStepper )
    nay( ci.IntMap[Double](123456 -> 3.14159).keysStepper )
    nay( ci.IntMap[Long](123456 -> 0x123456789L).keysStepper )

    // Collection section
    nay( co.Iterator[Int](654321).buffered.stepper )
    nay( co.IndexedSeq[Int](654321).stepper )
    nay( co.Iterable[Int](654321).stepper )
    nay( co.Iterable[Int](654321).view.stepper )
    nay( co.Iterator[Int](654321).stepper )
    nay( co.LinearSeq[Int](654321).stepper )
    nay( co.Map[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( co.Map[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( co.Seq[Int](654321).stepper )
    nay( co.Seq[Int](654321).view.stepper )
    nay( co.Set[Int](654321).stepper )
    nay( co.SortedMap[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( co.SortedMap[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( co.SortedSet[Int](654321).stepper )
    yay( co.Iterable[Int](654321).to(Accumulator).stepper )
    yay( (co.Iterator[Int](654321): co.IterableOnce[Int]).to(Accumulator).stepper )
    yay( co.Iterable[Int](654321).view.to(Accumulator).stepper )

    // Immutable section
    nay( ci.::(654321, Nil).stepper )
    nay( (ci.HashMap[Int, Int](0xDEEDED -> 654321): ci.AbstractMap[Int, Int]).keysStepper )
    nay( (ci.HashMap[Int, Int](0xDEEDED -> 654321): ci.AbstractMap[Int, Int]).valuesStepper )
    nay( ci.HashSet[Int](654321).stepper )
    nay( ci.IndexedSeq[Int](654321).stepper )
    nay( ci.IntMap[Int](123456 -> 654321).keysStepper )
    nay( ci.IntMap[Int](123456 -> 654321).valuesStepper )
    nay( ci.Iterable[Int](654321).stepper )
    nay( ci.LinearSeq[Int](654321).stepper )
    nay( ci.List[Int](654321).stepper )
    nay( ci.ListMap[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( ci.ListMap[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( ci.ListSet[Int](654321).stepper )
    nay( ci.LongMap[Int](9876543210L -> 654321).valuesStepper )
    nay( ci.Map[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( ci.Map[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( ci.Queue[Int](654321).stepper )
    nay( ci.Seq[Int](654321).stepper )
    nay( ci.Set[Int](654321).stepper )
    nay( ci.SortedMap[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( ci.SortedMap[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( ci.SortedSet[Int](654321).stepper )
    nay( ci.Stream[Int](654321).stepper )
    nay( ci.Stream[Int](654321).view.stepper )
    nay( ci.LazyList[Int](654321).stepper )
    nay( ci.LazyList[Int](654321).view.stepper )
    yay( ci.Iterable[Int](654321).to(Accumulator).stepper )
    nay( ci.TreeMap[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( ci.TreeMap[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( ci.TreeSet[Int](654321).stepper )
    nay( ci.Vector[Int](654321).stepper )

    // Mutable section
    nay( (cm.ArrayBuffer[Int](654321): cm.AbstractBuffer[Int]).stepper )
    nay( (cm.PriorityQueue[Int](654321): cm.AbstractIterable[Int]).stepper )
    nay( (cm.HashMap[Int, Int](0xDEEDED -> 654321): cm.AbstractMap[Int, Int]).keysStepper )
    nay( (cm.HashMap[Int, Int](0xDEEDED -> 654321): cm.AbstractMap[Int, Int]).valuesStepper )
    nay( (cm.ArrayBuffer[Int](654321): cm.AbstractSeq[Int]).stepper )
    nay( (cm.HashSet[Int](654321): cm.AbstractSet[Int]).stepper )
    nay( cm.AnyRefMap[String, Int]("fish" -> 654321).valuesStepper )
    nay( cm.ArrayBuffer[Int](654321).stepper )
    nay( (Array(654321): cm.ArraySeq[Int]).stepper )
    nay( cm.ArraySeq[Int](654321).stepper )
    nay( cm.ArrayStack[Int](654321).stepper )
    nay( (cm.ArrayBuffer[Int](654321): cm.Buffer[Int]).stepper )
    nay( cm.HashMap[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( cm.HashMap[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( cm.HashSet[Int](654321).stepper )
    nay( cm.IndexedSeq[Int](654321).stepper )
    nay( cm.IndexedSeq[Int](654321).view.stepper )
    nay( cm.Iterable[Int](654321).stepper )
    nay( cm.LinkedHashMap[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( cm.LinkedHashMap[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( cm.LinkedHashSet[Int](654321).stepper )
    nay( cm.ListBuffer[Int](654321).stepper )
    nay( cm.ListMap[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( cm.ListMap[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( cm.LongMap[Int](9876543210L -> 654321).valuesStepper )
    nay( cm.Map[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( cm.Map[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( cm.OpenHashMap[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( cm.OpenHashMap[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( cm.PriorityQueue[Int](654321).stepper )
    nay( cm.Queue[Int](654321).stepper ) // Used to be `Good` in 2.12, in 2.13 `Queue` is no longer a `LinearSeq`
    nay( cm.Seq[Int](654321).stepper )
    nay( cm.Set[Int](654321).stepper )
    nay( cm.SortedSet[Int](654321).stepper )
    nay( cm.Stack[Int](654321).stepper ) // Used to be `Good` in 2.12, in 2.13 `Stack` is no longer a `LinearSeq`
    yay( cm.Iterable[Int](654321).to(Accumulator).stepper )
    nay( cm.TreeSet[Int](654321).stepper )
    nay( cm.UnrolledBuffer[Int](654321).stepper )
    nay( cm.WeakHashMap[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( cm.WeakHashMap[Int, Int](0xDEEDED -> 654321).valuesStepper )

    // Java 6 converters section

    // Concurrent section
    nay( cc.TrieMap[Int, Int](0xDEEDED -> 654321).keysStepper )
    nay( cc.TrieMap[Int, Int](0xDEEDED -> 654321).valuesStepper )
    nay( (cc.TrieMap[Int, Int](0xDEEDED -> 654321): cc.Map[Int, Int]).keysStepper )
    nay( (cc.TrieMap[Int, Int](0xDEEDED -> 654321): cc.Map[Int, Int]).valuesStepper )
  }

  @Test
  def shortWidening(): Unit = {
    implicit val spec = SpecCheck(_.isInstanceOf[IntStepper], x => s"$x should be an IntStepper")

    nay( Array[Short](654321.toShort).stepper )
    nay( (Array[Short](654321.toShort): cm.ArraySeq[Short]).stepper )

    //TODO: None of these currently work because there are no native Stepper implementations:

    //nay( ci.NumericRange(123456.toShort, 123458.toShort, 1.toShort).stepper )
    //nay( ((Array[Short](654321.toShort): cm.ArraySeq[Short]): cm.ArrayLike[Short, cm.ArraySeq[Short]]).stepper )
    //nay( (Array[Short](654321.toShort): cm.ArrayOps[Short]).stepper )
    //nay( cm.ResizableArray[Short](654321.toShort).stepper )
  }

  @Test
  def comprehensivelyLong(): Unit = {
    implicit val spec = SpecCheck(_.isInstanceOf[LongStepper])

    // Long-specific tests
    nay( ci.NumericRange(9876543210L, 9876543212L, 1L).stepper )
    nay( ci.LongMap[String](9876543210L -> "salmon").keysStepper )
    nay( cm.LongMap[String](9876543210L -> "salmon").keysStepper )
    nay( ci.LongMap[Double](9876543210L -> 3.14159).keysStepper )
    nay( cm.LongMap[Double](9876543210L -> 3.14159).keysStepper )
    nay( ci.LongMap[Int](9876543210L -> 654321).keysStepper )
    nay( cm.LongMap[Int](9876543210L -> 654321).keysStepper )

    // Collection section
    nay( co.Iterator[Long](0x123456789L).buffered.stepper )
    nay( co.IndexedSeq[Long](0x123456789L).stepper )
    nay( co.Iterable[Long](0x123456789L).stepper )
    nay( co.Iterable[Long](0x123456789L).view.stepper )
    nay( co.Iterator[Long](0x123456789L).stepper )
    nay( co.LinearSeq[Long](0x123456789L).stepper )
    nay( co.Map[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( co.Map[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( co.Seq[Long](0x123456789L).stepper )
    nay( co.Seq[Long](0x123456789L).view.stepper )
    nay( co.Set[Long](0x123456789L).stepper )
    nay( co.SortedMap[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( co.SortedMap[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( co.SortedSet[Long](0x123456789L).stepper )
    yay( co.Iterable[Long](0x123456789L).to(Accumulator).stepper )
    yay( (co.Iterator[Long](0x123456789L): co.IterableOnce[Long]).to(Accumulator).stepper )
    yay( co.Iterable[Long](0x123456789L).view.to(Accumulator).stepper )

    // Immutable section
    nay( ci.::(0x123456789L, Nil).stepper )
    nay( (ci.HashMap[Long, Long](1234567654321L -> 0x123456789L): ci.AbstractMap[Long, Long]).keysStepper )
    nay( (ci.HashMap[Long, Long](1234567654321L -> 0x123456789L): ci.AbstractMap[Long, Long]).valuesStepper )
    nay( ci.HashSet[Long](0x123456789L).stepper )
    nay( ci.IndexedSeq[Long](0x123456789L).stepper )
    nay( ci.IntMap[Long](123456 -> 0x123456789L).valuesStepper )
    nay( ci.Iterable[Long](0x123456789L).stepper )
    nay( ci.LinearSeq[Long](0x123456789L).stepper )
    nay( ci.List[Long](0x123456789L).stepper )
    nay( ci.ListMap[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( ci.ListMap[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( ci.ListSet[Long](0x123456789L).stepper )
    nay( ci.LongMap[Long](9876543210L -> 0x123456789L).keysStepper )
    nay( ci.LongMap[Long](9876543210L -> 0x123456789L).valuesStepper )
    nay( ci.Map[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( ci.Map[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( ci.Queue[Long](0x123456789L).stepper )
    nay( ci.Seq[Long](0x123456789L).stepper )
    nay( ci.Set[Long](0x123456789L).stepper )
    nay( ci.SortedMap[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( ci.SortedMap[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( ci.SortedSet[Long](0x123456789L).stepper )
    nay( ci.Stream[Long](0x123456789L).stepper )
    nay( ci.Stream[Long](0x123456789L).view.stepper )
    nay( ci.LazyList[Long](0x123456789L).stepper )
    nay( ci.LazyList[Long](0x123456789L).view.stepper )
    yay( ci.Iterable[Long](0x123456789L).to(Accumulator).stepper )
    nay( ci.TreeMap[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( ci.TreeMap[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( ci.TreeSet[Long](0x123456789L).stepper )
    nay( ci.Vector[Long](0x123456789L).stepper )

    // Mutable section
    nay( (cm.ArrayBuffer[Long](0x123456789L): cm.AbstractBuffer[Long]).stepper )
    nay( (cm.PriorityQueue[Long](0x123456789L): cm.AbstractIterable[Long]).stepper )
    nay( (cm.HashMap[Long, Long](1234567654321L -> 0x123456789L): cm.AbstractMap[Long, Long]).keysStepper )
    nay( (cm.HashMap[Long, Long](1234567654321L -> 0x123456789L): cm.AbstractMap[Long, Long]).valuesStepper )
    nay( (cm.ArrayBuffer[Long](0x123456789L): cm.AbstractSeq[Long]).stepper )
    nay( (cm.HashSet[Long](0x123456789L): cm.AbstractSet[Long]).stepper )
    nay( cm.AnyRefMap[String,Long]("fish" -> 0x123456789L).valuesStepper )
    nay( cm.ArrayBuffer[Long](0x123456789L).stepper )
    nay( (Array(0x123456789L): cm.ArraySeq[Long]).stepper )
    nay( cm.ArraySeq[Long](0x123456789L).stepper )
    nay( cm.ArrayStack[Long](0x123456789L).stepper )
    nay( (cm.ArrayBuffer[Long](0x123456789L): cm.Buffer[Long]).stepper )
    nay( cm.HashMap[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( cm.HashMap[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( cm.HashSet[Long](0x123456789L).stepper )
    nay( cm.IndexedSeq[Long](0x123456789L).stepper )
    nay( cm.IndexedSeq[Long](0x123456789L).view.stepper )
    nay( cm.Iterable[Long](0x123456789L).stepper )
    nay( cm.LinkedHashMap[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( cm.LinkedHashMap[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( cm.LinkedHashSet[Long](0x123456789L).stepper )
    nay( cm.ListBuffer[Long](0x123456789L).stepper )
    nay( cm.ListMap[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( cm.ListMap[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( cm.LongMap[Long](9876543210L -> 0x123456789L).keysStepper )
    nay( cm.LongMap[Long](9876543210L -> 0x123456789L).valuesStepper )
    nay( cm.Map[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( cm.Map[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( cm.OpenHashMap[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( cm.OpenHashMap[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( cm.PriorityQueue[Long](0x123456789L).stepper )
    nay( cm.Queue[Long](0x123456789L).stepper ) // Used to be `Good` in 2.12, in 2.13 `Queue` is no longer a `LinearSeq`
    nay( cm.Seq[Long](0x123456789L).stepper )
    nay( cm.Set[Long](0x123456789L).stepper )
    nay( cm.SortedSet[Long](0x123456789L).stepper )
    nay( cm.Stack[Long](0x123456789L).stepper ) // Used to be `Good` in 2.12, in 2.13 `Stack` is no longer a `LinearSeq`
    yay( cm.Iterable[Long](0x123456789L).to(Accumulator).stepper )
    nay( cm.TreeSet[Long](0x123456789L).stepper )
    nay( cm.UnrolledBuffer[Long](0x123456789L).stepper )
    nay( cm.WeakHashMap[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( cm.WeakHashMap[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )

    // Java 6 converters section

    // Concurrent section
    nay( cc.TrieMap[Long, Long](1234567654321L -> 0x123456789L).keysStepper )
    nay( cc.TrieMap[Long, Long](1234567654321L -> 0x123456789L).valuesStepper )
    nay( (cc.TrieMap[Long, Long](1234567654321L -> 0x123456789L): cc.Map[Long, Long]).keysStepper )
    nay( (cc.TrieMap[Long, Long](1234567654321L -> 0x123456789L): cc.Map[Long, Long]).valuesStepper )
  }

  @Test
  def comprehensivelySpecific(): Unit = {
    implicit val spec = SpecCheck(_.isInstanceOf[IntStepper], x => s"$x should be an IntStepper")

    nay( ci.NumericRange(277: Short, 279: Short, 1: Short).stepper )
    nay( ("salmon": ci.WrappedString).stepper )
  }
}
