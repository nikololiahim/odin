package org.polystat.odin.analysis.stateaccess

import cats.data.EitherNel
import higherkindness.droste.data.Fix
import org.polystat.odin.analysis.utils.Abstract
import org.polystat.odin.analysis.utils.inlining._
import org.polystat.odin.core.ast._
import org.polystat.odin.core.ast.astparams.EOExprOnly

import scala.annotation.tailrec

object DetectStateAccess {

  type ObjInfo = ObjectInfo[ParentInfo[MethodInfo, ObjectInfo], MethodInfo]

  case class State(
    containerName: String,
    statePath: List[String],
    states: Vector[EONamedBnd]
  )

  case class StateChange(
    method: EONamedBnd,
    state: EONamedBnd,
    statePath: List[String]
  )

  def collectNestedStates(mainParent: String)(
    subTree: Inliner.CompleteObjectTree,
    depth: Int
  ): Vector[State] = {
    val currentLvlStateNames = subTree
      .info
      .bnds
      .collect {
        case BndItself(
               EOBndExpr(
                 bndName,
                 EOSimpleAppWithLocator("memory" | "cage", _)
               )
             ) => bndName
      }

    Vector(
      State(
        mainParent,
        subTree.info.fqn.names.toList.drop(depth),
        currentLvlStateNames
      )
    ) ++
      subTree
        .children
        .flatMap(t => collectNestedStates(mainParent)(t._2, depth))
  }

  def accumulateParentState(tree: Map[EONamedBnd, Inliner.CompleteObjectTree])(
    currentParentLink: Option[ParentInfo[MethodInfo, ObjectInfo]],
    existingStates: Vector[EONamedBnd] = Vector()
  ): Vector[State] = {
    currentParentLink match {
      case Some(parentLink) =>
        val parentObj = parentLink.linkToParent.getOption(tree).get
        val currentObjName = parentObj.info.name.name.name
        val currentLvlStateNames = parentObj
          .info
          .bnds
          .collect {
            case BndItself(
                   EOBndExpr(
                     bndName,
                     EOSimpleAppWithLocator("memory" | "cage", _)
                   )
                 ) if !existingStates.contains(bndName) =>
              bndName
          }
        val currentLvlState =
          State(currentObjName, List(), currentLvlStateNames)
        val nestedStates = parentObj
          .children
          .flatMap(c =>
            collectNestedStates(parentObj.info.name.name.name)(
              c._2,
              parentObj.info.depth.toInt + 1
            )
          )
          .toVector

        Vector(currentLvlState) ++ nestedStates ++
          accumulateParentState(tree)(
            parentObj.info.parentInfo,
            existingStates ++ currentLvlStateNames
          )

      case None => Vector()
    }
  }

  def getAccessedStates(method: (EONamedBnd, MethodInfo)): List[StateChange] = {
    @tailrec
    def hasSelfAsSource(dot: EODot[EOExprOnly]): Boolean = {
      Fix.un(dot.src) match {
        case EOSimpleAppWithLocator("self", x) if x == 0 => true
        case innerDot @ EODot(_, _) => hasSelfAsSource(innerDot)
        case _ => false
      }
    }

    def buildDotChain(dot: EODot[EOExprOnly]): List[String] =
      Fix.un(dot.src) match {
        case EOSimpleAppWithLocator("self", x) if x == 0 => List()
        case innerDot @ EODot(_, _) =>
          buildDotChain(innerDot).appended(innerDot.name)
        case _ => List()
      }

    val binds = method._2.body.bndAttrs

    def processDot(
      innerDot: EODot[Fix[EOExpr]],
      state: String
    ): List[StateChange] = {
      val stateName = EOAnyNameBnd(LazyName(state))
      val containerChain = buildDotChain(innerDot)

      List(StateChange(method._1, stateName, containerChain))
    }

    Abstract.foldAst[List[StateChange]](binds) {
      case EOCopy(Fix(dot @ EODot(Fix(innerDot @ EODot(_, state)), _)), _)
           if hasSelfAsSource(dot) =>
        processDot(innerDot, state)

      case dot @ EODot(_, state) if hasSelfAsSource(dot) =>
        processDot(dot, state)
    }
  }

  def detectStateAccesses(
    tree: Map[EONamedBnd, Inliner.CompleteObjectTree]
  )(obj: (EONamedBnd, Inliner.CompleteObjectTree)): List[String] = {
    val availableParentStates =
      accumulateParentState(tree)(obj._2.info.parentInfo)
    val accessedStates = obj._2.info.methods.flatMap(getAccessedStates)
    val results =
      for {
        StateChange(targetMethod, state, accessedStatePath) <- accessedStates
        State(baseClass, statePath, changedStates) <- availableParentStates
      } yield
        if (changedStates.contains(state) && statePath == accessedStatePath) {
          val objName = obj._2.info.fqn.names.toList.mkString(".")
          val stateName = state.name.name
          val method = targetMethod.name.name
          val container = statePath.prepended(baseClass).mkString(".")

          List(
            f"Method '$method' of object '$objName' directly accesses state '$stateName' of base class '$container'"
          )
        } else List()

    results.toList.flatten
  }

  def analyze[F[_]](
    originalTree: Map[EONamedBnd, Inliner.CompleteObjectTree]
  ): EitherNel[String, List[String]] = {
    def helper(
      tree: Map[EONamedBnd, Inliner.CompleteObjectTree]
    ): List[String] =
      tree
        .filter(_._2.info.parentInfo.nonEmpty)
        .flatMap(detectStateAccesses(originalTree))
        .toList

    def recurse(
      tree: Map[EONamedBnd, Inliner.CompleteObjectTree]
    ): List[String] = {
      val currentRes = helper(tree)
      val children = tree.values.map(_.children)

      currentRes ++ children.flatMap(recurse)
    }

    Right(recurse(originalTree))
  }

}
