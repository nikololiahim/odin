package org.polystat.odin.analysis.inlining

import cats.{Applicative, Traverse}
import cats.syntax.functor._
import cats.syntax.apply._
import com.github.tarao.nonempty.collection.NonEmpty
import higherkindness.droste.data.Fix
import monocle.{Lens, Optional, Prism, Traversal}
import monocle.macros.GenLens
import org.polystat.odin.core.ast._
import org.polystat.odin.core.ast.astparams.EOExprOnly
import org.polystat.odin.analysis.inlining.types._

object Optics {

  object prisms {

    val fixToEOSimpleAppWithLocator: Prism[EOExprOnly, EOSimpleAppWithLocator[EOExprOnly]] =
      Prism[EOExprOnly, EOSimpleAppWithLocator[EOExprOnly]](fix =>
        Fix.un(fix) match {
          case app: EOSimpleAppWithLocator[EOExprOnly] => Some(app)
          case _ => None
        }
      )(Fix(_))

    val fixToEOObj: Prism[EOExprOnly, EOObj[EOExprOnly]] =
      Prism[EOExprOnly, EOObj[EOExprOnly]](fix =>
        Fix.un(fix) match {
          case obj: EOObj[EOExprOnly] => Some(obj)
          case _ => None
        }
      )(Fix(_))

    val fixToEOCopy: Prism[EOExprOnly, EOCopy[EOExprOnly]] =
      Prism[EOExprOnly, EOCopy[EOExprOnly]](fix =>
        Fix.un(fix) match {
          case copy: EOCopy[EOExprOnly] => Some(copy)
          case _ => None
        }
      )(Fix(_))

    val fixToEODot: Prism[EOExprOnly, EODot[EOExprOnly]] =
      Prism[EOExprOnly, EODot[EOExprOnly]](fix =>
        Fix.un(fix) match {
          case dot: EODot[EOExprOnly] => Some(dot)
          case _ => None
        }
      )(Fix(_))

    val fixToEOArray: Prism[EOExprOnly, EOArray[EOExprOnly]] =
      Prism[EOExprOnly, EOArray[EOExprOnly]](fix =>
        Fix.un(fix) match {
          case arr: EOArray[EOExprOnly] => Some(arr)
          case _ => None
        }
      )(Fix(_))

  }

  object lenses {

    val focusFromBndToExpr: Optional[EOBnd[EOExprOnly], EOExprOnly] =
      Lens[EOBnd[EOExprOnly], EOExprOnly](bnd => bnd.expr)(expr => {
        case bnd: EOBndExpr[EOExprOnly] => bnd.copy(expr = expr)
        case bnd: EOAnonExpr[EOExprOnly] => bnd.copy(expr = expr)
      })

    val focusFromBndExprToExpr: Lens[EOBndExpr[EOExprOnly], EOExprOnly] =
      GenLens[EOBndExpr[EOExprOnly]](_.expr)

    val focusFromEOSimpleAppWithLocatorToLocator: Lens[EOSimpleAppWithLocator[EOExprOnly], BigInt] =
      GenLens[EOSimpleAppWithLocator[EOExprOnly]](_.locator)

    val focusFromEOObjToBndAttrs: Lens[EOObj[EOExprOnly], Vector[EOBndExpr[EOExprOnly]]] =
      GenLens[EOObj[EOExprOnly]](_.bndAttrs)

    val focusCopyTrg: Lens[EOCopy[EOExprOnly], EOExprOnly] =
      GenLens[EOCopy[EOExprOnly]](_.trg)

    val focusDotSrc: Lens[EODot[EOExprOnly], EOExprOnly] =
      GenLens[EODot[EOExprOnly]](_.src)

    val focusCopyArgs: Lens[EOCopy[EOExprOnly], CopyArgs] =
      GenLens[EOCopy[EOExprOnly]](_.args)

    val focusArrayElems: Lens[EOArray[EOExprOnly], Vector[EOBnd[EOExprOnly]]] =
      GenLens[EOArray[EOExprOnly]](_.elems)

  }

  object optionals {

    def vectorIndexOptional[A](i: Int): Optional[Vector[A], A] =
      Optional[Vector[A], A](_.lift(i))(item =>
        seq => if (seq.isDefinedAt(i)) seq.updated(i, item) else seq
      )

    def vectorFindOptional[A](pred: A => Boolean): Optional[Vector[A], A] =
      Optional[Vector[A], A](_.find(pred))(item =>
        seq => {
          val index = seq.indexWhere(pred)
          if (index == -1)
            seq
          else seq.updated(index, item)
        }
      )

