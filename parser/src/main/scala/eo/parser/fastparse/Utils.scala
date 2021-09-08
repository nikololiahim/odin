package eo.parser.fastparse

import com.github.tarao.nonempty.collection.NonEmpty
import eo.core.ast.{EOAnonExpr, EOBnd, EOBndExpr, EOCopy, EODot, EOExpr}
import eo.core.ast.astparams.EOExprOnly
import higherkindness.droste.data.Fix

object Utils {
  def createNonEmpty(objs: Seq[EOBnd[EOExprOnly]])
  : NonEmpty[EOBnd[EOExprOnly], Vector[EOBnd[EOExprOnly]]] = {
    NonEmpty.from(objs) match {
      case Some(value) => value.toVector
      case None => throw new Exception("1 or more arguments expected, got 0.")
    }
  }

   private def extractEOExpr(bnd: EOBnd[EOExprOnly]): EOExprOnly = {
    bnd match {
      case EOAnonExpr(expr) => expr
      case EOBndExpr(_, expr) => expr
    }
  }

  // TODO: rewrite so that the information
  //  about names of bindings is not lost
  def createInverseDot(id: String,
                               args: Vector[EOBnd[EOExprOnly]]): EOExprOnly = {
    if (args.tail.nonEmpty) {
      Fix[EOExpr](
        EOCopy(
          Fix[EOExpr](EODot(extractEOExpr(args.head), id)),
          createNonEmpty(args.tail)
        )
      )
    } else {
      Fix[EOExpr](EODot(extractEOExpr(args.head), id))
    }
  }


}

