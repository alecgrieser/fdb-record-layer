/*
 * PullUpNullOnEmptyRule.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2024 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.query.plan.cascades.rules;

import com.apple.foundationdb.record.query.plan.cascades.ExplorationCascadesRuleCall;
import com.apple.foundationdb.record.query.plan.cascades.ExplorationCascadesRule;
import com.apple.foundationdb.record.query.plan.cascades.GraphExpansion;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.apple.foundationdb.record.query.plan.cascades.expressions.RelationalExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.SelectExpression;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.BindingMatcher;
import com.google.common.collect.Iterables;

import javax.annotation.Nonnull;

import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ListMatcher.exactly;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.QuantifierMatchers.forEachQuantifierWithDefaultOnEmptyOverRef;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ReferenceMatchers.anyRef;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RelationalExpressionMatchers.selectExpression;

/**
 * A rewrite rule that splits a {@link SelectExpression} expression that ranges over a child with a {@link Quantifier}
 * that has {@code null-on-empty} semantics into two parts:
 * <ol>
 *     <li> a lower {@link SelectExpression} expression that ranges over the old child with a normal {@link Quantifier},
 *     i.e. one without {@code null-on-empty} semantics.
 *     <li> an upper {@link SelectExpression} expression that ranges over the lower the {@link SelectExpression} with a
 *     {@link Quantifier} that has {@code null-on-empty} semantics, and the same set of predicates as contained by the
 *     lower {@link SelectExpression}.
 * </ol>
 * The purpose of this rewrite rule is to create a variation that has a better chance of matching an index (since the lower
 * {@link SelectExpression} has a normal {@link Quantifier}), the purpose of the upper {@link SelectExpression} is to reapply
 * the predicates on top of its {@link Quantifier} with {@code null-on-empty} giving them a chance of acting on any {@code null}s
 * produced by this quantifier, which guarantees semantic equivalency.
 */
public class PullUpNullOnEmptyRule extends ExplorationCascadesRule<SelectExpression> {

    @Nonnull
    private static final BindingMatcher<Quantifier.ForEach> defaultOnEmptyQuantifier = forEachQuantifierWithDefaultOnEmptyOverRef(anyRef());

    @Nonnull
    private static final BindingMatcher<SelectExpression> root = selectExpression(exactly(defaultOnEmptyQuantifier));

    public PullUpNullOnEmptyRule() {
        super(root);
    }

    @Override
    public void onMatch(@Nonnull final ExplorationCascadesRuleCall call) {
        final var bindings = call.getBindings();
        final var selectExpression = bindings.get(root);
        final var quantifier = bindings.get(defaultOnEmptyQuantifier);
        final var childrenExpressions = quantifier.getRangesOver().getExploratoryExpressions();
        boolean pullUpDesired = false;
        for (final var childExpression : childrenExpressions) {
            final var childClassification =
                    classifyExpression(selectExpression, quantifier, childExpression);
            if (childClassification == ExpressionClassification.DO_NOT_PULL_UP) {
                return;
            }
            if (childClassification == ExpressionClassification.PULL_UP) {
                pullUpDesired = true;
            }
        }
        if (!pullUpDesired) {
            return;
        }
       
        final var newChildrenQuantifier =
                Quantifier.forEachBuilder()
                        .withAlias(quantifier.getAlias())
                        .build(quantifier.getRangesOver());

        // Create the lower select expression.
        final var newSelectExpression = call.memoizeExploratoryExpression(GraphExpansion.builder()
                .addQuantifier(newChildrenQuantifier)
                .addAllPredicates(selectExpression.getPredicates())
                .build().buildSimpleSelectOverQuantifier(newChildrenQuantifier));

        // Create the upper select expression.
        final var topLevelSelectQuantifier = Quantifier.forEachBuilder().from(quantifier).build(newSelectExpression);
        final var topLevelSelectExpression = GraphExpansion.builder()
                .addQuantifier(topLevelSelectQuantifier)
                .build().buildSelectWithResultValue(selectExpression.getResultValue());

        call.yieldExploratoryExpression(topLevelSelectExpression);
    }

    private ExpressionClassification classifyExpression(@Nonnull final SelectExpression selectOnTopExpression,
                                                        @Nonnull final Quantifier.ForEach quantifier,
                                                        @Nonnull final RelationalExpression expression) {
        if (!(expression instanceof SelectExpression)) {
            return ExpressionClassification.DO_NOT_CARE;
        }

        final var selectExpression = (SelectExpression)expression;
        if (selectExpression.getQuantifiers().size() > 1) {
            return ExpressionClassification.PULL_UP;
        }
        if (!Iterables.getOnlyElement(selectExpression.getQuantifiers()).getAlias().equals(quantifier.getAlias())) {
            return ExpressionClassification.PULL_UP;
        }

        // if all predicates are not the same, bail out, otherwise, we can pull up.
        final var predicates = selectOnTopExpression.getPredicates();
        final var otherPredicates = selectExpression.getPredicates();
        return !predicates.equals(otherPredicates)
               ? ExpressionClassification.PULL_UP
               : ExpressionClassification.DO_NOT_PULL_UP;
    }

    private enum ExpressionClassification {
        DO_NOT_CARE,
        DO_NOT_PULL_UP,
        PULL_UP
    }
}
