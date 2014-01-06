import scala.reflect.macros.{BlackboxContext => Ctx}

object Impls {
  def foo(c: Ctx) = {
    import c.universe._
    val body = Ident(TermName("IDoNotExist"))
    c.Expr[Int](body)
  }
}

object Macros {
  def foo = macro Impls.foo
}