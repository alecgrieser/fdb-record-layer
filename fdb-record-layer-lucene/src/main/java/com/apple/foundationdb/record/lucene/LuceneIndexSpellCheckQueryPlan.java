/*
 * LuceneIndexSpellCheckQueryPlan.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2021 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.lucene;

import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.ExecuteProperties;
import com.apple.foundationdb.record.IndexEntry;
import com.apple.foundationdb.record.IndexScanType;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.RecordType;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.provider.foundationdb.FDBQueriedRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.record.provider.foundationdb.IndexScanParameters;
import com.apple.foundationdb.record.query.plan.cascades.explain.ExplainPlanVisitor;
import com.apple.foundationdb.record.query.plan.IndexKeyValueToPartialRecord;
import com.apple.foundationdb.record.query.plan.PlanOrderingKey;
import com.apple.foundationdb.record.query.plan.explain.ExplainTokens;
import com.apple.foundationdb.record.query.plan.explain.ExplainTokensWithPrecedence;
import com.apple.foundationdb.record.query.plan.plans.QueryPlanUtils;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryFetchFromPartialRecordPlan.FetchIndexRecords;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryIndexPlan;
import com.google.common.base.Verify;
import com.google.common.collect.Iterables;
import com.google.protobuf.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Lucene query plan that allows to make spell-check suggestions.
 */
public class LuceneIndexSpellCheckQueryPlan extends LuceneIndexQueryPlan {
    protected LuceneIndexSpellCheckQueryPlan(@Nonnull final String indexName, @Nonnull final LuceneScanParameters scanParameters,
                                             @Nonnull final FetchIndexRecords fetchIndexRecords, final boolean reverse,
                                             @Nullable final PlanOrderingKey planOrderingKey, @Nullable final List<KeyExpression> storedFields) {
        super(indexName, scanParameters, fetchIndexRecords, reverse, planOrderingKey, storedFields);
    }

    @SuppressWarnings("resource")
    @Nonnull
    @Override
    public <M extends Message> RecordCursor<FDBQueriedRecord<M>> fetchIndexRecords(@Nonnull final FDBRecordStoreBase<M> store,
                                                                                   @Nonnull final EvaluationContext evaluationContext,
                                                                                   @Nonnull final Function<byte[], RecordCursor<IndexEntry>> entryCursorFunction,
                                                                                   @Nullable final byte[] continuation,
                                                                                   @Nonnull final ExecuteProperties executeProperties) {
        final RecordMetaData metaData = store.getRecordMetaData();
        final Index index = metaData.getIndex(indexName);
        final Collection<RecordType> recordTypes = metaData.recordTypesForIndex(index);
        final IndexScanType scanType = getScanType();

        final RecordType recordType = Iterables.getOnlyElement(recordTypes);
        return entryCursorFunction.apply(continuation)
                .map(QueryPlanUtils.getCoveringIndexEntryToPartialRecordFunction(store, recordType.getName(), indexName,
                        LuceneIndexKeyValueToPartialRecordUtils.getToPartialRecord(index, recordType, scanType), false));
    }

    /**
     * Auto-Complete and Spell-Check scan has their own implementation for {@link IndexKeyValueToPartialRecord} to build partial records,
     * so they are not appropriate for the optimization by {@link com.apple.foundationdb.record.query.plan.plans.RecordQueryCoveringIndexPlan}.
     */
    @Override
    public boolean allowedForCoveringIndexPlan() {
        return false;
    }

    @Nonnull
    @Override
    protected RecordQueryIndexPlan withIndexScanParameters(@Nonnull final IndexScanParameters newIndexScanParameters) {
        Verify.verify(newIndexScanParameters instanceof LuceneScanParameters);
        Verify.verify(newIndexScanParameters.getScanType().equals(LuceneScanTypes.BY_LUCENE_SPELL_CHECK));
        return new LuceneIndexSpellCheckQueryPlan(getIndexName(), (LuceneScanParameters)newIndexScanParameters, getFetchIndexRecords(), reverse, getPlanOrderingKey(), getStoredFields());
    }

    @Nonnull
    @Override
    public ExplainTokensWithPrecedence explain() {
        return ExplainTokensWithPrecedence.of(
                new ExplainTokens().addKeyword("LSISCAN").addOptionalWhitespace().addOpeningParen()
                        .addNested(ExplainPlanVisitor.indexDetails(this))
                        .addOptionalWhitespace().addClosingParen());
    }
}
