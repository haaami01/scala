import org.scalacheck.{ Arbitrary, Prop, Properties }
import org.scalacheck.Arbitrary.{arbitrary, arbThrowable}
import org.scalacheck.Gen.oneOf
import org.scalacheck.Prop._
import org.scalacheck.Test.check
import Function.tupled

object Test extends Properties("Either") {
  implicit def arbitraryEither[X, Y](implicit xa: Arbitrary[X], ya: Arbitrary[Y]): Arbitrary[Either[X, Y]] =
    Arbitrary[Either[X, Y]](oneOf(arbitrary[X].map(Left(_)), arbitrary[Y].map(Right(_))))

  val prop_either1 = forAll((n: Int) => Left(n).fold(x => x, b => sys.error("fail")) == n)

  val prop_either2 = forAll((n: Int) => Right(n).fold(a => sys.error("fail"), x => x) == n)

  val prop_swap = forAll((e: Either[Int, Int]) => e match {
    case Left(a) => e.swap.right.get == a
    case Right(b) => e.swap.left.get == b
  })

  val prop_isLeftRight = forAll((e: Either[Int, Int]) => e.isLeft != e.isRight)

  object CheckLeftProjection {
    val prop_value = forAll((n: Int) => Left(n).left.get == n)

    val prop_getOrElse = forAll((e: Either[Int, Int], or: Int) => e.left.getOrElse(or) == (e match {
      case Left(a) => a
      case Right(_) => or
    }))

    val prop_forall = forAll((e: Either[Int, Int]) =>
      e.left.forall(_ % 2 == 0) == (e.isRight || e.left.get % 2 == 0))

    val prop_exists = forAll((e: Either[Int, Int]) =>
      e.left.exists(_ % 2 == 0) == (e.isLeft && e.left.get % 2 == 0))

    val prop_flatMapLeftIdentity = forAll((e: Either[Int, Int], n: Int, s: String) => {
      def f(x: Int) = if(x % 2 == 0) Left(s) else Right(s)
      Left(n).left.flatMap(f(_)) == f(n)})

    val prop_flatMapRightIdentity = forAll((e: Either[Int, Int]) => e.left.flatMap(Left(_)) == e)

    val prop_flatMapComposition = forAll((e: Either[Int, Int]) => {
      def f(x: Int) = if(x % 2 == 0) Left(x) else Right(x)
      def g(x: Int) = if(x % 7 == 0) Right(x) else Left(x)
      e.left.flatMap(f(_)).left.flatMap(g(_)) == e.left.flatMap(f(_).left.flatMap(g(_)))})

    val prop_mapIdentity = forAll((e: Either[Int, Int]) => e.left.map(x => x) == e)

    val prop_mapComposition = forAll((e: Either[String, Int]) => {
      def f(s: String) = s.toLowerCase
      def g(s: String) = s.reverse
      e.left.map(x => f(g(x))) == e.left.map(x => g(x)).left.map(f(_))})

    val prop_filter = forAll((e: Either[Int, Int], x: Int) => e.left.filter(_ % 2 == 0) ==
      (if(e.isRight || e.left.get % 2 != 0) None else Some(e)))

    val prop_seq = forAll((e: Either[Int, Int]) => e.left.toSeq == (e match {
      case Left(a) => Seq(a)
      case Right(_) => Seq.empty
    }))

    val prop_option = forAll((e: Either[Int, Int]) => e.left.toOption == (e match {
      case Left(a) => Some(a)
      case Right(_) => None
    }))
  }

  object CheckRightProjection {
    val prop_value = forAll((n: Int) => Right(n).right.get == n)

    val prop_getOrElse = forAll((e: Either[Int, Int], or: Int) => e.right.getOrElse(or) == (e match {
      case Left(_) => or
      case Right(b) => b
    }))

    val prop_forall = forAll((e: Either[Int, Int]) =>
      e.right.forall(_ % 2 == 0) == (e.isLeft || e.right.get % 2 == 0))

    val prop_exists = forAll((e: Either[Int, Int]) =>
      e.right.exists(_ % 2 == 0) == (e.isRight && e.right.get % 2 == 0))

    val prop_flatMapLeftIdentity = forAll((e: Either[Int, Int], n: Int, s: String) => {
      def f(x: Int) = if(x % 2 == 0) Left(s) else Right(s)
      Right(n).right.flatMap(f(_)) == f(n)})

