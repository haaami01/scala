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

package scala.collection

import scala.language.higherKinds

/**
  * Trait that overrides operations on sequences in order
  * to take advantage of strict builders.
  */
trait StrictOptimizedSeqOps [+A, +CC[X] <: Seq[X], +C]
  extends Any
    with StrictOptimizedIterableOps[A, CC, C]
    with SeqOps[A, CC, C] {

  override def distinctBy[B](f: A => B): C = {
    val builder = newSpecificBuilder
    val seen = mutable.HashSet.empty[B]
    val it = this.iterator
    while (it.hasNext) {
      val next = it.next()
      if (seen.add(f(next))) builder += next
    }
    builder.result()
  }

  override def prepended[B >: A](elem: B): CC[B] = {
    val b = iterableFactory.newBuilder[B]
    if (knownSize >= 0) {
      b.sizeHint(size + 1)
    }
    b += elem
    b ++= this
    b.result()
  }

  override def appended[B >: A](elem: B): CC[B] = {
    val b = iterableFactory.newBuilder[B]
    if (knownSize >= 0) {
      b.sizeHint(size + 1)
    }
    b ++= this
    b += elem
    b.result()
  }

  override def appendedAll[B >: A](suffix: IterableOnce[B]): CC[B] =
    strictOptimizedConcat(suffix, iterableFactory.newBuilder)

  override def prependedAll[B >: A](prefix: IterableOnce[B]): CC[B] = {
    val b = iterableFactory.newBuilder[B]
    b ++= prefix
    b ++= this
    b.result()
  }

  // Must override here to ensure that `concat` is still an alias for `appendedAll` since it is overridden in `StrictOptimizedIterableOps`
  override def concat[B >: A](suffix: IterableOnce[B]): CC[B] = appendedAll(suffix)

  override def padTo[B >: A](len: Int, elem: B): CC[B] = {
    val b = iterableFactory.newBuilder[B]
    val L = size
    b.sizeHint(math.max(L, len))
    var diff = len - L
    b ++= this
    while (diff > 0) {
      b += elem
      diff -= 1
    }
    b.result()
  }

}
