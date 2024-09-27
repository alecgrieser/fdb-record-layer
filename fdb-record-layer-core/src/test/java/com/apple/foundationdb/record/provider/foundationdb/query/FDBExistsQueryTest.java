/*
 * FDBExistsQueryTest.java
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

package com.apple.foundationdb.record.provider.foundationdb.query;

import com.apple.foundationdb.record.Bindings;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.query.expressions.Comparisons;
import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.GraphExpansion;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.apple.foundationdb.record.query.plan.cascades.Reference;
import com.apple.foundationdb.record.query.plan.cascades.expressions.ExplodeExpression;
import com.apple.foundationdb.record.query.plan.cascades.predicates.ExistsPredicate;
import com.apple.foundationdb.record.query.plan.cascades.predicates.NotPredicate;
import com.apple.foundationdb.record.query.plan.cascades.predicates.QueryPredicate;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.apple.foundationdb.record.query.plan.cascades.values.ConstantObjectValue;
import com.apple.foundationdb.record.query.plan.cascades.values.FieldValue;
import com.apple.foundationdb.record.query.plan.cascades.values.LiteralValue;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.apple.test.BooleanSource;
import com.apple.test.Tags;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;

import javax.annotation.Nonnull;
import java.util.List;

import static com.apple.foundationdb.record.query.plan.ScanComparisons.anyValueComparison;
import static com.apple.foundationdb.record.query.plan.ScanComparisons.equalities;
import static com.apple.foundationdb.record.query.plan.ScanComparisons.range;
import static com.apple.foundationdb.record.query.plan.ScanComparisons.unbounded;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ListMatcher.only;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.anyPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.coveringIndexPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.flatMapPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.inComparandJoinPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.inUnionOnValuesPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.indexName;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.indexPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.indexPlanOf;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.isNotReverse;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.isReverse;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.mapPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.scanComparisons;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.scanPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.typeFilterPlan;

/**
 * Tests of queries using the Cascades planner that introduce on existential predicate.
 */
@Tag(Tags.RequiresFDB)
public class FDBExistsQueryTest extends FDBRecordStoreQueryTestBase {
    @Nonnull
    private static final String REPEATER_INDEX_NAME = "MySimpleRecord$repeater";
    @Nonnull
    private static final String REPEATER_NV3_INDEX_NAME = "MySimpleRecord$repeater+num_value_3_indexed";

    @Nonnull
    private Index repeaterIndex() {
        return new Index(REPEATER_INDEX_NAME, Key.Expressions.field("repeater", KeyExpression.FanType.FanOut));
    }

    @Nonnull
    private Index repeaterNumValue3Index() {
        return new Index(REPEATER_NV3_INDEX_NAME, Key.Expressions.concat(Key.Expressions.field("repeater", KeyExpression.FanType.FanOut), Key.Expressions.field("num_value_3_indexed")));
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    void repeaterEquals() {
        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context, metaDataBuilder -> metaDataBuilder.addIndex("MySimpleRecord", repeaterIndex()));

            final var queryParam = "x";
            final RecordQueryPlan plan = planGraph(() -> {
                var scanQun = FDBQueryGraphTestHelpers.fullTypeScan(recordStore.getRecordMetaData(), "MySimpleRecord");
                var explodeQun = explode(scanQun, "repeater");
                var exists = existsWhere(explodeQun, explodeQun.getFlowedObjectValue().withComparison(new Comparisons.ParameterComparison(Comparisons.Type.EQUALS, queryParam)));

                // Equivalent to a query like:
                //   SELECT rec_no FROM MySimpleRecord WHERE EXISTS (SELECT 1 FROM repeater WHERE value = $x)
                var resultQun = Quantifier.forEach(Reference.of(GraphExpansion.builder()
                        .addQuantifier(scanQun)
                        .addQuantifier(exists)
                        .addPredicate(new ExistsPredicate(exists.getAlias()))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(scanQun, "rec_no"))
                        .build()
                        .buildSelect()));
                return Reference.of(FDBQueryGraphTestHelpers.unordered(resultQun));
            });