    val prop_flatMapRightIdentity = forAll((e: Either[Int, Int]) => e.right.flatMap(Right(_)) == e)

    val prop_flatMapComposition = forAll((e: Either[Int, Int]) => {
      def f(x: Int) = if(x % 2 == 0) Left(x) else Right(x)
      def g(x: Int) = if(x % 7 == 0) Right(x) else Left(x)
      e.right.flatMap(f(_)).right.flatMap(g(_)) == e.right.flatMap(f(_).right.flatMap(g(_)))})

    val prop_mapIdentity = forAll((e: Either[Int, Int]) => e.right.map(x => x) == e)

    val prop_mapComposition = forAll((e: Either[Int, String]) => {
      def f(s: String) = s.toLowerCase
      def g(s: String) = s.reverse
      e.right.map(x => f(g(x))) == e.right.map(x => g(x)).right.map(f(_))})

    val prop_filter = forAll((e: Either[Int, Int], x: Int) => e.right.filter(_ % 2 == 0) ==
      (if(e.isLeft || e.right.get % 2 != 0) None else Some(e)))

    val prop_seq = forAll((e: Either[Int, Int]) => e.right.toSeq == (e match {
      case Left(_) => Seq.empty
      case Right(b) => Seq(b)
    }))

    val prop_option = forAll((e: Either[Int, Int]) => e.right.toOption == (e match {
      case Left(_) => None
      case Right(b) => Some(b)
    }))
  }


  object CheckLeftBiased {
    import Either.LeftBiased._

    val prop_value = forAll((n: Int) => Left(n).get == n)

    val prop_getOrElse = forAll((e: Either[Int, Int], or: Int) => e.getOrElse(or) == (e match {
      case Left(a) => a
      case Right(_) => or
    }))

    val prop_forall = forAll((e: Either[Int, Int]) =>
      e.forall(_ % 2 == 0) == (e.isRight || e.get % 2 == 0))

    val prop_exists = forAll((e: Either[Int, Int]) =>
      e.exists(_ % 2 == 0) == (e.isLeft && e.get % 2 == 0))

    val prop_flatMapLeftIdentity = forAll((e: Either[Int, Int], n: Int, s: String) => {
      def f(x: Int) = if(x % 2 == 0) Left(s) else Right(s)
      Left(n).flatMap(f(_)) == f(n)})

    val prop_flatMapRightIdentity = forAll((e: Either[Int, Int]) => e.flatMap(Left(_)) == e)

    val prop_flatMapComposition = forAll((e: Either[Int, Int]) => {
      def f(x: Int) = if(x % 2 == 0) Left(x) else Right(x)
      def g(x: Int) = if(x % 7 == 0) Right(x) else Left(x)
      e.flatMap(f(_)).flatMap(g(_)) == e.flatMap(f(_).flatMap(g(_)))})

    val prop_mapIdentity = forAll((e: Either[Int, Int]) => e.map(x => x) == e)

    val prop_mapComposition = forAll((e: Either[String, Int]) => {
      def f(s: String) = s.toLowerCase
      def g(s: String) = s.reverse
      e.map(x => f(g(x))) == e.map(x => g(x)).map(f(_))})

    val prop_seq = forAll((e: Either[Int, Int]) => e.toSeq == (e match {
      case Left(a) => Seq(a)
      case Right(_) => Seq.empty
    }))

    val prop_option = forAll((e: Either[Int, Int]) => e.toOption == (e match {
      case Left(a) => Some(a)
      case Right(_) => None
    }))

    val prop_withFilter = forAll((e: Either[Int, Int] ) => {
      if ( e.isLeft ) {
        if (e.get % 2 == 0) e.withFilter( _ % 2 == 0 ) == e;
        else {
          try { e.withFilter( _ % 2 == 0 ); false }
          catch { case _ : NoSuchElementException => true }
        }
      } else {
        e.withFilter(_ % 2 == 0) == e // right should be unchanged
      }
    })

    val prop_extractTuple = forAll((e: Either[(Int,Int,Int),Int]) => {
      if ( e.isLeft ) {
      e.get._1 == (for ( ( a, b, c ) <- e ) yield a).get
      } else {
        e == (for ( ( a, b, c ) <- e ) yield a) // right should be unchanged
      }
    })

    val prop_assignVariable = forAll((e: Either[(Int,Int,Int),Int]) => {
      if ( e.isLeft ) {
        e.get._2 == (for ( tup <- e; b = tup._2 ) yield b).get
      } else {
        e == (for ( tup <- e; b = tup._2 ) yield b) // right should be unchanged
      }
    })

