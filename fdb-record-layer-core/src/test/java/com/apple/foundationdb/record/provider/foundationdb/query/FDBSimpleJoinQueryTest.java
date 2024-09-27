/*
 * FDBSimpleJoinQueryTest.java
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

package com.apple.foundationdb.record.provider.foundationdb.query;

import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.TestRecordsParentChildRelationshipProto;
import com.apple.foundationdb.record.provider.foundationdb.FDBQueriedRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.query.RecordQuery;
import com.apple.foundationdb.record.query.expressions.Comparisons;
import com.apple.foundationdb.record.query.expressions.Query;
import com.apple.foundationdb.record.query.plan.cascades.Column;
import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.GraphExpansion;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.apple.foundationdb.record.query.plan.cascades.Reference;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalSortExpression;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.BindingMatcher;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.apple.foundationdb.record.query.plan.cascades.values.ConstantObjectValue;
import com.apple.foundationdb.record.query.plan.cascades.values.FieldValue;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryLoadByKeysPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.test.Tags;
import com.google.protobuf.Message;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.apple.foundationdb.record.TestHelpers.assertDiscardedNone;
import static com.apple.foundationdb.record.query.plan.ScanComparisons.anyValueComparison;
import static com.apple.foundationdb.record.query.plan.ScanComparisons.equalities;
import static com.apple.foundationdb.record.query.plan.ScanComparisons.unbounded;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ListMatcher.only;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.PrimitiveMatchers.equalsObject;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.anyPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.coveringIndexPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.fetchFromPartialRecordPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.flatMapPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.indexName;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.indexPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.indexPlanOf;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.isNotReverse;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.isReverse;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.predicatesFilterPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.recordTypes;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.scanComparisons;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.typeFilterPlan;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests of the planning and execution of queries that produce an in-join plan.
 */
@Tag(Tags.RequiresFDB)
public class FDBSimpleJoinQueryTest extends FDBRecordStoreQueryTestBase {
    private void openJoinRecordStore(FDBRecordContext context) {
        createOrOpenRecordStore(context, RecordMetaData.build(TestRecordsParentChildRelationshipProto.getDescriptor()));
    }

    /**
     * Verify that simple binding joins in parent/child relationships work.
     */
    @Test
    public void joinChildToParent() {
        createJoinRecords(false);

        RecordQuery parentQuery = RecordQuery.newBuilder()
                .setRecordType("MyParentRecord")
                .setFilter(Query.field("str_value_indexed").equalsValue("even"))
                .build();
        RecordQuery childQuery = RecordQuery.newBuilder()
                .setRecordType("MyChildRecord")
                .setFilter(Query.field("parent_rec_no").equalsParameter("parent"))
                .build();
        RecordQueryPlan parentPlan = planQuery(parentQuery);
        RecordQueryPlan childPlan = planQuery(childQuery);

        try (FDBRecordContext context = openContext()) {
            openJoinRecordStore(context);
            RecordCursor<FDBQueriedRecord<Message>> childCursor = RecordCursor.flatMapPipelined(
                    ignore -> recordStore.executeQuery(parentPlan),
                    (rec, ignore) -> {
                        TestRecordsParentChildRelationshipProto.MyParentRecord.Builder parentRec =
                                TestRecordsParentChildRelationshipProto.MyParentRecord.newBuilder();
                        parentRec.mergeFrom(rec.getRecord());
                        EvaluationContext childContext = EvaluationContext.forBinding("parent", parentRec.getRecNo());
                        return childPlan.execute(recordStore, childContext);
                    }, null, 10);
            RecordCursor<String> resultsCursor = childCursor.map(rec -> {
                TestRecordsParentChildRelationshipProto.MyChildRecord.Builder childRec = TestRecordsParentChildRelationshipProto.MyChildRecord.newBuilder();
                childRec.mergeFrom(rec.getRecord());
                return childRec.getStrValue();
            });
            assertEquals(Arrays.asList("2.1", "2.2", "2.3", "4.1", "4.2", "4.3"), resultsCursor.asList().join());
            assertDiscardedNone(context);
        }
    }

