
object Foo {
  val i,j = 3
  //for (x = Some(i); if x == j) yield 42  //t7473.scala:4: error: '<-' expected but '=' found.
  // evil postfix!
  (for (x = Some(i); if x == j) yield 42) toList
}