    val prop_filterInFor = forAll((e: Either[Int,Int], mul : Int, passThru: Boolean) => {
      if ( e.isLeft && passThru) {
        e.map(_ * mul) == (for ( x <- e if passThru ) yield (mul * x))
      } else if ( e.isLeft && !passThru ) {
        try { for ( x <- e if passThru ) yield x; false }
        catch { case nse : NoSuchElementException => true; }
      } else {
        e == (for ( x <- e ) yield x) // right should be unchanged
      }
    })
  }

  object CheckLeftBiasedWithEmptyToken {
    val Bias = Either.LeftBiased.WithEmptyToken(-1);
    import Bias._;

    val prop_withFilter = forAll((e: Either[Int, Int] ) => {
      if ( e.isLeft ) {
        if (e.get % 2 == 0) e.withFilter( _ % 2 == 0 ) == e;
        else e.withFilter( _ % 2 == 0 ) == Right[Int,Int](-1)
      } else {
        e.withFilter(_ % 2 == 0) == e // right should be unchanged
      }
    })

    val prop_filterInFor = forAll((e: Either[Int,Int], mul : Int, passThru: Boolean) => {
      if ( e.isLeft && passThru) {
        e.map(_ * mul) == (for ( x <- e if passThru ) yield (mul * x))
      } else if ( e.isLeft && !passThru ) {
        (for ( x <- e if passThru ) yield x) == Right[Int,Int](-1)
      } else {
        e == (for ( x <- e ) yield x) // right should be unchanged
      }
    })
  }

  object CheckRightBiased {
    import Either.RightBiased._

    val prop_value = forAll((n: Int) => Right(n).get == n)

    val prop_getOrElse = forAll((e: Either[Int, Int], or: Int) => e.getOrElse(or) == (e match {
      case Left(_) => or
      case Right(b) => b
    }))

    val prop_forall = forAll((e: Either[Int, Int]) =>
      e.forall(_ % 2 == 0) == (e.isLeft || e.get % 2 == 0))

    val prop_exists = forAll((e: Either[Int, Int]) =>
      e.exists(_ % 2 == 0) == (e.isRight && e.get % 2 == 0))

    val prop_flatMapLeftIdentity = forAll((e: Either[Int, Int], n: Int, s: String) => {
      def f(x: Int) = if(x % 2 == 0) Left(s) else Right(s)
      Right(n).flatMap(f(_)) == f(n)})

    val prop_flatMapRightIdentity = forAll((e: Either[Int, Int]) => e.flatMap(Right(_)) == e)

    val prop_flatMapComposition = forAll((e: Either[Int, Int]) => {
      def f(x: Int) = if(x % 2 == 0) Left(x) else Right(x)
      def g(x: Int) = if(x % 7 == 0) Right(x) else Left(x)
      e.flatMap(f(_)).flatMap(g(_)) == e.flatMap(f(_).flatMap(g(_)))})

    val prop_mapIdentity = forAll((e: Either[Int, Int]) => e.map(x => x) == e)

    val prop_mapComposition = forAll((e: Either[Int, String]) => {
      def f(s: String) = s.toLowerCase
      def g(s: String) = s.reverse
      e.map(x => f(g(x))) == e.map(x => g(x)).map(f(_))})

    val prop_seq = forAll((e: Either[Int, Int]) => e.toSeq == (e match {
      case Left(_) => Seq.empty
      case Right(b) => Seq(b)
    }))

    val prop_option = forAll((e: Either[Int, Int]) => e.toOption == (e match {
      case Left(_) => None
      case Right(b) => Some(b)
    }))

    val prop_withFilter = forAll((e: Either[Int, Int] ) => {
      if ( e.isRight ) {
        if (e.get % 2 == 0) e.withFilter( _ % 2 == 0 ) == e;
        else {
          try { e.withFilter( _ % 2 == 0 ); false }
          catch { case _ : NoSuchElementException => true }
        }
      } else {
        e.withFilter(_ % 2 == 0) == e // left should be unchanged
      }
    })

    val prop_extractTuple = forAll((e: Either[Int,(Int,Int,Int)]) => {
      if ( e.isRight ) {
        e.get._1 == (for ( ( a, b, c ) <- e ) yield a).get
      } else {
        e == (for ( ( a, b, c ) <- e ) yield a) // left should be unchanged
      }
    })

