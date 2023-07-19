/*
 * PushReferencedFieldsThroughUniqueRule.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2019 Apple Inc. and the FoundationDB project authors
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

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.query.plan.cascades.CascadesRule;
import com.apple.foundationdb.record.query.plan.cascades.CascadesRuleCall;
import com.apple.foundationdb.record.query.plan.cascades.ExpressionRef;
import com.apple.foundationdb.record.query.plan.cascades.PlannerRule.PreOrderRule;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.apple.foundationdb.record.query.plan.cascades.ReferencedFieldsConstraint;
import com.apple.foundationdb.record.query.plan.cascades.ReferencedFieldsConstraint.ReferencedFields;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalUniqueExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.RelationalExpression;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.BindingMatcher;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.PlannerBindings;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.ReferenceMatchers;
import com.google.common.collect.ImmutableSet;

import javax.annotation.Nonnull;
import java.util.Optional;

import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ListMatcher.exactly;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.QuantifierMatchers.forEachQuantifierOverRef;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RelationalExpressionMatchers.logicalUniqueExpression;

/**
 * A rule that pushes a {@link ReferencedFieldsConstraint} through a {@link LogicalUniqueExpression}.
 */
@API(API.Status.EXPERIMENTAL)
@SuppressWarnings("PMD.TooManyStaticImports")
public class PushReferencedFieldsThroughUniqueRule extends CascadesRule<LogicalUniqueExpression> implements PreOrderRule {
    private static final BindingMatcher<ExpressionRef<? extends RelationalExpression>> lowerRefMatcher = ReferenceMatchers.anyRef();
    private static final BindingMatcher<Quantifier.ForEach> innerQuantifierMatcher = forEachQuantifierOverRef(lowerRefMatcher);
    private static final BindingMatcher<LogicalUniqueExpression> root =
            logicalUniqueExpression(exactly(innerQuantifierMatcher));

    public PushReferencedFieldsThroughUniqueRule() {
        super(root, ImmutableSet.of(ReferencedFieldsConstraint.REFERENCED_FIELDS));
    }

    @Override
    public void onMatch(@Nonnull final CascadesRuleCall call) {
        final PlannerBindings bindings = call.getBindings();
        final ExpressionRef<? extends RelationalExpression> lowerRef = bindings.get(lowerRefMatcher);
        final Optional<ReferencedFields> referencedFieldsOptional = call.getPlannerConstraint(ReferencedFieldsConstraint.REFERENCED_FIELDS);

        if (!referencedFieldsOptional.isPresent()) {
            return;
        }

        call.pushConstraint(lowerRef,
                ReferencedFieldsConstraint.REFERENCED_FIELDS,
                referencedFieldsOptional.get());
    }
}