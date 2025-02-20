/*
 * FDBVersionsQueryTest.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2023 Apple Inc. and the FoundationDB project authors
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
import com.apple.foundationdb.record.PlanSerializationContext;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordCursorResult;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.TestRecords1Proto.MySimpleRecord;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.IndexTypes;
import com.apple.foundationdb.record.metadata.RecordType;
import com.apple.foundationdb.record.metadata.RecordTypeBuilder;
import com.apple.foundationdb.record.planprotos.PRecordQueryPlan;
import com.apple.foundationdb.record.provider.common.RecordSerializer;
import com.apple.foundationdb.record.provider.common.StoreTimer;
import com.apple.foundationdb.record.provider.foundationdb.FDBQueriedRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordVersion;
import com.apple.foundationdb.record.provider.foundationdb.FDBStoredRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBTypedRecordStore;
import com.apple.foundationdb.record.query.IndexQueryabilityFilter;
import com.apple.foundationdb.record.query.RecordQuery;
import com.apple.foundationdb.record.query.expressions.Comparisons;
import com.apple.foundationdb.record.query.expressions.Query;
import com.apple.foundationdb.record.query.plan.RecordQueryPlanner;
import com.apple.foundationdb.record.query.plan.cascades.AliasMap;
import com.apple.foundationdb.record.query.plan.cascades.CascadesPlanner;
import com.apple.foundationdb.record.query.plan.cascades.GraphExpansion;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.apple.foundationdb.record.query.plan.cascades.Reference;
import com.apple.foundationdb.record.query.plan.cascades.expressions.LogicalSortExpression;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.BindingMatcher;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.PrimitiveMatchers;
import com.apple.foundationdb.record.query.plan.cascades.predicates.ValuePredicate;
import com.apple.foundationdb.record.query.plan.cascades.values.FieldValue;
import com.apple.foundationdb.record.query.plan.cascades.values.QuantifiedRecordValue;
import com.apple.foundationdb.record.query.plan.cascades.values.VersionValue;
import com.apple.foundationdb.record.query.plan.plans.QueryResult;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryIndexPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.apple.foundationdb.tuple.ByteArrayUtil2;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.test.Tags;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.apple.foundationdb.record.metadata.Key.Expressions.concat;
import static com.apple.foundationdb.record.metadata.Key.Expressions.field;
import static com.apple.foundationdb.record.metadata.Key.Expressions.version;
import static com.apple.foundationdb.record.provider.foundationdb.query.FDBQueryGraphTestHelpers.executeCascades;
import static com.apple.foundationdb.record.provider.foundationdb.query.FDBQueryGraphTestHelpers.fullTypeScan;
import static com.apple.foundationdb.record.provider.foundationdb.query.FDBQueryGraphTestHelpers.getField;
import static com.apple.foundationdb.record.provider.foundationdb.query.FDBQueryGraphTestHelpers.resultColumn;
import static com.apple.foundationdb.record.provider.foundationdb.query.FDBQueryGraphTestHelpers.sortExpression;
import static com.apple.foundationdb.record.query.plan.ScanComparisons.range;
import static com.apple.foundationdb.record.query.plan.ScanComparisons.unbounded;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ListMatcher.exactly;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.PrimitiveMatchers.equalsObject;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.QueryPredicateMatchers.valuePredicate;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.filterPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.indexName;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.indexPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.mapPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.mapResult;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.predicates;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.predicatesFilterPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.queryComponents;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.recordTypes;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.scanComparisons;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.scanPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RecordQueryPlanMatchers.typeFilterPlan;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ValueMatchers.fieldValueWithFieldNames;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ValueMatchers.recordConstructorValue;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ValueMatchers.versionValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of queries involving predicates on {@link FDBRecordVersion}s. These tests are around to facilitate testing
 * the planners' version queries, including things like making sure if the version is
 * For additional tests, see {@link com.apple.foundationdb.record.provider.foundationdb.indexes.VersionIndexTest}.
 */
@Tag(Tags.RequiresFDB)
public class FDBVersionsQueryTest extends FDBRecordStoreQueryTestBase {
    private static final Index VERSION_INDEX = new Index("versionIndex", version(), IndexTypes.VERSION);
    private static final Index VERSION_BY_NUM_VALUE_2_INDEX = new Index("versionByNumValue2Index", concat(field("num_value_2"), version()), IndexTypes.VERSION);

    private static final RecordMetaDataHook VERSIONS_HOOK = metaDataBuilder -> {
        metaDataBuilder.setStoreRecordVersions(true);

        final RecordTypeBuilder simple = metaDataBuilder.getRecordType("MySimpleRecord");
        metaDataBuilder.addIndex(simple, VERSION_INDEX);
        metaDataBuilder.addIndex(simple, VERSION_BY_NUM_VALUE_2_INDEX);
    };

