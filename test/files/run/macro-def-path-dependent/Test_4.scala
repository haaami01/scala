package test4

import scala.reflect.runtime.universe._
import scala.reflect.macros.BlackboxContext
import scala.reflect.api.Universe

object Test {
  def materializeTypeTag[T](u: Universe)(e: T) = macro materializeTypeTag_impl[T]

  def materializeTypeTag_impl[T: c.WeakTypeTag](c: BlackboxContext)(u: c.Expr[Universe])(e: c.Expr[T]): c.Expr[u.value.TypeTag[T]] = ???
}