    def nonEmptyVectorIndexOptional[A](
      i: Int
    ): Optional[NonEmpty[A, Vector[A]], A] =
      Optional[NonEmpty[A, Vector[A]], A](_.lift(i))(item =>
        seq => if (seq.isDefinedAt(i)) seq.updated(i, item) else seq
      )

    def focusBndAttrWithName(
      name: EONamedBnd
    ): Optional[EOObj[EOExprOnly], EOExprOnly] =
      Optional[EOObj[EOExprOnly], EOExprOnly](obj =>
        obj.bndAttrs.find(_.bndName == name).map(_.expr)
      )(expr =>
        obj =>
          lenses
            .focusFromEOObjToBndAttrs
            .andThen(
              vectorFindOptional[EOBndExpr[EOExprOnly]](_.bndName == name)
            )
            .andThen(lenses.focusFromBndExprToExpr)
            .replaceOption(expr)(obj)
            .getOrElse(obj)
      )

    def focusCopyArgAtIndex(
      i: Int
    ): Optional[EOCopy[EOExprOnly], EOExprOnly] =
      Optional[EOCopy[EOExprOnly], EOExprOnly](copy =>
        copy.args.lift(i).map(_.expr)
      )(expr =>
        copy => {
          lenses
            .focusCopyArgs
            .andThen(nonEmptyVectorIndexOptional[EOBnd[EOExprOnly]](i))
            .andThen(lenses.focusFromBndToExpr)
            .replaceOption(expr)(copy)
            .getOrElse(copy)
        }
      )

    def focusArrayElemAtIndex(
      i: Int
    ): Optional[EOArray[EOExprOnly], EOExprOnly] =
      Optional[EOArray[EOExprOnly], EOExprOnly](arr =>
        arr.elems.lift(i).map(_.expr)
      )(expr =>
        arr =>
          lenses
            .focusArrayElems
            .andThen(vectorIndexOptional[EOBnd[EOExprOnly]](i))
            .andThen(lenses.focusFromBndToExpr)
            .replaceOption(expr)(arr)
            .getOrElse(arr)
      )

  }

  object traversals {

    def nonEmptyVectorTraversal[A]: Traversal[NonEmpty[A, Vector[A]], A] =
      new Traversal[NonEmpty[A, Vector[A]], A] {

        override def modifyA[F[_]: Applicative](f: A => F[A])(
          s: NonEmpty[A, Vector[A]]
        ): F[NonEmpty[A, Vector[A]]] = {
          Applicative[F].map2(
            f(s.head),
            Traverse[Vector].traverse(s.tail)(f)
          )((head, tail) => NonEmpty[Vector[A]](head, tail: _*))
        }

      }

    val eoCopy: Traversal[EOCopy[EOExprOnly], EOExprOnly] =
      new Traversal[EOCopy[EOExprOnly], EOExprOnly] {

        override def modifyA[F[_]: Applicative](f: EOExprOnly => F[EOExprOnly])(
          s: EOCopy[EOExprOnly]
        ): F[EOCopy[EOExprOnly]] =
          (
            f(s.trg),
            nonEmptyVectorTraversal[EOBnd[EOExprOnly]]
              .andThen(lenses.focusFromBndToExpr)
              .modifyA(f)(s.args),
          ).mapN(EOCopy.apply)

      }

    val eoObjBndAttrs: Traversal[EOObj[EOExprOnly], EOExprOnly] =
      new Traversal[EOObj[EOExprOnly], EOExprOnly] {

        override def modifyA[F[_]: Applicative](f: EOExprOnly => F[EOExprOnly])(
          s: EOObj[EOExprOnly]
        ): F[EOObj[EOExprOnly]] =
          Traversal
            .fromTraverse[Vector, EOBndExpr[EOExprOnly]]
            .andThen(lenses.focusFromBndExprToExpr)
            .modifyA(f)(s.bndAttrs)
            .map(bnds => s.copy(bndAttrs = bnds))

      }

    val eoArrayElems: Traversal[EOArray[EOExprOnly], EOExprOnly] =
      new Traversal[EOArray[EOExprOnly], EOExprOnly] {

        override def modifyA[F[_]: Applicative](f: EOExprOnly => F[EOExprOnly])(
          s: EOArray[EOExprOnly]
        ): F[EOArray[EOExprOnly]] =
          Traversal
            .fromTraverse[Vector, EOBnd[EOExprOnly]]
            .andThen(lenses.focusFromBndToExpr)
            .modifyA(f)(s.elems)
            .map(elems => s.copy(elems = elems))

      }

  }

}