    val prop_assignVariable = forAll((e: Either[Int,(Int,Int,Int)]) => {
      if ( e.isRight ) {
        e.get._2 == (for ( tup <- e; b = tup._2 ) yield b).get
      } else {
        e == (for ( tup <- e; b = tup._2 ) yield b) // left should be unchanged
      }
    })

    val prop_filterInFor = forAll((e: Either[Int,Int], mul : Int, passThru: Boolean) => {
      if ( e.isRight && passThru) {
        e.map(_ * mul) == (for ( x <- e if passThru ) yield (mul * x))
      } else if ( e.isRight && !passThru ) {
        try { for ( x <- e if passThru ) yield x; false }
        catch { case nse : NoSuchElementException => true; }
      } else {
        e == (for ( x <- e ) yield x) // left should be unchanged
      }
    })
  }

  object CheckRightBiasedWithEmptyToken {
    val Bias = Either.RightBiased.WithEmptyToken(-1);
    import Bias._;

    val prop_withFilter = forAll((e: Either[Int, Int] ) => {
      if ( e.isRight ) {
        if (e.get % 2 == 0) e.withFilter( _ % 2 == 0 ) == e;
        else e.withFilter( _ % 2 == 0 ) == Left[Int,Int](-1)
      } else {
        e.withFilter(_ % 2 == 0) == e // left should be unchanged
      }
    })

    val prop_filterInFor = forAll((e: Either[Int,Int], mul : Int, passThru: Boolean) => {
      if ( e.isRight && passThru) {
        e.map(_ * mul) == (for ( x <- e if passThru ) yield (mul * x))
      } else if ( e.isRight && !passThru ) {
        (for ( x <- e if passThru ) yield x) == Left[Int,Int](-1)
      } else {
        e == (for ( x <- e ) yield x) // left should be unchanged
      }
    })
  }

  val prop_Either_left = forAll((n: Int) => Left(n).left.get == n)

  val prop_Either_right = forAll((n: Int) => Right(n).right.get == n)

  val prop_Either_joinLeft = forAll((e: Either[Either[Int, Int], Int]) => e match {
    case Left(ee) => e.joinLeft == ee
    case Right(n) => e.joinLeft == Right(n)
  })

  val prop_Either_joinRight = forAll((e: Either[Int, Either[Int, Int]]) => e match {
    case Left(n) => e.joinRight == Left(n)
    case Right(ee) => e.joinRight == ee
  })

  val prop_Either_reduce = forAll((e: Either[Int, Int]) =>
    e.merge == (e match {
      case Left(a) => a
      case Right(a) => a
    }))

  /** Hard to believe I'm "fixing" a test to reflect B before A ... */
  val prop_Either_cond = forAll((c: Boolean, a: Int, b: Int) =>
    Either.cond(c, a, b) == (if(c) Right(a) else Left(b)))

