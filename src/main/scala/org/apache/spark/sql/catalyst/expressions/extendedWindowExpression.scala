/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.sql.catalyst.analysis.UnresolvedException
import org.apache.spark.sql.catalyst.trees.UnaryNode
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.types.LongType

object PatchedWindowFunction {
  def apply(expr: Expression) = new PatchedWindowFunction(expr, expr.children)
}

case class PatchedWindowFunction(expr: Expression, children: Seq[Expression]) extends Expression with WindowFunction {
  lazy val func = expr.withNewChildren(children)
  override def dataType: DataType = func.dataType
  override def foldable: Boolean = func.foldable
  override def nullable: Boolean = func.nullable
  override lazy val resolved = func.resolved
  override def eval(input: Row = null): EvaluatedType = 
    func.eval(input).asInstanceOf[EvaluatedType]
  override def toString: String = func.toString
  override def newInstance(): WindowFunction = 
    throw new UnresolvedException(this, "newInstance")
  
  // Noop Window Function implementation.
  override def init(): Unit = {}
  override def reset(): Unit = {}
  override def prepareInputParameters(input: Row): AnyRef = null
  override def update(input: AnyRef): Unit = {}
  override def batchUpdate(inputs: Array[AnyRef]): Unit = {}
  override def evaluate(): Unit = {}
  override def get(index: Int): Any = null
}

/**
 * A pivot window expression is a window expression that first processes all rows for a partition
 * and then returns an indexed result for the entire partition.
 */
abstract class PivotWindowExpression extends AggregateExpression {
  self: Product =>
  override type EvaluatedType = Array[Any]
}

/**
 * Base class for a rank expression.
 */
abstract class RankLikeExpression extends AggregateExpression {
  self: Product => 
  override def dataType: DataType = LongType
  override def foldable: Boolean = false
  override def nullable: Boolean = false
  override def toString: String = s"${this.nodeName}()"
}

/**
 * Base class for a rank function. This function should always be evaluated in a running fashion:
 * i.e. in a ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW frame.
 */
abstract class RankLikeFunction extends AggregateFunction {
  self: Product =>
  var counter: Long = 0L
  var value: Long = 0L
  var last: Row = EmptyRow
  val extractor = new InterpretedProjection(children)
  override def eval(input: Row): Any = value
}

/**
 * Place Holder object for an Unresolved Window Sort Order.
 */
case object UnresolvedWindowSortOrder extends Seq[Expression] {
  def length = 0
  def apply(index: Int) = throw new NoSuchElementException
  def iterator = Iterator.empty
}

case class Rank(children: Seq[Expression]) extends RankLikeExpression {
  override def newInstance(): AggregateFunction = RankFunction(children, this)
}

case class RankFunction(override val children: Seq[Expression], base: AggregateExpression) extends RankLikeFunction {
  def update(input: Row): Unit = {
    val current = extractor(input)
    counter += 1
    if (current != last) {
      last = current
      value = counter
    }
  }
}

case class DenseRank(children: Seq[Expression]) extends RankLikeExpression {
  override def newInstance(): AggregateFunction = DenseRankFunction(children, this)
}

case class DenseRankFunction(override val children: Seq[Expression], base: AggregateExpression) extends RankLikeFunction {
  def update(input: Row): Unit = {
    val current = extractor(input)
    if (current != last) {
      counter += 1
      last = current
      value = counter
    }
  }
}

/**
 * Helper extractor for making working with frame boundaries easier.
 */
object FrameBoundaryExtractor {
  def unapply(boundary: FrameBoundary): Option[Int] = boundary match {
    case CurrentRow => Some(0)
    case ValuePreceding(offset) => Some(-offset)
    case ValueFollowing(offset) => Some(offset)
    case _ => None
  }
}