    /**
     * Verify that simple binding joins in parent/child relationships work.
     */
    @Test
    public void joinParentToChild() {
        createJoinRecords(true);

        RecordQuery parentQuery = RecordQuery.newBuilder()
                .setRecordType("MyParentRecord")
                .setFilter(Query.field("str_value_indexed").equalsValue("even"))
                .build();
        RecordQueryPlan parentPlan = planQuery(parentQuery);
        RecordQueryPlan childPlan = new RecordQueryLoadByKeysPlan("children");

        try (FDBRecordContext context = openContext()) {
            openJoinRecordStore(context);
            RecordCursor<FDBQueriedRecord<Message>> childCursor = RecordCursor.flatMapPipelined(
                    ignore -> recordStore.executeQuery(parentPlan),
                    (rec, ignore) -> {
                        TestRecordsParentChildRelationshipProto.MyParentRecord.Builder parentRec =
                                TestRecordsParentChildRelationshipProto.MyParentRecord.newBuilder();
                        parentRec.mergeFrom(rec.getRecord());
                        EvaluationContext childContext = EvaluationContext.forBinding("children", parentRec.getChildRecNosList().stream()
                                .map(Tuple::from)
                                .collect(Collectors.toList()));
                        return childPlan.execute(recordStore, childContext);
                    }, null, 10);
            RecordCursor<String> resultsCursor = childCursor.map(rec -> {
                TestRecordsParentChildRelationshipProto.MyChildRecord.Builder childRec = TestRecordsParentChildRelationshipProto.MyChildRecord.newBuilder();
                childRec.mergeFrom(rec.getRecord());
                return childRec.getStrValue();
            });
            assertEquals(Arrays.asList("2.1", "2.2", "2.3", "4.1", "4.2", "4.3"), resultsCursor.asList().join());
            assertDiscardedNone(context);
        }
    }

    static class JoinOrdering {
        private final String name;
        private final Function<Quantifier, LogicalSortExpression> addSort;
        private final BindingMatcher<RecordQueryPlan> reverseMatcher;
        private final Matcher<Iterable<? extends String>> resultMatcher;

        public JoinOrdering(String name, Function<Quantifier, LogicalSortExpression> addSort, BindingMatcher<RecordQueryPlan> reverseMatcher, Matcher<Iterable<? extends String>> resultMatcher) {
            this.name = name;
            this.addSort = addSort;
            this.reverseMatcher = reverseMatcher;
            this.resultMatcher = resultMatcher;
        }

