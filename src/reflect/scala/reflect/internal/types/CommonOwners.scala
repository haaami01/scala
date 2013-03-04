package scala.reflect
package internal
package types

trait CommonOwners extends api.Types {
  self: SymbolTable =>

  import definitions._
  import TypesStats._

  /** The most deeply nested owner that contains all the symbols
    *  of thistype or prefixless typerefs/singletype occurrences in given type.
    */
  /*TODO private*/ def commonOwner(t: Type): Symbol = commonOwner(t :: Nil)

  /** The most deeply nested owner that contains all the symbols
    *  of thistype or prefixless typerefs/singletype occurrences in given list
    *  of types.
    */
  /*TODO private*/ def commonOwner(tps: List[Type]): Symbol = {
    if (tps.isEmpty) NoSymbol
    else {
      commonOwnerMap.clear()
      tps foreach (commonOwnerMap traverse _)
      if (commonOwnerMap.result ne null) commonOwnerMap.result else NoSymbol
    }
  }

  protected def commonOwnerMap: CommonOwnerMap = commonOwnerMapObj

  protected class CommonOwnerMap extends TypeTraverserWithResult[Symbol] {
    var result: Symbol = _

    def clear() { result = null }

    private def register(sym: Symbol) {
      // First considered type is the trivial result.
      if ((result eq null) || (sym eq NoSymbol))
        result = sym
      else
        while ((result ne NoSymbol) && (result ne sym) && !(sym isNestedIn result))
          result = result.owner
    }
    def traverse(tp: Type) = tp.normalize match {
      case ThisType(sym)                => register(sym)
      case TypeRef(NoPrefix, sym, args) => register(sym.owner) ; args foreach traverse
      case SingleType(NoPrefix, sym)    => register(sym.owner)
      case _                            => mapOver(tp)
    }
  }

  /*TODO private*/ lazy val commonOwnerMapObj = new CommonOwnerMap
}
