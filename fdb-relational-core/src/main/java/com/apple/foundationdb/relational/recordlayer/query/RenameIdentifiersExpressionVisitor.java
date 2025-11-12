/*
 * RenameIdentifiersExpressionVisitor.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2025 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.relational.recordlayer.query;

import com.apple.foundationdb.record.query.plan.cascades.LinkedIdentitySet;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.apple.foundationdb.record.query.plan.cascades.Reference;
import com.apple.foundationdb.record.query.plan.cascades.expressions.DeleteExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.ExplodeExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.FullUnorderedScanExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.GroupByExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.InsertExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalDistinctExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalFilterExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalIntersectionExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalProjectionExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalSortExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalTypeFilterExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalUnionExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalUniqueExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.MatchableSortExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.RecursiveUnionExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.RelationalExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.RelationalExpressionVisitorWithDefaults;
import com.apple.foundationdb.record.query.plan.cascades.expressions.SelectExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.TableFunctionExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.TempTableInsertExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.TempTableScanExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.UpdateExpression;
import com.apple.foundationdb.record.query.plan.cascades.typing.RenameIdsTypeVisitor;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.UnaryOperator;

public class RenameIdentifiersExpressionVisitor implements RelationalExpressionVisitorWithDefaults<RelationalExpression> {
    private final UnaryOperator<String> idRenamer;

    private RenameIdentifiersExpressionVisitor(@Nonnull UnaryOperator<String> idRenamer) {
        this.idRenamer = idRenamer;
    }

    @Nonnull
    private Quantifier renameQuantifierIds(@Nonnull Quantifier qun) {
        final Reference reference = qun.getRangesOver();
        final LinkedIdentitySet<RelationalExpression> newExploratoryExpressions = new LinkedIdentitySet<>();
        for (RelationalExpression expression : reference.getExploratoryExpressions()) {
            newExploratoryExpressions.add(visit(expression));
        }
        final LinkedIdentitySet<RelationalExpression> newFinalExpressions = new LinkedIdentitySet<>();
        for (RelationalExpression expression : reference.getExploratoryExpressions()) {
            newFinalExpressions.add(visit(expression));
        }
        final Reference newReference = Reference.of(reference.getPlannerStage(), newExploratoryExpressions, newFinalExpressions);
        return qun.overNewReference(newReference);
    }

    @Nonnull
    private List<Quantifier> renameQuantifierIds(@Nonnull List<? extends Quantifier> quns) {
        return quns.stream()
                .map(this::renameQuantifierIds)
                .collect(ImmutableList.toImmutableList());
    }

    @Nonnull
    @Override
    public MatchableSortExpression visitMatchableSortExpression(@Nonnull final MatchableSortExpression element) {
        return new MatchableSortExpression(
                element.getSortParameterIds(),
                element.isReverse(),
                renameQuantifierIds(Iterables.getOnlyElement(element.getQuantifiers())));
    }

    @Nonnull
    @Override
    public InsertExpression visitInsertExpression(@Nonnull final InsertExpression element) {
        return new InsertExpression(
                (Quantifier.ForEach) renameQuantifierIds(Iterables.getOnlyElement(element.getQuantifiers())),
                idRenamer.apply(element.getTargetRecordType()),
                (Type.Record) RenameIdsTypeVisitor.renameIds(idRenamer, element.getTargetType()));
    }

    @Nonnull
    @Override
    public RecursiveUnionExpression visitRecursiveUnionExpression(@Nonnull final RecursiveUnionExpression element) {
        return new RecursiveUnionExpression(
                renameQuantifierIds(element.getInitialStateQuantifier()),
                renameQuantifierIds(element.getRecursiveStateQuantifier()),
                element.getTempTableScanAlias(),
                element.getTempTableInsertAlias(),
                element.getTraversalStrategy()
        );
    }

    @Nonnull
    @Override
    public LogicalSortExpression visitLogicalSortExpression(@Nonnull final LogicalSortExpression element) {
        return new LogicalSortExpression(, renameQuantifierIds(Iterables.getOnlyElement(element.getQuantifiers())));
    }

    @Nonnull
    @Override
    public LogicalTypeFilterExpression visitLogicalTypeFilterExpression(@Nonnull final LogicalTypeFilterExpression element) {
        return new LogicalTypeFilterExpression(
                element.getRecordTypes().stream().map(idRenamer).collect(ImmutableSet.toImmutableSet()),
                renameQuantifierIds(element.getInnerQuantifier()),
                RenameIdsTypeVisitor.renameIds(idRenamer, element.getResultType()));
    }

    @Nonnull
    @Override
    public LogicalUnionExpression visitLogicalUnionExpression(@Nonnull final LogicalUnionExpression element) {
        return new LogicalUnionExpression(renameQuantifierIds(element.getQuantifiers()));
    }

    @Nonnull
    @Override
    public TempTableScanExpression visitTempTableScanExpression(@Nonnull final TempTableScanExpression element) {
        return null;
    }

    @Nonnull
    @Override
    public LogicalIntersectionExpression visitLogicalIntersectionExpression(@Nonnull final LogicalIntersectionExpression element) {
        return LogicalIntersectionExpression.from(renameQuantifierIds(element.getQuantifiers()), element.getComparisonKeyProvidedOrderingParts());
    }

    @Nonnull
    @Override
    public TableFunctionExpression visitTableFunctionExpression(@Nonnull final TableFunctionExpression element) {
        return null;
    }

    @Nonnull
    @Override
    public LogicalUnionExpression visitLogicalUniqueExpression(@Nonnull final LogicalUniqueExpression element) {
        return new LogicalUnionExpression(renameQuantifierIds(element.getQuantifiers()));
    }

    @Nonnull
    @Override
    public LogicalProjectionExpression visitLogicalProjectionExpression(@Nonnull final LogicalProjectionExpression element) {
        return new LogicalProjectionExpression(, renameQuantifierIds(element.getInner()));
    }

    @Nonnull
    @Override
    public SelectExpression visitSelectExpression(@Nonnull final SelectExpression element) {
        return new SelectExpression(, renameQuantifierIds(element.getQuantifiers()), );
    }

    @Nonnull
    @Override
    public ExplodeExpression visitExplodeExpression(@Nonnull final ExplodeExpression element) {
        return null;
    }

    @Nonnull
    @Override
    public FullUnorderedScanExpression visitFullUnorderedScanExpression(@Nonnull final FullUnorderedScanExpression element) {
        return new FullUnorderedScanExpression(
                element.getRecordTypes().stream().map(idRenamer).collect(ImmutableSet.toImmutableSet()),
                RenameIdsTypeVisitor.renameIds(idRenamer, element.getResultType()),
                element.getAccessHints());
    }

    @Nonnull
    @Override
    public RelationalExpression visitGroupByExpression(@Nonnull final GroupByExpression element) {
        return new GroupByExpression(, , , renameQuantifierIds(element.getInnerQuantifier()));
    }

    @Nonnull
    @Override
    public RelationalExpression visitUpdateExpression(@Nonnull final UpdateExpression element) {
        return new UpdateExpression(
                (Quantifier.ForEach) renameQuantifierIds(Iterables.getOnlyElement(element.getQuantifiers())),
                idRenamer.apply(element.getTargetRecordType()),
                RenameIdsTypeVisitor.renameIds(idRenamer, element.getTargetType()),
        );
    }

    @Nonnull
    @Override
    public RelationalExpression visitLogicalDistinctExpression(@Nonnull final LogicalDistinctExpression element) {
        return new LogicalDistinctExpression(renameQuantifierIds(Iterables.getOnlyElement(element.getQuantifiers())));
    }

    @Nonnull
    @Override
    public RelationalExpression visitLogicalFilterExpression(@Nonnull final LogicalFilterExpression element) {
        return null;
    }

    @Nonnull
    @Override
    public RelationalExpression visitDeleteExpression(@Nonnull final DeleteExpression element) {
        return new DeleteExpression(
                (Quantifier.ForEach) renameQuantifierIds(Iterables.getOnlyElement(element.getQuantifiers())),
                idRenamer.apply(element.getTargetRecordType()));
    }

    @Nonnull
    @Override
    public RelationalExpression visitTempTableInsertExpression(@Nonnull final TempTableInsertExpression element) {
        return null;
    }

    @Nonnull
    @Override
    public RelationalExpression visitDefault(@Nonnull final RelationalExpression element) {
        return null;
    }

    @Nonnull
    public static RelationalExpression renameIdentifiers(@Nonnull UnaryOperator<String> idRenamer, @Nonnull RelationalExpression expression) {
        RenameIdentifiersExpressionVisitor visitor = new RenameIdentifiersExpressionVisitor(idRenamer);
        return visitor.visit(expression);
    }
}