        public LogicalSortExpression withSort(@Nonnull Quantifier qun) {
            return addSort.apply(qun);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Nonnull
    static Stream<JoinOrdering> joinParentAndChildCascades() {
        return Stream.of(
                new JoinOrdering("unordered", FDBQueryGraphTestHelpers::unordered, anyPlan(), Matchers.containsInAnyOrder("2.1", "2.2", "2.3", "4.1", "4.2", "4.3")),
                new JoinOrdering("parent_rec_no ASC", qun -> FDBQueryGraphTestHelpers.orderedByFields(qun, false,"parent_rec_no"), isNotReverse(), Matchers.contains("2.1", "2.2", "2.3", "4.1", "4.2", "4.3")),
                new JoinOrdering("parent_rec_no DESC", qun -> FDBQueryGraphTestHelpers.orderedByFields(qun, true, "parent_rec_no"), isReverse(), Matchers.contains("4.1", "4.2", "4.3", "2.1", "2.2", "2.3"))
        );
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    @ParameterizedTest(name = "joinParentAndChildCascades[{0}]")
    @MethodSource
    void joinParentAndChildCascades(@Nonnull JoinOrdering joinOrdering) {
        Assumptions.assumeTrue(useCascadesPlanner);
        createJoinRecords(false);

        try (FDBRecordContext context = openContext()) {
            openJoinRecordStore(context);
            final RecordQueryPlan plan = planGraph(() -> {
                final var parentTypeQun = FDBQueryGraphTestHelpers.fullTypeScan(recordStore.getRecordMetaData(), "MyParentRecord");
                final var childTypeQun = FDBQueryGraphTestHelpers.fullTypeScan(recordStore.getRecordMetaData(), "MyChildRecord");

                // Equivalent to something like:
                //   SELECT p.rec_no AS parent_rec_no, c.rec_no AS child_rec_no, c.str_value
                //       FROM MyParentRecord p, MyChildRecord c
                //       WHERE p.str_value_indexed = "even" AND p.rec_no = c.parent_rec_no
                final var selectQun = Quantifier.forEach(Reference.of(GraphExpansion.builder()
                        .addQuantifier(parentTypeQun)
                        .addQuantifier(childTypeQun)
                        .addPredicate(FieldValue.ofFieldName(parentTypeQun.getFlowedObjectValue(), "str_value_indexed")
                                .withComparison(new Comparisons.SimpleComparison(Comparisons.Type.EQUALS, "even")))
                        .addPredicate(FieldValue.ofFieldName(childTypeQun.getFlowedObjectValue(), "parent_rec_no")
                                .withComparison(new Comparisons.ValueComparison(Comparisons.Type.EQUALS, FieldValue.ofFieldName(parentTypeQun.getFlowedObjectValue(), "rec_no"))))
                        .addResultColumn(Column.of(Optional.of("parent_rec_no"), FieldValue.ofFieldName(parentTypeQun.getFlowedObjectValue(), "rec_no")))
                        .addResultColumn(Column.of(Optional.of("child_rec_no"), FieldValue.ofFieldName(parentTypeQun.getFlowedObjectValue(), "rec_no")))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(childTypeQun.getFlowedObjectValue(), "str_value"))
                        .build()
                        .buildSelect()));
                return Reference.of(joinOrdering.withSort(selectQun));
            });

            // This plan is a bit suspect. It's evaluating the predicate on MyParentRecord in the filter on the
            // inner part of the flat map instead of the outer. This means it will reject a bunch of records as
            // part of query execution.
            // This appears to be caused by the way PartitionBinarySelectRule is partitioning predicates. It attempts
            // plan this query as a join between:
            //   SELECT p.rec_no, c.rec_no, c.str_value
            //     FROM (SELECT * FROM MyParentRecord) p,
            //          (SELECT * FROM MyChildRecord WHERE parent_rec_no = p.rec_no AND p.str_value_indexed = "even") c
            // But the better rewrite is as:
            //   SELECT p.rec_no, c.rec_no, c.str_value
            //     FROM (SELECT * FROM MyParentRecord WHERE str_value_indexed = "even") p,
            //          (SELECT * FROM MyChildRecord WHERE parent_rec_no = p.rec_no) c
            assertMatches(plan, flatMapPlan(
                    typeFilterPlan(scanComparisons(unbounded()))
                            .where(recordTypes(only(equalsObject("MyParentRecord"))))
                            .and(joinOrdering.reverseMatcher),
                    fetchFromPartialRecordPlan(
                            predicatesFilterPlan(
                                    coveringIndexPlan()
                                            .where(indexPlanOf(
                                                    indexPlan()
                                                            .where(indexName("MyChildRecord$parent_rec_no"))
                                                            .and(scanComparisons(equalities(only(anyValueComparison()))))
                                            ))
                            )
                    )
            ));
            // This is the desired plan (though the first index access, at least, could use a covering optimization)
            /*
            assertMatches(plan, flatMapPlan(
                    indexPlan()
                            .where(indexName("MyParentRecord$str_value_indexed"))
                            .and(scanComparisons(range("[[even],[even]]"))),
                    indexPlan()
                            .where(indexName("MyChildRecord$parent_rec_no"))
                            .and(scanComparisons(equalities(only(anyValueComparison()))))
            ));
             */

            final List<String> results = FDBQueryGraphTestHelpers.executeCascades(recordStore, plan)
                    .map(queryResult -> FDBQueryGraphTestHelpers.getField(queryResult, String.class, "str_value"))
                    .asList()
                    .join();
            assertThat(results, joinOrdering.resultMatcher);
        }
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    @Disabled
    void findChildrenPerParent() {
        Assumptions.assumeTrue(useCascadesPlanner);
        createJoinRecords(false);
        try (FDBRecordContext context = openContext()) {
            openJoinRecordStore(context);

            final CorrelationIdentifier constantId = Quantifier.uniqueID();
            final ConstantObjectValue cov = ConstantObjectValue.of(constantId, "0", new Type.Array(false, Type.primitiveType(Type.TypeCode.LONG, false)));

            final RecordQueryPlan plan = planGraph(() -> {
                final var parentTypeQun = FDBQueryGraphTestHelpers.fullTypeScan(recordStore.getRecordMetaData(), "MyParentRecord");
                final var childTypeQun = FDBQueryGraphTestHelpers.fullTypeScan(recordStore.getRecordMetaData(), "MyChildRecord");

                // Equivalent to something like:
                //   SELECT p.rec_no AS parent_rec_no, c.rec_no AS child_rec_no, c.str_value
                //      FROM MyParentRecord p, MyChildRecord c
                //      WHERE p.rec_no IN ? AND p.rec_no = c.parent_rec_no
                final var selectQun = Quantifier.forEach(Reference.of(GraphExpansion.builder()
                        .addQuantifier(parentTypeQun)
                        .addQuantifier(childTypeQun)
                        .addPredicate(FieldValue.ofFieldName(parentTypeQun.getFlowedObjectValue(), "rec_no")
                                .withComparison(new Comparisons.ValueComparison(Comparisons.Type.IN, cov)))
                        .addPredicate(FieldValue.ofFieldName(childTypeQun.getFlowedObjectValue(), "parent_rec_no")
                                .withComparison(new Comparisons.ValueComparison(Comparisons.Type.EQUALS, FieldValue.ofFieldName(parentTypeQun.getFlowedObjectValue(), "rec_no"))))
                        .addResultColumn(Column.of(Optional.of("parent_rec_no"), FieldValue.ofFieldName(parentTypeQun.getFlowedObjectValue(), "rec_no")))
                        .addResultColumn(Column.of(Optional.of("child_rec_no"), FieldValue.ofFieldName(parentTypeQun.getFlowedObjectValue(), "rec_no")))
                        .addResultColumn(FDBQueryGraphTestHelpers.projectColumn(childTypeQun.getFlowedObjectValue(), "str_value"))
                        .build()
                        .buildSelect()));
                return Reference.of(FDBQueryGraphTestHelpers.unordered(selectQun));
            }, constantBindings(cov, List.of("1L", "2L", "3L")));

            assertMatches(plan, flatMapPlan(
                    typeFilterPlan(scanComparisons(unbounded()))
                            .where(recordTypes(only(equalsObject("MyParentRecord")))),
                    fetchFromPartialRecordPlan(
                            predicatesFilterPlan(
                                    coveringIndexPlan()
                                            .where(indexPlanOf(
                                                    indexPlan()
                                                            .where(indexName("MyChildRecord$parent_rec_no"))
                                                            .and(scanComparisons(equalities(only(anyValueComparison()))))
                                            ))
                            )
                    )
            ));
        }
    }

    protected void createJoinRecords(boolean parentToChild) {
        try (FDBRecordContext context = openContext()) {
            openJoinRecordStore(context);

            for (int i = 1; i <= 4; i++) {
                TestRecordsParentChildRelationshipProto.MyParentRecord.Builder parentBuilder = TestRecordsParentChildRelationshipProto.MyParentRecord.newBuilder();
                parentBuilder.setRecNo(i);
                parentBuilder.setStrValueIndexed((i & 1) == 1 ? "odd" : "even");
                for (int j = 1; j <= 3; j++) {
                    TestRecordsParentChildRelationshipProto.MyChildRecord.Builder childBuilder = TestRecordsParentChildRelationshipProto.MyChildRecord.newBuilder();
                    childBuilder.setRecNo(i * 10 + j);
                    childBuilder.setStrValue(i + "." + j);
                    if (parentToChild) {
                        parentBuilder.addChildRecNos(childBuilder.getRecNo());
                    } else {
                        childBuilder.setParentRecNo(i);
                    }
                    recordStore.saveRecord(childBuilder.build());
                }
                recordStore.saveRecord(parentBuilder.build());
            }
            commit(context);
        }
    }
}