    @Nonnull
    private static final ByteString PLAN_4_0_559_0 = ByteString.fromHex(
            "9A019E030A84020A02713212FD01080012F8015AF5010A0C76657273696F6E496E646578120E1A0C0A067265635F6E6F1001" +
                    "18011A10120E0A0A0A0842595F56414C55451200200128013000380042AE0132AB010800180122120A060A04080910011206" +
                    "7265635F6E6F1801221D0A060A04080A100112117374725F76616C75655F696E64657865641802221C0A060A040808100112" +
                    "106E756D5F76616C75655F756E69717565180322170A060A0408081001120B6E756D5F76616C75655F321804221F0A060A04" +
                    "0808100112136E756D5F76616C75655F335F696E64657865641805221A0A0C420A10001A060A040808100012087265706561" +
                    "74657218064A0A0A081A060A02080010011294018A0290010A2F322D0801180022130A060A04080B1001120776657273696F" +
                    "6E180122120A060A040809100112066E756D6265721802121E0A130A060A04080B1001120776657273696F6E18011207B202" +
                    "040A027132123D0A120A060A040809100112066E756D6265721802122762250A0DF2010A0A02713212043202080012140A12" +
                    "0A067265635F6E6F10001A060A0408091001"
    );
    @Nonnull
    private static final ByteString PLAN_4_0_564_0 = ByteString.fromHex(
            "9A01AD030A84020A02713212FD01080012F8015AF5010A0C76657273696F6E496E646578120E1A0C0A067265635F6E6F1001" +
                    "18011A10120E0A0A0A0842595F56414C55451200200128013000380042AE0132AB010800180022120A060A04080910011206" +
                    "7265635F6E6F1801221D0A060A04080A100112117374725F76616C75655F696E64657865641802221C0A060A040808100112" +
                    "106E756D5F76616C75655F756E69717565180322170A060A0408081001120B6E756D5F76616C75655F321804221F0A060A04" +
                    "0808100112136E756D5F76616C75655F335F696E64657865641805221A0A0C420A10001A060A040808100012087265706561" +
                    "74657218064A0A0A081A060A020800100112A3018A029F010A2F322D0801180022130A060A04080B1001120776657273696F" +
                    "6E180122120A060A040809100112066E756D6265721802122D0A130A060A04080B1001120776657273696F6E18011216B202" +
                    "130A027132120DF2020A0A027132120432020800123D0A120A060A040809100112066E756D6265721802122762250A0DF201" +
                    "0A0A02713212043202080012140A120A067265635F6E6F10001A060A0408091001"
    );
    @Nonnull
    private static final ByteString PLAN_4_1_9_0 = ByteString.fromHex(
            "9A019E030A84020A02713212FD01080012F8015AF5010A0C76657273696F6E496E646578120E1A0C0A067265635F6E6F1001" +
                    "18011A10120E0A0A0A0842595F56414C55451200200128013000380042AE0132AB010800180022120A060A04080910011206" +
                    "7265635F6E6F1801221D0A060A04080A100112117374725F76616C75655F696E64657865641802221C0A060A040808100112" +
                    "106E756D5F76616C75655F756E69717565180322170A060A0408081001120B6E756D5F76616C75655F321804221F0A060A04" +
                    "0808100112136E756D5F76616C75655F335F696E64657865641805221A0A0C420A10001A060A040808100012087265706561" +
                    "74657218064A0A0A081A060A02080010011294018A0290010A2F322D0801180022130A060A04080B1001120776657273696F" +
                    "6E180122120A060A040809100112066E756D6265721802121E0A130A060A04080B1001120776657273696F6E18011207B202" +
                    "040A027132123D0A120A060A040809100112066E756D6265721802122762250A0DF2010A0A02713212043202080012140A12" +
                    "0A067265635F6E6F10001A060A0408091001"
    );

    private void openStore(FDBRecordContext context) {
        openSimpleRecordStore(context, VERSIONS_HOOK);
    }

