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

package org.apache.spark.sql.catalyst.analysis

import scala.collection.mutable

import org.apache.spark.sql.catalyst.ProjectingInternalRow
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference, AttributeSet, Expression, ExprId, Literal, V2ExpressionUtils}
import org.apache.spark.sql.catalyst.plans.logical.{Assignment, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.catalyst.util.RowDeltaUtils._
import org.apache.spark.sql.catalyst.util.WriteDeltaProjections
import org.apache.spark.sql.connector.catalog.SupportsRowLevelOperations
import org.apache.spark.sql.connector.expressions.FieldReference
import org.apache.spark.sql.connector.write.{RowLevelOperation, RowLevelOperationInfoImpl, RowLevelOperationTable, SupportsDelta}
import org.apache.spark.sql.connector.write.RowLevelOperation.Command
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation
import org.apache.spark.sql.util.CaseInsensitiveStringMap
import org.apache.spark.util.ArrayImplicits._

trait RewriteRowLevelCommand extends Rule[LogicalPlan] {

  protected def buildOperationTable(
      table: SupportsRowLevelOperations,
      command: Command,
      options: CaseInsensitiveStringMap): RowLevelOperationTable = {
    val info = RowLevelOperationInfoImpl(command, options)
    val operation = table.newRowLevelOperationBuilder(info).build()
    RowLevelOperationTable(table, operation)
  }

  protected def buildRelationWithAttrs(
      relation: DataSourceV2Relation,
      table: RowLevelOperationTable,
      metadataAttrs: Seq[AttributeReference],
      rowIdAttrs: Seq[AttributeReference] = Nil): DataSourceV2Relation = {

    val attrs = dedupAttrs(relation.output ++ rowIdAttrs ++ metadataAttrs)
    relation.copy(table = table, output = attrs)
  }

  protected def dedupAttrs(attrs: Seq[AttributeReference]): Seq[AttributeReference] = {
    val exprIds = mutable.Set.empty[ExprId]
    attrs.flatMap { attr =>
      if (exprIds.contains(attr.exprId)) {
        None
      } else {
        exprIds += attr.exprId
        Some(attr)
      }
    }
  }

  protected def resolveRequiredMetadataAttrs(
      relation: DataSourceV2Relation,
      operation: RowLevelOperation): Seq[AttributeReference] = {

    V2ExpressionUtils.resolveRefs[AttributeReference](
      operation.requiredMetadataAttributes.toImmutableArraySeq,
      relation)
  }

  protected def resolveRowIdAttrs(
      relation: DataSourceV2Relation,
      operation: SupportsDelta): Seq[AttributeReference] = {

    val rowIdAttrs = V2ExpressionUtils.resolveRefs[AttributeReference](
      operation.rowId.toImmutableArraySeq,
      relation)

    val nullableRowIdAttrs = rowIdAttrs.filter(_.nullable)
    if (nullableRowIdAttrs.nonEmpty) {
      throw QueryCompilationErrors.nullableRowIdError(nullableRowIdAttrs)
    }

    rowIdAttrs
  }

  protected def resolveAttrRef(name: String, plan: LogicalPlan): AttributeReference = {
    V2ExpressionUtils.resolveRef[AttributeReference](FieldReference(name), plan)
  }

  protected def deltaDeleteOutput(
      rowAttrs: Seq[Attribute],
      rowIdAttrs: Seq[Attribute],
      metadataAttrs: Seq[Attribute],
      originalRowIdValues: Seq[Expression] = Seq.empty): Seq[Expression] = {
    val rowValues = buildDeltaDeleteRowValues(rowAttrs, rowIdAttrs)
    Seq(Literal(DELETE_OPERATION)) ++ rowValues ++ metadataAttrs ++ originalRowIdValues
  }

  private def buildDeltaDeleteRowValues(
      rowAttrs: Seq[Attribute],
      rowIdAttrs: Seq[Attribute]): Seq[Expression] = {

    // nullify all row attrs that don't belong to row ID
    val rowIdAttSet = AttributeSet(rowIdAttrs)
    rowAttrs.map {
      case attr if rowIdAttSet.contains(attr) => attr
      case attr => Literal(null, attr.dataType)
    }
  }

  protected def deltaInsertOutput(
      assignments: Seq[Assignment],
      metadataAttrs: Seq[Attribute],
      originalRowIdValues: Seq[Expression] = Seq.empty): Seq[Expression] = {
    val rowValues = assignments.map(_.value)
    val extraNullValues = (metadataAttrs ++ originalRowIdValues).map(e => Literal(null, e.dataType))
    Seq(Literal(INSERT_OPERATION)) ++ rowValues ++ extraNullValues
  }

  protected def deltaUpdateOutput(
      assignments: Seq[Assignment],
      metadataAttrs: Seq[Attribute],
      originalRowIdValues: Seq[Expression]): Seq[Expression] = {
    val rowValues = assignments.map(_.value)
    Seq(Literal(UPDATE_OPERATION)) ++ rowValues ++ metadataAttrs ++ originalRowIdValues
  }

  protected def buildWriteDeltaProjections(
      plan: LogicalPlan,
      rowAttrs: Seq[Attribute],
      rowIdAttrs: Seq[Attribute],
      metadataAttrs: Seq[Attribute]): WriteDeltaProjections = {

    val rowProjection = if (rowAttrs.nonEmpty) {
      Some(newLazyProjection(plan, rowAttrs))
    } else {
      None
    }

    val rowIdProjection = newLazyRowIdProjection(plan, rowIdAttrs)

    val metadataProjection = if (metadataAttrs.nonEmpty) {
      Some(newLazyProjection(plan, metadataAttrs))
    } else {
      None
    }

    WriteDeltaProjections(rowProjection, rowIdProjection, metadataProjection)
  }

  private def newLazyProjection(
      plan: LogicalPlan,
      attrs: Seq[Attribute]): ProjectingInternalRow = {

    val colOrdinals = attrs.map(attr => findColOrdinal(plan, attr.name))
    val schema = DataTypeUtils.fromAttributes(attrs)
    ProjectingInternalRow(schema, colOrdinals)
  }

  // if there are assignment to row ID attributes, original values are projected as special columns
  // this method honors such special columns if present
  private def newLazyRowIdProjection(
      plan: LogicalPlan,
      rowIdAttrs: Seq[Attribute]): ProjectingInternalRow = {

    val colOrdinals = rowIdAttrs.map { attr =>
      val originalValueIndex = findColOrdinal(plan, ORIGINAL_ROW_ID_VALUE_PREFIX + attr.name)
      if (originalValueIndex != -1) originalValueIndex else findColOrdinal(plan, attr.name)
    }
    val schema = DataTypeUtils.fromAttributes(rowIdAttrs)
    ProjectingInternalRow(schema, colOrdinals)
  }

  private def findColOrdinal(plan: LogicalPlan, name: String): Int = {
    plan.output.indexWhere(attr => conf.resolver(attr.name, name))
  }

  protected def buildOriginalRowIdValues(
      rowIdAttrs: Seq[Attribute],
      assignments: Seq[Assignment]): Seq[Alias] = {
    val rowIdAttrSet = AttributeSet(rowIdAttrs)
    assignments.flatMap { assignment =>
      val key = assignment.key.asInstanceOf[Attribute]
      val value = assignment.value
      if (rowIdAttrSet.contains(key) && !key.semanticEquals(value)) {
        Some(Alias(key, ORIGINAL_ROW_ID_VALUE_PREFIX + key.name)())
      } else {
        None
      }
    }
  }

  // generates output attributes with fresh expr IDs and correct nullability for nodes like Expand
  // and MergeRows where there are multiple outputs for each input row
  protected def generateExpandOutput(
      attrs: Seq[Attribute],
      outputs: Seq[Seq[Expression]]): Seq[Attribute] = {

    // build a correct nullability map for output attributes
    // an attribute is nullable if at least one output may produce null
    val nullabilityMap = attrs.indices.map { index =>
      index -> outputs.exists(output => output(index).nullable)
    }.toMap

    attrs.zipWithIndex.map { case (attr, index) =>
      AttributeReference(attr.name, attr.dataType, nullabilityMap(index), attr.metadata)()
    }
  }
}
