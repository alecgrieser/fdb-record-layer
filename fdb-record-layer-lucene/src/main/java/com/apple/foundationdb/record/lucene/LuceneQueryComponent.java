/*
 * LuceneQueryComponent.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2022 Apple Inc. and the FoundationDB project authors
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

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.PlanHashable;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.record.query.expressions.ComponentWithNoChildren;
import com.apple.foundationdb.record.query.expressions.QueryComponent;
import com.apple.foundationdb.record.query.plan.cascades.GraphExpansion;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A Query Component for Lucene that wraps the query supplied.
 *
 */
@API(API.Status.EXPERIMENTAL)
public class LuceneQueryComponent implements QueryComponent, ComponentWithNoChildren {

    @Nonnull
    private final LuceneQueryType type;
    @Nonnull
    private final String query;
    private final boolean queryIsParameter;

    @Nonnull
    private final List<String> fields;

    @Nullable
    private final LuceneScanQueryParameters.LuceneQueryHighlightParameters luceneQueryHighlightParameters;

    @Nullable
    private final Set<String> explicitFieldNames;

    //MultiFieldSearch determines whether MultiFieldQueryParser or QueryParserBase is used.
    // QueryParserBase expects the query to contain the fields to be run against and takes a default field
    // which in our use case is the primary key field.
    // The MultiFieldQueryParser runs the query against all fields listed and uses AND to join them.
    // If the component is created with empty fields we default to MultiField search.
    // It can also be specified by the user in creation of the component.
    // If True then we use the MultiFieldQueryParser to query all fields specified in the root expression.
    private final boolean multiFieldSearch;

    public LuceneQueryComponent(String query, List<String> fields) {
        this(query, fields, fields.isEmpty());
    }

    public LuceneQueryComponent(String query, List<String> fields, boolean multiField) {
        this(LuceneQueryType.QUERY, query, false, fields, multiField);
    }

    public LuceneQueryComponent(LuceneQueryType type, String query, boolean queryIsParameter, List<String> fields, boolean multiFieldSearch) {
        this(type, query, queryIsParameter, fields, multiFieldSearch, null, null);
    }

    public LuceneQueryComponent(LuceneQueryType type, String query, boolean queryIsParameter, List<String> fields, boolean multiFieldSearch,
                                @Nullable LuceneScanQueryParameters.LuceneQueryHighlightParameters luceneQueryHighlightParameters,
                                @Nullable Set<String> explicitFieldNames) {
        this.type = type;
        this.query = query;
        this.queryIsParameter = queryIsParameter;
        this.fields = fields;
        this.multiFieldSearch = multiFieldSearch;
        this.luceneQueryHighlightParameters = luceneQueryHighlightParameters;
        if (explicitFieldNames != null) {
            Preconditions.checkArgument(fields.size() == 1);
            final String fieldName = Iterables.getOnlyElement(fields);
            Preconditions.checkArgument(explicitFieldNames.stream().allMatch(explicitFieldName -> explicitFieldName.startsWith(fieldName)));
            this.explicitFieldNames = ImmutableSet.copyOf(explicitFieldNames);
        } else {
            this.explicitFieldNames = null;
        }
    }

    @Nonnull
    @Override
    public <M extends Message> Boolean evalMessage(@Nonnull final FDBRecordStoreBase<M> store, @Nonnull final EvaluationContext context, @Nullable final FDBRecord<M> rec, @Nullable final Message message) {
        throw new RecordCoreException("Residual lucene components are not yet supported");
    }

    @Override
    public void validate(@Nonnull final Descriptors.Descriptor descriptor) {
        // It's possible we could validate the fields that are being used with the fields that we've passed in.
    }

    @Nonnull
    @Override
    public GraphExpansion expand(@Nonnull final Quantifier.ForEach baseQuantifier,
                                 @Nonnull final Supplier<Quantifier.ForEach> outerQuantifierSupplier,
                                 @Nonnull final List<String> fieldNamePrefix) {
        // TODO do something here
        throw new UnsupportedOperationException();
    }

    @Nonnull
    public LuceneQueryType getType() {
        return type;
    }

    @Nonnull
    public String getQuery() {
        return query;
    }

    public boolean isQueryIsParameter() {
        return queryIsParameter;
    }

    @Nonnull
    public List<String> getFields() {
        return fields;
    }

    public boolean isMultiFieldSearch() {
        return multiFieldSearch;
    }

    @Nullable
    public LuceneScanQueryParameters.LuceneQueryHighlightParameters getLuceneQueryHighlightParameters() {
        return luceneQueryHighlightParameters;
    }

    @Nullable
    public Set<String> getExplicitFieldNames() {
        return explicitFieldNames;
    }

    /**
     * Creates an instance of this {@link LuceneQueryComponent} with a new list of fields.
     * @param fields The new list of fields.
     * @param explicitFieldNames the new list of explicit field names
     * @return a new instance of {@link LuceneQueryComponent}.
     */
    @Nonnull
    public LuceneQueryComponent withNewFields(@Nonnull final List<String> fields, @Nullable Set<String> explicitFieldNames) {
        return new LuceneQueryComponent(type, query, queryIsParameter, fields, multiFieldSearch, luceneQueryHighlightParameters, explicitFieldNames);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final LuceneQueryComponent that = (LuceneQueryComponent)o;

        if (queryIsParameter != that.queryIsParameter) {
            return false;
        }
        if (multiFieldSearch != that.multiFieldSearch) {
            return false;
        }
        if (type != that.type) {
            return false;
        }
        if (!query.equals(that.query)) {
            return false;
        }
        if (!fields.equals(that.fields)) {
            return false;
        }
        if (!Objects.equals(explicitFieldNames, that.explicitFieldNames)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + query.hashCode();
        result = 31 * result + (queryIsParameter ? 1 : 0);
        result = 31 * result + fields.hashCode();
        result = 31 * result + (multiFieldSearch ? 1 : 0);
        if (explicitFieldNames != null) {
            result = 31 * result + explicitFieldNames.hashCode();
        }
        return result;
    }

    @Override
    public int planHash(@Nonnull final PlanHashMode mode) {
        return PlanHashable.objectsPlanHash(mode, type, query);
    }

    @Override
    @Nonnull
    public String toString() {
        return "LuceneQuery(" + query + ")";
    }

}