    @Nonnull
    private FDBTypedRecordStore<MySimpleRecord> getNarrowedStore() {
        RecordSerializer<Message> baseSerializer = recordStore.getSerializer();
        return recordStore.getTypedRecordStore(new RecordSerializer<>() {
            @Nonnull
            @Override
            public byte[] serialize(@Nonnull final RecordMetaData metaData, @Nonnull final RecordType recordType, @Nonnull final MySimpleRecord rec, @Nullable final StoreTimer timer) {
                return baseSerializer.serialize(metaData, recordType, rec, timer);
            }

            @Nonnull
            @Override
            public MySimpleRecord deserialize(@Nonnull final RecordMetaData metaData, @Nonnull final Tuple primaryKey, @Nonnull final byte[] serialized, @Nullable final StoreTimer timer) {
                Message msg = baseSerializer.deserialize(metaData, primaryKey, serialized, timer);

                if (!msg.getDescriptorForType().equals(MySimpleRecord.getDescriptor())) {
                    throw new RecordCoreException("invalid type to deserialize");
                }
                return MySimpleRecord.newBuilder().mergeFrom(msg).build();
            }

            @Nonnull
            @Override
            public RecordSerializer<Message> widen() {
                return baseSerializer.widen();
            }
        });
    }

    @Nonnull
    private List<FDBStoredRecord<MySimpleRecord>> populateRecords() {
        List<FDBStoredRecord<MySimpleRecord>> saved = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            try (FDBRecordContext context = openContext()) {
                openStore(context);
                FDBTypedRecordStore<MySimpleRecord> typedStore = getNarrowedStore();

                List<FDBStoredRecord<MySimpleRecord>> savedInTransaction = new ArrayList<>();
                for (int j = 0; j < 10; j++) {
                    MySimpleRecord record = MySimpleRecord.newBuilder()
                            .setRecNo(j * 100 + i)
                            .setStrValueIndexed(j % 2 == 0 ? "even" : "odd")
                            .setNumValue2(j % 3)
                            .setNumValue3Indexed(j)
                            .setNumValueUnique(i * 100 + j)
                            .build();

                    savedInTransaction.add(typedStore.saveRecord(record));
                }

                context.commit();
                byte[] globalVersion = context.getVersionStamp();
                savedInTransaction.forEach(rec -> saved.add(rec.withCommittedVersion(globalVersion)));
            }
        }
        return saved;
    }

    @DualPlannerTest
    void orderByVersion() {
        List<FDBStoredRecord<MySimpleRecord>> records = populateRecords();

        try (FDBRecordContext context = openContext()) {
            openStore(context);
            FDBTypedRecordStore<MySimpleRecord> typedStore = getNarrowedStore();

            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType("MySimpleRecord")
                    .setSort(version())
                    .build();

            RecordQueryPlan plan = planQuery(query);
            assertMatchesExactly(plan, indexPlan()
                    .where(indexName(VERSION_INDEX.getName()))
                    .and(scanComparisons(unbounded())));

            List<FDBQueriedRecord<MySimpleRecord>> queried = typedStore.executeQuery(plan)
                    .asList()
                    .join();
            assertThat(queried, hasSize(records.size()));
            assertInVersionOrder(queried);
        }
    }

    @DualPlannerTest
    void orderByVersionWithSelectiveResults() {
        List<FDBStoredRecord<MySimpleRecord>> records = populateRecords();

        try (FDBRecordContext context = openContext()) {
            openStore(context);
            FDBTypedRecordStore<MySimpleRecord> typedStore = getNarrowedStore();

            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType("MySimpleRecord")
                    .setRequiredResults(List.of(field("rec_no"), version()))
                    .setSort(version())
                    .build();

            RecordQueryPlan plan = planQuery(query);
            assertMatchesExactly(plan, indexPlan()
                    .where(indexName(VERSION_INDEX.getName()))
                    .and(scanComparisons(unbounded())));

            List<FDBQueriedRecord<MySimpleRecord>> queried = typedStore.executeQuery(plan)
                    .asList()
                    .join();
            assertThat(queried, hasSize(records.size()));
            assertInVersionOrder(queried);
        }
    }

    @DualPlannerTest
    void filterByVersion() {
        List<FDBStoredRecord<MySimpleRecord>> records = populateRecords();

        try (FDBRecordContext context = openContext()) {
            openStore(context);
            FDBTypedRecordStore<MySimpleRecord> typedStore = getNarrowedStore();

            FDBRecordVersion versionForQuery = records.get(records.size() / 2).getVersion();
            assertNotNull(versionForQuery);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType("MySimpleRecord")
                    .setFilter(Query.version().greaterThan(versionForQuery))
                    .build();

            RecordQueryPlan plan = planQuery(query);
            assertMatchesExactly(plan, indexPlan()
                    .where(indexName(VERSION_INDEX.getName()))
                    .and(scanComparisons(range("([" + versionForQuery.toVersionstamp(false) + "],>"))));
            List<FDBStoredRecord<MySimpleRecord>> queried = typedStore.executeQuery(plan)
                    .map(FDBQueriedRecord::getStoredRecord)
                    .asList()
                    .join();

            List<FDBStoredRecord<MySimpleRecord>> expected = records.stream()
                    .filter(rec -> {
                        FDBRecordVersion recordVersion = rec.getVersion();
                        assertNotNull(recordVersion);
                        return recordVersion.compareTo(versionForQuery) > 0;
                    })
                    .collect(Collectors.toList());
            assertEquals(expected, queried);
        }
    }

    @DualPlannerTest
    void residualVersionFilter() {
        List<FDBStoredRecord<MySimpleRecord>> records = populateRecords();

        try (FDBRecordContext context = openContext()) {
            openStore(context);
            FDBTypedRecordStore<MySimpleRecord> typedStore = getNarrowedStore();

            FDBRecordVersion versionForQuery = records.get(records.size() / 2).getVersion();
            assertNotNull(versionForQuery);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType("MySimpleRecord")
                    .setFilter(Query.version().greaterThan(versionForQuery))
                    .setSort(field("num_value_unique")) // use sort to force execution of predicate as a residual filter
                    .build();

            RecordQueryPlan plan = planQuery(query);
            BindingMatcher<RecordQueryIndexPlan> indexPlanMatcher = indexPlan()
                    .where(indexName("MySimpleRecord$num_value_unique"))
                    .and(scanComparisons(unbounded()));
            if (planner instanceof RecordQueryPlanner) {
                assertMatchesExactly(plan, filterPlan(indexPlanMatcher)
                        .where(queryComponents(exactly(equalsObject(Query.version().greaterThan(versionForQuery))))));
            } else {
                assertMatchesExactly(plan, predicatesFilterPlan(indexPlanMatcher)
                        .where(predicates(valuePredicate(versionValue(), new Comparisons.SimpleComparison(Comparisons.Type.GREATER_THAN, versionForQuery)))));
            }
            List<FDBStoredRecord<MySimpleRecord>> queried = typedStore.executeQuery(plan)
                    .map(FDBQueriedRecord::getStoredRecord)
                    .asList()
                    .join();

            List<FDBStoredRecord<MySimpleRecord>> expected = records.stream()
                    .filter(rec -> {
                        FDBRecordVersion recordVersion = rec.getVersion();
                        assertNotNull(recordVersion);
                        return recordVersion.compareTo(versionForQuery) > 0;
                    })
                    .sorted(Comparator.comparingInt(rec -> rec.getRecord().getNumValueUnique()))
                    .collect(Collectors.toList());
            assertEquals(expected, queried);
        }
    }

    @DualPlannerTest
    void residualVersionFilterWithSelectiveResults() {
        List<FDBStoredRecord<MySimpleRecord>> records = populateRecords();

        try (FDBRecordContext context = openContext()) {
            openStore(context);
            FDBTypedRecordStore<MySimpleRecord> typedStore = getNarrowedStore();

            FDBRecordVersion versionForQuery = records.get(records.size() / 2).getVersion();
            assertNotNull(versionForQuery);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType("MySimpleRecord")
                    .setFilter(Query.version().greaterThan(versionForQuery))
                    .setSort(field("num_value_unique")) // use sort to force execution of predicate as a residual filter
                    .setRequiredResults(List.of(field("num_value_unique"), field("rec_no")))
                    .build();

            RecordQueryPlan plan = planQuery(query);
            BindingMatcher<RecordQueryIndexPlan> indexPlanMatcher = indexPlan()
                    .where(indexName("MySimpleRecord$num_value_unique"))
                    .and(scanComparisons(unbounded()));
            if (planner instanceof RecordQueryPlanner) {
                assertMatchesExactly(plan, filterPlan(indexPlanMatcher)
                        .where(queryComponents(exactly(equalsObject(Query.version().greaterThan(versionForQuery))))));
            } else {
                assertMatchesExactly(plan, predicatesFilterPlan(indexPlanMatcher)
                        .where(predicates(valuePredicate(versionValue(), new Comparisons.SimpleComparison(Comparisons.Type.GREATER_THAN, versionForQuery)))));
            }

            List<Long> queried = typedStore.executeQuery(plan)
                    .map(rec -> rec.getRecord().getRecNo())
                    .asList()
                    .join();

            List<Long> expected = records.stream()
                    .filter(rec -> {
                        FDBRecordVersion recordVersion = rec.getVersion();
                        assertNotNull(recordVersion);
                        return recordVersion.compareTo(versionForQuery) > 0;
                    })
                    .sorted(Comparator.comparingInt(rec -> rec.getRecord().getNumValueUnique()))
                    .map(rec -> rec.getRecord().getRecNo())
                    .collect(Collectors.toList());
            assertEquals(expected, queried);
        }
    }

    @DualPlannerTest
    void sortAndFilterWithSingleIndex() {
        List<FDBStoredRecord<MySimpleRecord>> records = populateRecords();

        try (FDBRecordContext context = openContext()) {
            openStore(context);
            FDBTypedRecordStore<MySimpleRecord> typedStore = getNarrowedStore();

            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType("MySimpleRecord")
                    .setFilter(Query.field("num_value_2").equalsValue(1))
                    .setSort(version())
                    .build();

            RecordQueryPlan plan = planQuery(query);
            assertMatchesExactly(plan, indexPlan()
                    .where(indexName(VERSION_BY_NUM_VALUE_2_INDEX.getName()))
                    .and(scanComparisons(range("[[1],[1]]"))));

            List<FDBStoredRecord<MySimpleRecord>> queried = typedStore.executeQuery(plan)
                    .map(FDBQueriedRecord::getStoredRecord)
                    .asList()
                    .join();
            assertInVersionOrder(queried);

            List<FDBStoredRecord<MySimpleRecord>> expected = records.stream()
                    .filter(rec -> rec.getRecord().getNumValue2() == 1)
                    .collect(Collectors.toList());
            assertEquals(expected, queried);
        }
    }

    @DualPlannerTest
    void sortFilterOnVersionIndexEntries() {
        List<FDBStoredRecord<MySimpleRecord>> records = populateRecords();

        try (FDBRecordContext context = openContext()) {
            openStore(context);
            FDBTypedRecordStore<MySimpleRecord> typedStore = getNarrowedStore();

            FDBRecordVersion excludedVersion = records.stream()
                    .filter(rec -> rec.getRecord().getNumValue2() == 1)
                    .map(FDBStoredRecord::getVersion)
                    .findAny()
                    .get();
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType("MySimpleRecord")
                    .setFilter(Query.and(Query.field("num_value_2").equalsValue(1), Query.version().notEquals(excludedVersion)))
                    .setSort(version())
                    .build();

            RecordQueryPlan plan = planQuery(query);
            // Should be able to push down version filter onto index the index entries when planner can better reason about version field
            BindingMatcher<RecordQueryIndexPlan> indexPlanMatcher = indexPlan()
                    .where(indexName(VERSION_BY_NUM_VALUE_2_INDEX.getName()))
                    .and(scanComparisons(range("[[1],[1]]")));
            if (planner instanceof RecordQueryPlanner) {
                assertMatchesExactly(plan, filterPlan(indexPlanMatcher)
                        .where(queryComponents(exactly(equalsObject(Query.version().notEquals(excludedVersion))))));
            } else {
                assertMatchesExactly(plan, predicatesFilterPlan(indexPlanMatcher)
                        .where(predicates(valuePredicate(versionValue(), new Comparisons.SimpleComparison(Comparisons.Type.NOT_EQUALS, excludedVersion)))));
            }
            List<FDBStoredRecord<MySimpleRecord>> queried = typedStore.executeQuery(plan)
                    .map(FDBQueriedRecord::getStoredRecord)
                    .asList()
                    .join();
            assertInVersionOrder(queried);

            List<FDBStoredRecord<MySimpleRecord>> expected = records.stream()
                    .filter(rec -> rec.getRecord().getNumValue2() == 1)
                    .filter(rec -> rec.hasVersion() && !rec.getVersion().equals(excludedVersion))
                    .collect(Collectors.toList());
            assertEquals(expected, queried);
        }
    }

    @DualPlannerTest
    void requestVersionWhenQueryIsOnOtherFields() {
        List<FDBStoredRecord<MySimpleRecord>> records = populateRecords();

        try (FDBRecordContext context = openContext()) {
            openStore(context);
            FDBTypedRecordStore<MySimpleRecord> typedStore = getNarrowedStore();

            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType("MySimpleRecord")
                    .setFilter(Query.field("str_value_indexed").equalsValue("even"))
                    .setRequiredResults(List.of(field("rec_no"), version()))
                    .build();

            RecordQueryPlan plan = planQuery(query);
            // Should be able to push down version filter onto index the index entries when planner can better reason about version field
            assertMatchesExactly(plan, indexPlan()
                    .where(indexName("MySimpleRecord$str_value_indexed"))
                    .and(scanComparisons(range("[[even],[even]]")))
            );
            List<FDBStoredRecord<MySimpleRecord>> queried = typedStore.executeQuery(plan)
                    .map(FDBQueriedRecord::getStoredRecord)
                    .asList()
                    .join();
            assertTrue(queried.stream().allMatch(FDBRecord::hasVersion), "records should all have non-null versions");

            List<FDBStoredRecord<MySimpleRecord>> expected = records.stream()
                    .filter(rec -> rec.getRecord().getStrValueIndexed().equals("even"))
                    .sorted(Comparator.comparing(FDBStoredRecord::getPrimaryKey))
                    .collect(Collectors.toList());
            assertEquals(expected, queried);
        }
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    void versionGraphQuery() {
        List<FDBStoredRecord<MySimpleRecord>> records = populateRecords();

        try (FDBRecordContext context = openContext()) {
            openStore(context);

            // Plan a query approximating:
            //    SELECT recordVersion(MySimpleRecord) AS version, MySimpleRecord.rec_no AS number FROM MySimpleRecord ORDER BY version ASC
            RecordQueryPlan plan = ((CascadesPlanner)planner).planGraph(() -> {
                var qun = fullTypeScan(recordStore.getRecordMetaData(), "MySimpleRecord");

                final var graphExpansionBuilder = GraphExpansion.builder();
                graphExpansionBuilder.addQuantifier(qun);

                var recNoValue = FieldValue.ofFieldName(qun.getFlowedObjectValue(), "rec_no");
                var versionValue = new VersionValue(QuantifiedRecordValue.of(qun));

                graphExpansionBuilder.addResultColumn(resultColumn(versionValue, "version"));
                graphExpansionBuilder.addResultColumn(resultColumn(recNoValue, "number"));

                var select = Quantifier.forEach(Reference.of(graphExpansionBuilder.build().buildSelect()));

                AliasMap aliasMap = AliasMap.ofAliases(select.getAlias(), Quantifier.current());
                return Reference.of(sortExpression(List.of(FieldValue.ofFieldName(select.getFlowedObjectValue(), "version").rebase(aliasMap)), false, select));
            }, Optional.empty(), IndexQueryabilityFilter.DEFAULT, EvaluationContext.empty()).getPlan();

            assertMatchesExactly(plan, mapPlan(
                    indexPlan()
                            .where(indexName("versionIndex"))
                            .and(scanComparisons(unbounded()))
                    )
                    .where(mapResult(recordConstructorValue(exactly(versionValue(), fieldValueWithFieldNames("rec_no"))))));

            FDBRecordVersion previousVersion = null;
            try (RecordCursor<QueryResult> cursor = executeCascades(recordStore, plan)) {
                for (RecordCursorResult<QueryResult> result = cursor.getNext(); result.hasNext(); result = cursor.getNext()) {
                    QueryResult underlying = Objects.requireNonNull(result.get());
                    // Make sure that the version is serialized into the RecordConstructor as bytes correctly
                    ByteString versionObj = getField(underlying, ByteString.class, "version");
                    assertNotNull(versionObj);
                    FDBRecordVersion version = FDBRecordVersion.fromBytes(versionObj.toByteArray(), false);
                    if (previousVersion != null) {
                        assertThat(version, greaterThan(previousVersion));
                    }
                    long number = Objects.requireNonNull(getField(underlying, Long.class, "number"));
                    long expectedRecNo = records.stream()
                            .filter(rec -> version.equals(rec.getVersion()))
                            .findFirst()
                            .map(rec -> rec.getRecord().getRecNo())
                            .orElse(-1L);
                    assertEquals(expectedRecNo, number);

                    previousVersion = version;
                }
            }
        }
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    void versionInSubSelectQuery() {
        List<FDBStoredRecord<MySimpleRecord>> records = populateRecords();

        try (FDBRecordContext context = openContext()) {
            openStore(context);

            final FDBRecordVersion versionForQuery = Objects.requireNonNull(records.get(records.size() / 2).getVersion());

            // Plan a query approximating:
            //    SELECT *
            //        FROM (SELECT recordVersion(MySimpleRecord) AS version, MySimpleRecord.rec_no AS number FROM MySimpleRecord)
            //        WHERE version <= ?versionForQuery
            // Use this to test how processing a version through sub-selects works
            RecordQueryPlan plan = ((CascadesPlanner)planner).planGraph(() -> {
                var qun = fullTypeScan(recordStore.getRecordMetaData(), "MySimpleRecord");

                final var innerGraphBuilder = GraphExpansion.builder();
                innerGraphBuilder.addQuantifier(qun);

                var recNoValue = FieldValue.ofFieldName(qun.getFlowedObjectValue(), "rec_no");
                var versionValue = new VersionValue(QuantifiedRecordValue.of(qun));

                innerGraphBuilder.addResultColumn(resultColumn(versionValue, "version"));
                innerGraphBuilder.addResultColumn(resultColumn(recNoValue, "number"));

                var innerSelect = Quantifier.forEach(Reference.of(innerGraphBuilder.build().buildSelect()));

                final var outerGraphBuilder = GraphExpansion.builder();
                outerGraphBuilder.addQuantifier(innerSelect);

                outerGraphBuilder.addPredicate(new ValuePredicate(FieldValue.ofFieldName(innerSelect.getFlowedObjectValue(), "version"), new Comparisons.SimpleComparison(Comparisons.Type.LESS_THAN_OR_EQUALS, versionForQuery)));

                outerGraphBuilder.addResultValue(FieldValue.ofFieldName(innerSelect.getFlowedObjectValue(), "version"));
                outerGraphBuilder.addResultValue(FieldValue.ofFieldName(innerSelect.getFlowedObjectValue(), "number"));
                var select = Quantifier.forEach(Reference.of(outerGraphBuilder.build().buildSelect()));

                return Reference.of(LogicalSortExpression.unsorted(select));
            }, Optional.empty(), IndexQueryabilityFilter.DEFAULT, EvaluationContext.empty()).getPlan();

            assertMatchesExactly(plan, mapPlan(
                    predicatesFilterPlan(
                            mapPlan(
                                    typeFilterPlan(
                                            scanPlan()
                                                    .where(scanComparisons(unbounded()))
                                    ).where(recordTypes(PrimitiveMatchers.containsAll(Set.of("MySimpleRecord"))))
                            )
                            .where(mapResult(recordConstructorValue(exactly(versionValue(), fieldValueWithFieldNames("rec_no")))))
                    ).where(predicates(exactly(valuePredicate(fieldValueWithFieldNames("version"), new Comparisons.SimpleComparison(Comparisons.Type.LESS_THAN_OR_EQUALS, versionForQuery)))))
            ).where(mapResult(recordConstructorValue(exactly(fieldValueWithFieldNames("version"), fieldValueWithFieldNames("number"))))));

            Set<Long> expectedNumbers = records.stream()
                    .filter(rec -> rec.getVersion() != null && rec.getVersion().compareTo(versionForQuery) <= 0)
                    .map(rec -> rec.getRecord().getRecNo())
                    .collect(Collectors.toSet());
            try (RecordCursor<QueryResult> cursor = executeCascades(recordStore, plan)) {
                Set<Long> actualNumbers = new HashSet<>();
                for (RecordCursorResult<QueryResult> result = cursor.getNext(); result.hasNext(); result = cursor.getNext()) {
                    QueryResult underlying = Objects.requireNonNull(result.get());
                    ByteString versionObj = getField(underlying, ByteString.class, "_0");
                    assertNotNull(versionObj);
                    FDBRecordVersion version = FDBRecordVersion.fromBytes(versionObj.toByteArray(), false);
                    assertThat(version, lessThanOrEqualTo(versionForQuery));
                    long number = Objects.requireNonNull(getField(underlying, Long.class, "_1"));
                    actualNumbers.add(number);
                }
                assertEquals(expectedNumbers, actualNumbers);
            }
        }
    }

    private ByteString generateSerializedPlan() {
        try (FDBRecordContext context = openContext()) {
            openStore(context);

            // Plan a query approximating:
            //    SELECT recordVersion(MySimpleRecord) AS version, MySimpleRecord.rec_no AS number FROM MySimpleRecord ORDER BY version ASC
            RecordQueryPlan plan = ((CascadesPlanner)planner).planGraph(() -> {
                var qun = fullTypeScan(recordStore.getRecordMetaData(), "MySimpleRecord");

                final var graphExpansionBuilder = GraphExpansion.builder();
                graphExpansionBuilder.addQuantifier(qun);

                var recNoValue = FieldValue.ofFieldName(qun.getFlowedObjectValue(), "rec_no");
                var versionValue = new VersionValue(QuantifiedRecordValue.of(qun));

                graphExpansionBuilder.addResultColumn(resultColumn(versionValue, "version"));
                graphExpansionBuilder.addResultColumn(resultColumn(recNoValue, "number"));

                var select = Quantifier.forEach(Reference.of(graphExpansionBuilder.build().buildSelect()));

                AliasMap aliasMap = AliasMap.ofAliases(select.getAlias(), Quantifier.current());
                return Reference.of(sortExpression(List.of(FieldValue.ofFieldName(select.getFlowedObjectValue(), "version").rebase(aliasMap)), false, select));
            }, Optional.empty(), IndexQueryabilityFilter.DEFAULT, EvaluationContext.empty()).getPlan();

            assertMatchesExactly(plan, mapPlan(
                    indexPlan()
                            .where(indexName("versionIndex"))
                            .and(scanComparisons(unbounded()))
            )
                    .where(mapResult(recordConstructorValue(exactly(versionValue(), fieldValueWithFieldNames("rec_no"))))));

            PlanSerializationContext serializationContext = PlanSerializationContext.newForCurrentMode();
            PRecordQueryPlan planProto = plan.toRecordQueryPlanProto(serializationContext);
            assertThat(planProto, instanceOf(PRecordQueryPlan.class));
            return planProto.toByteString();
        }
    }

    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    @Disabled("Utility used to generate additional serialized plans")
    void printPlanHexString() {
        ByteString serializedPlan = generateSerializedPlan();
        String hexPlan = ByteArrayUtil2.toHexString(serializedPlan.toByteArray());

        int curr = 0;
        while (curr < hexPlan.length()) {
            int end = Math.min(curr + 100, hexPlan.length());
            System.out.println("       \"" + hexPlan.substring(curr, end) + "\"" + ((end < hexPlan.length()) ? " +" : ""));
            curr = end;
        }
    }

    static Stream<Arguments> runDeserializedPlan() {
        // Once we no longer want to support reading plans from these older versions, we can
        // remove the older test cases
        return Stream.of(
                Arguments.of("4.0.559.0", PLAN_4_0_559_0),
                Arguments.of("4.0.564.0", PLAN_4_0_564_0),
                Arguments.of("4.1.9.0", PLAN_4_1_9_0),
                Arguments.of("current", ByteString.EMPTY)
        );
    }

    /**
     * Test for making sure serialized plans can be successfully run from older versions.
     * To generate a new test case, run {@link #printPlanHexString()} and copy the printed
     * hex string to a static variable. Then, update the list of test cases in
     * {@link #runDeserializedPlan()} with the new plan.
     *
     * @param codeVersion Record Layer version in which the plan was generated (for logging)
     * @param serializedPlan the plan to deserialize and run
     * @throws InvalidProtocolBufferException if deserialization fails
     */
    @DualPlannerTest(planner = DualPlannerTest.Planner.CASCADES)
    @ParameterizedTest(name = "runDeserializedPlan[codeVersion={0}]")
    @MethodSource
    void runDeserializedPlan(String codeVersion, ByteString serializedPlan) throws InvalidProtocolBufferException {
        Assumptions.assumeTrue(useCascadesPlanner);
        if (serializedPlan.isEmpty()) {
            // To test the current version, generate a serialized plan of what we currently produce
            serializedPlan = generateSerializedPlan();
        }

        PRecordQueryPlan planProto = PRecordQueryPlan.parseFrom(serializedPlan);
        RecordQueryPlan plan = RecordQueryPlan.fromRecordQueryPlanProto(PlanSerializationContext.newForCurrentMode(), planProto);

        assertMatchesExactly(plan, mapPlan(
                indexPlan()
                        .where(indexName("versionIndex"))
                        .and(scanComparisons(unbounded()))
        ).where(mapResult(recordConstructorValue(exactly(versionValue(), fieldValueWithFieldNames("rec_no"))))));

        List<FDBStoredRecord<MySimpleRecord>> records = populateRecords();
        FDBRecordVersion previousVersion = null;
        try (FDBRecordContext context = openContext()) {
            openStore(context);
            try (RecordCursor<QueryResult> cursor = executeCascades(recordStore, plan)) {
                for (RecordCursorResult<QueryResult> result = cursor.getNext(); result.hasNext(); result = cursor.getNext()) {
                    QueryResult underlying = Objects.requireNonNull(result.get());
                    // Make sure that the version is serialized into the RecordConstructor as bytes correctly
                    ByteString versionObj = getField(underlying, ByteString.class, "version");
                    assertNotNull(versionObj);
                    FDBRecordVersion version = FDBRecordVersion.fromBytes(versionObj.toByteArray(), false);
                    if (previousVersion != null) {
                        assertThat(version, greaterThan(previousVersion));
                    }
                    long number = Objects.requireNonNull(getField(underlying, Long.class, "number"));
                    long expectedRecNo = records.stream()
                            .filter(rec -> version.equals(rec.getVersion()))
                            .findFirst()
                            .map(rec -> rec.getRecord().getRecNo())
                            .orElse(-1L);
                    assertEquals(expectedRecNo, number);

                    previousVersion = version;
                }
            }
        }
    }

    private static void assertInVersionOrder(List<? extends FDBRecord<?>> records) {
        FDBRecordVersion lastVersion = null;
        for (FDBRecord<?> rec : records) {
            FDBRecordVersion nextVersion = rec.getVersion();
            assertNotNull(nextVersion, () -> "version for record with primary key " + rec.getPrimaryKey() + " should not be null");
            if (lastVersion != null) {
                assertThat(nextVersion, greaterThan(lastVersion));
            }
            lastVersion = nextVersion;
        }
    }
}