            assertMatchesExactly(plan, mapPlan(coveringIndexPlan().where(indexPlanOf(
                    indexPlan()
                            .where(indexName(REPEATER_INDEX_NAME))
                            .and(scanComparisons(range("[EQUALS $" + queryParam + "]")))
            ))));
        }
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    void repeaterInList() {
        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context, metaDataBuilder -> metaDataBuilder.addIndex("MySimpleRecord", repeaterIndex()));

            final var queryParam = "x";
            final RecordQueryPlan plan = planGraph(() -> {
                var scanQun = FDBQueryGraphTestHelpers.fullTypeScan(recordStore.getRecordMetaData(), "MySimpleRecord");
                var explodeQun = explode(scanQun, "repeater");
                var exists = existsWhere(explodeQun, explodeQun.getFlowedObjectValue().withComparison(new Comparisons.ParameterComparison(Comparisons.Type.IN, queryParam)));

                // Equivalent to a query like:
                //   SELECT rec_no FROM MySimpleRecord WHERE EXISTS (SELECT 1 FROM repeater WHERE value IN $x)
                var resultQun = Quantifier.forEach(Reference.of(GraphExpansion.builder()
                        .addQuantifier(scanQun)
                        .addQuantifier(exists)
                        .addPredicate(new ExistsPredicate(exists.getAlias()))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(scanQun, "rec_no"))
                        .build()
                        .buildSelect()));
                return Reference.of(FDBQueryGraphTestHelpers.unordered(resultQun));
            });

            assertMatchesExactly(plan, flatMapPlan(
                    typeFilterPlan(scanPlan().where(scanComparisons(unbounded()))),
                    anyPlan()
            ));
        }
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    void repeaterEqualsElementInExplodedList() {
        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context, metaDataBuilder -> metaDataBuilder.addIndex("MySimpleRecord", repeaterIndex()));

            final List<Integer> values = ImmutableList.of(1, 3, 5);
            final var listConstant = ConstantObjectValue.of(CorrelationIdentifier.uniqueID(), "0", new Type.Array(false, Type.primitiveType(Type.TypeCode.INT, false)));
            final Bindings bindings = constantBindings(listConstant, values);
            final RecordQueryPlan plan = planGraph(() -> {
                var scanQun = FDBQueryGraphTestHelpers.fullTypeScan(recordStore.getRecordMetaData(), "MySimpleRecord");
                var explodeQun = explode(scanQun, "repeater");
                var explodedListElem = Quantifier.forEach(Reference.of(new ExplodeExpression(listConstant)));
                var exists = existsWhere(explodeQun, explodeQun.getFlowedObjectValue().withComparison(new Comparisons.ValueComparison(Comparisons.Type.EQUALS, explodedListElem.getFlowedObjectValue())));

                // Equivalent to a query like:
                //   SELECT rec_no FROM MySimpleRecord, VALUES (1, 2, 3) y WHERE EXISTS (SELECT 1 FROM repeater WHERE value = y)
                var resultQun = Quantifier.forEach(Reference.of(GraphExpansion.builder()
                        .addQuantifier(scanQun)
                        .addQuantifier(exists)
                        .addQuantifier(explodedListElem)
                        .addPredicate(new ExistsPredicate(exists.getAlias()))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(scanQun, "rec_no"))
                        .build()
                        .buildSelect()));
                return Reference.of(FDBQueryGraphTestHelpers.unordered(resultQun));
            }, bindings);

            assertMatchesExactly(plan, inComparandJoinPlan(mapPlan(coveringIndexPlan().where(indexPlanOf(
                    indexPlan()
                            .where(indexName(REPEATER_INDEX_NAME))
                            .and(scanComparisons(equalities(only(anyValueComparison()))))
            )))));
        }
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    @ParameterizedTest(name = "orderByColumnAfterRepeated[descending={0}]")
    @BooleanSource
    void orderByColumnAfterRepeated(boolean descending) {
        Assumptions.assumeTrue(useCascadesPlanner);
        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context, metaDataBuilder -> metaDataBuilder.addIndex("MySimpleRecord", repeaterNumValue3Index()));

            final String queryParam = "x";
            final RecordQueryPlan plan = planGraph(() -> {
                var scanQun = FDBQueryGraphTestHelpers.fullTypeScan(recordStore.getRecordMetaData(), "MySimpleRecord");
                var explodeQun = explode(scanQun, "repeater");
                var exists = existsWhere(explodeQun, explodeQun.getFlowedObjectValue().withComparison(new Comparisons.ParameterComparison(Comparisons.Type.EQUALS, queryParam)));

                // Equivalent to a query like:
                //   SELECT num_value_3_indexed, rec_no FROM MySimpleRecord WHERE EXISTS (SELECT 1 FROM repeater WHERE value = $x) ORDER BY num_value_3_indexed
                var resultQun = Quantifier.forEach(Reference.of(GraphExpansion.builder()
                        .addQuantifier(scanQun)
                        .addQuantifier(exists)
                        .addPredicate(new ExistsPredicate(exists.getAlias()))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(scanQun, "rec_no"))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(scanQun, "num_value_3_indexed"))
                        .build()
                        .buildSelect()));
                return Reference.of(FDBQueryGraphTestHelpers.orderedByFields(resultQun, descending, "num_value_3_indexed"));
            });

            assertMatchesExactly(plan, mapPlan(coveringIndexPlan().where(indexPlanOf(
                    indexPlan()
                            .where(indexName(REPEATER_NV3_INDEX_NAME))
                            .and(scanComparisons(range("[EQUALS $" + queryParam + "]"))))
                            .and(descending ? isReverse() : isNotReverse())
            )));
        }
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    @ParameterizedTest(name = "orderByColumnAfterRepeatedInList[descending={0}]")
    @BooleanSource
    void orderByColumnAfterRepeatedInList(boolean descending) {
        Assumptions.assumeTrue(useCascadesPlanner);
        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context, metaDataBuilder -> metaDataBuilder.addIndex("MySimpleRecord", repeaterNumValue3Index()));

            final List<Integer> values = ImmutableList.of(1, 3, 5);
            final var listConstant = ConstantObjectValue.of(CorrelationIdentifier.uniqueID(), "0", new Type.Array(false, Type.primitiveType(Type.TypeCode.INT, false)));
            final Bindings bindings = constantBindings(listConstant, values);
            final RecordQueryPlan plan = planGraph(() -> {
                var scanQun = FDBQueryGraphTestHelpers.fullTypeScan(recordStore.getRecordMetaData(), "MySimpleRecord");
                var explodeQun = explode(scanQun, "repeater");
                var explodedList = Quantifier.forEach(Reference.of(new ExplodeExpression(listConstant)));
                var exists = existsWhere(explodeQun, explodeQun.getFlowedObjectValue().withComparison(new Comparisons.ValueComparison(Comparisons.Type.EQUALS, explodedList.getFlowedObjectValue())));

                // Equivalent to a query like:
                //   SELECT num_value_3_indexed, rec_no FROM MySimpleRecord, VALUES (1, 2, 3) y WHERE EXISTS (SELECT 1 FROM repeater WHERE value = y) ORDER BY num_value_3_indexed
                var resultQun = Quantifier.forEach(Reference.of(GraphExpansion.builder()
                        .addQuantifier(scanQun)
                        .addQuantifier(exists)
                        .addQuantifier(explodedList)
                        .addPredicate(new ExistsPredicate(exists.getAlias()))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(scanQun, "rec_no"))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(scanQun, "num_value_3_indexed"))
                        .build()
                        .buildSelect()));
                return Reference.of(FDBQueryGraphTestHelpers.orderedByFields(resultQun, descending, "num_value_3_indexed"));
            }, bindings);

            assertMatchesExactly(plan, inUnionOnValuesPlan(mapPlan(coveringIndexPlan().where(indexPlanOf(
                    indexPlan()
                            .where(indexName(REPEATER_NV3_INDEX_NAME))
                            .and(scanComparisons(equalities(only(anyValueComparison()))))
                            .and(descending ? isReverse() : isNotReverse())
            )))));
        }
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    void doesNotExistAvoidsIndex() {
        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context, metaDataBuilder -> metaDataBuilder.addIndex("MySimpleRecord", repeaterIndex()));

            final String queryParam = "x";
            final RecordQueryPlan plan = planGraph(() -> {
                var scanQun = FDBQueryGraphTestHelpers.fullTypeScan(recordStore.getRecordMetaData(), "MySimpleRecord");
                var explodeQun = explode(scanQun, "repeater");
                var exists = existsWhere(explodeQun, explodeQun.getFlowedObjectValue().withComparison(new Comparisons.ParameterComparison(Comparisons.Type.EQUALS, queryParam)));

                // Equivalent to a query like:
                //   SELECT rec_no FROM MySimpleRecord WHERE NOT EXISTS (SELECT 1 FROM repeater WHERE value = $x)
                var resultQun = Quantifier.forEach(Reference.of(GraphExpansion.builder()
                        .addQuantifier(scanQun)
                        .addQuantifier(exists)
                        .addPredicate(NotPredicate.not(new ExistsPredicate(exists.getAlias())))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(scanQun, "rec_no"))
                        .build()
                        .buildSelect()));
                return Reference.of(FDBQueryGraphTestHelpers.unordered(resultQun));
            });

            // Does a full scan and then filters out values
            assertMatchesExactly(plan, flatMapPlan(
                    typeFilterPlan(scanPlan().where(scanComparisons(unbounded()))),
                    anyPlan()
            ));
        }
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    @ParameterizedTest
    @BooleanSource
    void doesNotExistWithOrder(boolean descending) {
        Assumptions.assumeTrue(useCascadesPlanner);
        try (FDBRecordContext context = openContext()) {
            openSimpleRecordStore(context, metaDataBuilder -> metaDataBuilder.addIndex("MySimpleRecord", repeaterNumValue3Index()));

            final String queryParam = "x";
            final RecordQueryPlan plan = planGraph(() -> {
                var scanQun = FDBQueryGraphTestHelpers.fullTypeScan(recordStore.getRecordMetaData(), "MySimpleRecord");
                var explodeQun = explode(scanQun, "repeater");
                var exists = existsWhere(explodeQun, explodeQun.getFlowedObjectValue().withComparison(new Comparisons.ParameterComparison(Comparisons.Type.EQUALS, queryParam)));

                // Equivalent to a query like:
                //   SELECT rec_no, num_value_3_indexed FROM MySimpleRecord WHERE NOT EXISTS (SELECT 1 FROM repeater WHERE value = $x) ORDER BY num_value_3_indexed
                var resultQun = Quantifier.forEach(Reference.of(GraphExpansion.builder()
                        .addQuantifier(scanQun)
                        .addQuantifier(exists)
                        .addPredicate(NotPredicate.not(new ExistsPredicate(exists.getAlias())))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(scanQun, "rec_no"))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(scanQun, "num_value_3_indexed"))
                        .build()
                        .buildSelect()));
                return Reference.of(FDBQueryGraphTestHelpers.orderedByFields(resultQun, descending, "num_value_3_indexed"));
            });

            // Does a full scan and then filters out values
            assertMatchesExactly(plan, flatMapPlan(
                    indexPlan().where(indexName("MySimpleRecord$num_value_3_indexed")).and(scanComparisons(unbounded())).and(descending ? isReverse() : isNotReverse()),
                    anyPlan()
            ));
        }
    }

    @Nonnull
    private static Quantifier.Existential existsWhere(@Nonnull Quantifier qun, QueryPredicate... predicates) {
        var select = GraphExpansion.builder()
                .addResultValue(LiteralValue.ofScalar(1L))
                .addQuantifier(qun)
                .addAllPredicates(List.of(predicates))
                .build()
                .buildSelect();
        return Quantifier.existential(Reference.of(select));
    }

    @Nonnull
    private static Quantifier.ForEach explode(@Nonnull Quantifier qun, @Nonnull String repeatedField) {
        return Quantifier.forEach(Reference.of(new ExplodeExpression(FieldValue.ofFieldNameAndFuseIfPossible(qun.getFlowedObjectValue(), repeatedField))));
    }

}
