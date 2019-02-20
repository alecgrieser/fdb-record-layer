/*
 * FilterWithFieldWithComparisonRule.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2018 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.query.plan.temp.rules;

import com.apple.foundationdb.annotations.API;
import com.apple.foundationdb.record.IndexScanType;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.expressions.FieldKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.metadata.expressions.ThenKeyExpression;
import com.apple.foundationdb.record.query.expressions.Comparisons;
import com.apple.foundationdb.record.query.expressions.FieldWithComparison;
import com.apple.foundationdb.record.query.plan.ScanComparisons;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryIndexPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryScanPlan;
import com.apple.foundationdb.record.query.plan.temp.PlannerRule;
import com.apple.foundationdb.record.query.plan.temp.PlannerRuleCall;
import com.apple.foundationdb.record.query.plan.temp.SingleExpressionRef;
import com.apple.foundationdb.record.query.plan.temp.expressions.LogicalFilterExpression;
import com.apple.foundationdb.record.query.plan.temp.matchers.ExpressionMatcher;
import com.apple.foundationdb.record.query.plan.temp.matchers.TypeMatcher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * A rewrite rule that turns a logical filter on the results of a full scan (without a sort) into an index scan on
 * a compatibly ordered index.
 *
 * Like the {@link com.apple.foundationdb.record.query.plan.RecordQueryPlanner}
 */
@API(API.Status.EXPERIMENTAL)
public class FilterWithFieldWithComparisonRule extends PlannerRule<LogicalFilterExpression> {
    private static final ExpressionMatcher<Comparisons.Comparison> comparisonMatcher = TypeMatcher.of(Comparisons.Comparison.class);
    private static final ExpressionMatcher<FieldWithComparison> filterMatcher = TypeMatcher.of(FieldWithComparison.class, comparisonMatcher);
    private static final ExpressionMatcher<RecordQueryScanPlan> scanMatcher = TypeMatcher.of(RecordQueryScanPlan.class);
    private static final ExpressionMatcher<LogicalFilterExpression> root = TypeMatcher.of(LogicalFilterExpression.class, filterMatcher, scanMatcher);

    public FilterWithFieldWithComparisonRule() {
        super(root);
    }

    @Nonnull
    @Override
    public ChangesMade onMatch(@Nonnull PlannerRuleCall call) {
        if (!call.get(scanMatcher).hasFullRecordScan()) {
            return ChangesMade.NO_CHANGE; // already has some bounds on the scan
        }

        final FieldWithComparison singleField = call.get(filterMatcher);
        final Comparisons.Comparison comparison = call.get(comparisonMatcher);
        final ScanComparisons scanComparisons = ScanComparisons.from(comparison);
        if (scanComparisons == null) {
            // This comparison cannot be accomplished with a single scan.
            return ChangesMade.NO_CHANGE;
        }

        FieldKeyExpression field = firstField(call.getContext().getCommonPrimaryKey());
        if (field != null && Objects.equals(singleField.getFieldName(), field.getFieldName())) {
            call.yield(SingleExpressionRef.of(new RecordQueryScanPlan(scanComparisons, false)));
            return ChangesMade.MADE_CHANGES;
        }

        for (Index index : call.getContext().getIndexes()) {
            field = firstField(index.getRootExpression());
            if (field != null && Objects.equals(singleField.getFieldName(), field.getFieldName())) {
                call.yield(SingleExpressionRef.of(
                        new RecordQueryIndexPlan(index.getName(), IndexScanType.BY_VALUE, scanComparisons, false)));
                return ChangesMade.MADE_CHANGES;
            }
        }
        // couldn't find an index
        return ChangesMade.NO_CHANGE;
    }

    @Nullable
    private FieldKeyExpression firstField(@Nullable KeyExpression indexExpression) {
        if (indexExpression == null) {
            return null;
        }

        if (indexExpression instanceof ThenKeyExpression) {
            ThenKeyExpression then = (ThenKeyExpression) indexExpression;
            // First column will do it all or not.
            indexExpression = then.getChildren().get(0);
        }

        if (indexExpression instanceof FieldKeyExpression) {
            return (FieldKeyExpression)indexExpression;
        } else {
            return null;
        }
    }

}
