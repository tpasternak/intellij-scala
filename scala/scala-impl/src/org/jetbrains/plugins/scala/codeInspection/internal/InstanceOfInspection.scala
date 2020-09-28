package org.jetbrains.plugins.scala
package codeInspection
package internal

import org.jetbrains.plugins.scala.codeInspection.collections.{OperationOnCollectionInspection, Qualified, Simplification, SimplificationType, invocation, invocationText}
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScExpression, ScGenericCall}

class InstanceOfInspection extends OperationOnCollectionInspection {
  override def possibleSimplificationTypes: Array[SimplificationType] =
    Array(InstanceOfShouldBeIsInspection)
}

object InstanceOfShouldBeIsInspection extends SimplificationType() {
  override val hint: String = ScalaInspectionBundle.message("replace.with.is")

  private val `.isInstanceOf`: Qualified = invocation("isInstanceOf")

  override def getSimplification(expr: ScExpression): Option[Simplification] = expr match {
    case `.isInstanceOf`(base) && ScGenericCall(_, Seq(castType)) if base.`type`().map(_.widen).exists(castType.calcType.conforms) =>
      Some(replace(expr).withText(invocationText(base, "is") + s"[${castType.getText}]").highlightRef)
    case _ =>
      None
  }
}