  val tests = List(
      ("prop_either1", prop_either1),
      ("prop_either2", prop_either2),
      ("prop_swap", prop_swap),
      ("prop_isLeftRight", prop_isLeftRight),
      ("Left.prop_value", CheckLeftProjection.prop_value),
      ("Left.prop_getOrElse", CheckLeftProjection.prop_getOrElse),
      ("Left.prop_forall", CheckLeftProjection.prop_forall),
      ("Left.prop_exists", CheckLeftProjection.prop_exists),
      ("Left.prop_flatMapLeftIdentity", CheckLeftProjection.prop_flatMapLeftIdentity),
      ("Left.prop_flatMapRightIdentity", CheckLeftProjection.prop_flatMapRightIdentity),
      ("Left.prop_flatMapComposition", CheckLeftProjection.prop_flatMapComposition),
      ("Left.prop_mapIdentity", CheckLeftProjection.prop_mapIdentity),
      ("Left.prop_mapComposition", CheckLeftProjection.prop_mapComposition),
      ("Left.prop_filter", CheckLeftProjection.prop_filter),
      ("Left.prop_seq", CheckLeftProjection.prop_seq),
      ("Left.prop_option", CheckLeftProjection.prop_option),
      ("Right.prop_value", CheckRightProjection.prop_value),
      ("Right.prop_getOrElse", CheckRightProjection.prop_getOrElse),
      ("Right.prop_forall", CheckRightProjection.prop_forall),
      ("Right.prop_exists", CheckRightProjection.prop_exists),
      ("Right.prop_flatMapLeftIdentity", CheckRightProjection.prop_flatMapLeftIdentity),
      ("Right.prop_flatMapRightIdentity", CheckRightProjection.prop_flatMapRightIdentity),
      ("Right.prop_flatMapComposition", CheckRightProjection.prop_flatMapComposition),
      ("Right.prop_mapIdentity", CheckRightProjection.prop_mapIdentity),
      ("Right.prop_mapComposition", CheckRightProjection.prop_mapComposition),
      ("Right.prop_filter", CheckRightProjection.prop_filter),
      ("Right.prop_seq", CheckRightProjection.prop_seq),
      ("Right.prop_option", CheckRightProjection.prop_option),

      ("LeftBiased.prop_value", CheckLeftBiased.prop_value),
      ("LeftBiased.prop_getOrElse", CheckLeftBiased.prop_getOrElse),
      ("LeftBiased.prop_forall", CheckLeftBiased.prop_forall),
      ("LeftBiased.prop_exists", CheckLeftBiased.prop_exists),
      ("LeftBiased.prop_flatMapLeftIdentity", CheckLeftBiased.prop_flatMapLeftIdentity),
      ("LeftBiased.prop_flatMapRightIdentity", CheckLeftBiased.prop_flatMapRightIdentity),
      ("LeftBiased.prop_flatMapComposition", CheckLeftBiased.prop_flatMapComposition),
      ("LeftBiased.prop_mapIdentity", CheckLeftBiased.prop_mapIdentity),
      ("LeftBiased.prop_mapComposition", CheckLeftBiased.prop_mapComposition),
      ("LeftBiased.prop_seq", CheckLeftBiased.prop_seq),
      ("LeftBiased.prop_option", CheckLeftBiased.prop_option),
      ("LeftBiased.prop_withFilter", CheckLeftBiased.prop_withFilter),
      ("LeftBiased.prop_extractTuple", CheckLeftBiased.prop_extractTuple),
      ("LeftBiased.prop_assignVariable", CheckLeftBiased.prop_assignVariable),
      ("LeftBiased.prop_filterInFor", CheckLeftBiased.prop_filterInFor),

      ("LeftBiasedWithEmptyToken.prop_withFilter", CheckLeftBiasedWithEmptyToken.prop_withFilter),
      ("LeftBiasedWithEmptyToken.prop_filterInFor", CheckLeftBiasedWithEmptyToken.prop_filterInFor),

      ("RightBiased.prop_value", CheckRightBiased.prop_value),
      ("RightBiased.prop_getOrElse", CheckRightBiased.prop_getOrElse),
      ("RightBiased.prop_forall", CheckRightBiased.prop_forall),
      ("RightBiased.prop_exists", CheckRightBiased.prop_exists),
      ("RightBiased.prop_flatMapLeftIdentity", CheckRightBiased.prop_flatMapLeftIdentity),
      ("RightBiased.prop_flatMapRightIdentity", CheckRightBiased.prop_flatMapRightIdentity),
      ("RightBiased.prop_flatMapComposition", CheckRightBiased.prop_flatMapComposition),
      ("RightBiased.prop_mapIdentity", CheckRightBiased.prop_mapIdentity),
      ("RightBiased.prop_mapComposition", CheckRightBiased.prop_mapComposition),
      ("RightBiased.prop_seq", CheckRightBiased.prop_seq),
      ("RightBiased.prop_option", CheckRightBiased.prop_option),
      ("RightBiased.prop_withFilter", CheckRightBiased.prop_withFilter),
      ("RightBiased.prop_extractTuple", CheckRightBiased.prop_extractTuple),
      ("RightBiased.prop_assignVariable", CheckRightBiased.prop_assignVariable),
      ("RightBiased.prop_filterInFor", CheckRightBiased.prop_filterInFor),

      ("RightBiasedWithEmptyToken.prop_withFilter", CheckRightBiasedWithEmptyToken.prop_withFilter),
      ("RightBiasedWithEmptyToken.prop_filterInFor", CheckRightBiasedWithEmptyToken.prop_filterInFor),

      ("prop_Either_left", prop_Either_left),
      ("prop_Either_right", prop_Either_right),
      ("prop_Either_joinLeft", prop_Either_joinLeft),
      ("prop_Either_joinRight", prop_Either_joinRight),
      ("prop_Either_reduce", prop_Either_reduce),
      ("prop_Either_cond", prop_Either_cond)
    )

  for ((label, prop) <- tests) {
    property(label) = prop
  }
}
