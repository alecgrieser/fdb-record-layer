/*
 * RecordLayerIndex.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2021-2025 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.relational.recordlayer.metadata;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.RecordMetaDataProto;
import com.apple.foundationdb.record.metadata.IndexOptions;
import com.apple.foundationdb.record.metadata.IndexPredicate;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.relational.api.exceptions.ErrorCode;
import com.apple.foundationdb.relational.api.metadata.Index;
import com.apple.foundationdb.relational.recordlayer.metadata.serde.KeyExpressionTransformFieldVisitor;
import com.apple.foundationdb.relational.util.Assert;
import com.google.common.collect.ImmutableMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

@API(API.Status.EXPERIMENTAL)
public final class RecordLayerIndex implements Index  {

    @Nonnull
    private final String tableName;

    private final String indexType;

    @Nonnull
    private final String name;

    @Nonnull
    private final KeyExpression keyExpression;

    @Nonnull
    private final Map<String, String> options;

    @Nullable
    private final RecordMetaDataProto.Predicate predicate;

    private RecordLayerIndex(@Nonnull final String tableName,
                             @Nonnull final String indexType,
                             @Nonnull final String name,
                             @Nonnull final KeyExpression keyExpression,
                             @Nullable final RecordMetaDataProto.Predicate predicate,
                             @Nonnull final Map<String, String> options) {
        this.tableName = tableName;
        this.indexType = indexType;
        this.name = name;
        this.keyExpression = keyExpression;
        this.predicate = predicate;
        this.options = ImmutableMap.copyOf(options);
    }

    @Nonnull
    @Override
    public String getTableName() {
        return tableName;
    }

    @Nonnull
    @Override
    public String getIndexType() {
        return indexType;
    }

    @Override
    public boolean isUnique() {
        @Nullable String uniqueOption = options.get(IndexOptions.UNIQUE_OPTION);
        return Boolean.parseBoolean(uniqueOption);
    }

    @Override
    public boolean isSparse() {
        return predicate != null;
    }

    @Nullable
    public RecordMetaDataProto.Predicate getPredicate() {
        return predicate;
    }

    @Nonnull
    @Override
    public String getName() {
        return name;
    }

    @Nonnull
    public KeyExpression getKeyExpression() {
        return keyExpression;
    }

    @Nonnull
    public Map<String, String> getOptions() {
        return options;
    }

    @Nonnull
    public static RecordLayerIndex from(@Nonnull final String tableName, @Nonnull final com.apple.foundationdb.record.metadata.Index index) {
        final var indexProto = index.toProto();
        return newBuilder().setName(index.getName())
                .setIndexType(index.getType())
                .setTableName(tableName)
                .setKeyExpression(KeyExpressionTransformFieldVisitor.rewriteFieldExpressions(DataTypeUtils::toUserIdentifier, index.getRootExpression()))
                .setPredicate(indexProto.hasPredicate() ? rewritePredicate(DataTypeUtils::toUserIdentifier, indexProto.getPredicate()) : null)
                .setOptions(index.getOptions())
                .build();
    }

    @Nullable
    public IndexPredicate getRewrittenPredicate(@Nonnull UnaryOperator<String> idTransformer) {
        if (predicate == null) {
            return null;
        }
        return IndexPredicate.fromProto(rewritePredicate(idTransformer, predicate));
    }

    @Nonnull
    private static RecordMetaDataProto.Predicate rewritePredicate(@Nonnull UnaryOperator<String> idTransformer, @Nonnull RecordMetaDataProto.Predicate predicate) {
        if (predicate.hasAndPredicate()) {
            RecordMetaDataProto.AndPredicate oldAnd = predicate.getAndPredicate();
            RecordMetaDataProto.AndPredicate.Builder newAndBuilder = RecordMetaDataProto.AndPredicate.newBuilder();
            for (RecordMetaDataProto.Predicate child : oldAnd.getChildrenList()) {
                newAndBuilder.addChildren(rewritePredicate(idTransformer, child));
            }
            return RecordMetaDataProto.Predicate.newBuilder().setAndPredicate(newAndBuilder.build()).build();
        } else if (predicate.hasOrPredicate()) {
            RecordMetaDataProto.OrPredicate oldOr = predicate.getOrPredicate();
            RecordMetaDataProto.OrPredicate.Builder newOrBuilder = RecordMetaDataProto.OrPredicate.newBuilder();
            for (RecordMetaDataProto.Predicate child : oldOr.getChildrenList()) {
                newOrBuilder.addChildren(rewritePredicate(idTransformer, child));
            }
            return RecordMetaDataProto.Predicate.newBuilder().setOrPredicate(newOrBuilder.build()).build();
        } else if (predicate.hasConstantPredicate()) {
            return predicate;
        } else if (predicate.hasNotPredicate()) {
            return RecordMetaDataProto.Predicate.newBuilder()
                    .setNotPredicate(RecordMetaDataProto.NotPredicate.newBuilder()
                                    .setChild(rewritePredicate(idTransformer, predicate.getNotPredicate().getChild())))
                    .build();
        } else if (predicate.hasValuePredicate()) {
            RecordMetaDataProto.ValuePredicate oldValuePredicate = predicate.getValuePredicate();
            RecordMetaDataProto.ValuePredicate.Builder newValuePredicate = RecordMetaDataProto.ValuePredicate.newBuilder();
            oldValuePredicate.getValueList().forEach(v -> newValuePredicate.addValue(idTransformer.apply(v)));
            newValuePredicate.setComparison(oldValuePredicate.getComparison());
            return RecordMetaDataProto.Predicate.newBuilder().setValuePredicate(newValuePredicate).build();
        } else {
            throw Assert.failUnchecked(ErrorCode.INTERNAL_ERROR, "Unable to translate predicate of unknown type");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RecordLayerIndex that = (RecordLayerIndex) o;
        return Objects.equals(tableName, that.tableName) &&
                Objects.equals(indexType, that.indexType) &&
                Objects.equals(name, that.name) &&
                Objects.equals(keyExpression, that.keyExpression) &&
                Objects.equals(predicate, that.predicate) &&
                Objects.equals(options, that.options);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, indexType, name, keyExpression, options, predicate);
    }

    public static class Builder {
        private String tableName;
        private String indexType;
        private String name;
        private KeyExpression keyExpression;
        @Nullable
        private ImmutableMap.Builder<String, String> optionsBuilder;

        @Nullable
        private RecordMetaDataProto.Predicate predicate;

        @Nonnull
        public Builder setTableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        @Nonnull
        public Builder setIndexType(String indexType) {
            this.indexType = indexType;
            return this;
        }

        @Nonnull
        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        @Nonnull
        public Builder setKeyExpression(KeyExpression keyExpression) {
            this.keyExpression = keyExpression;
            return this;
        }

        @Nonnull
        public Builder setPredicate(@Nullable final RecordMetaDataProto.Predicate predicate) {
            this.predicate = predicate;
            return this;
        }

        @Nonnull
        public Builder setUnique(boolean isUnique) {
            return setOption(IndexOptions.UNIQUE_OPTION, isUnique);
        }

        @Nonnull
        public Builder setOptions(@Nonnull final Map<String, String> options) {
            optionsBuilder = ImmutableMap.builderWithExpectedSize(options.size());
            optionsBuilder.putAll(options);
            return this;
        }

        @Nonnull
        public Builder setOption(@Nonnull final String optionKey, @Nonnull final String optionValue) {
            if (optionsBuilder == null) {
                optionsBuilder = ImmutableMap.builder();
            }
            optionsBuilder.put(optionKey, optionValue);
            return this;
        }

        @Nonnull
        public Builder setOption(@Nonnull final String optionKey, int optionValue) {
            return setOption(optionKey, Integer.toString(optionValue));
        }

        @Nonnull
        public Builder setOption(@Nonnull final String optionKey, boolean optionValue) {
            return setOption(optionKey, Boolean.toString(optionValue));
        }

        @Nonnull
        public RecordLayerIndex build() {
            Assert.notNullUnchecked(name, "index name is not set");
            Assert.notNullUnchecked(tableName, "table name is not set");
            Assert.notNullUnchecked(indexType, "index type is not set");
            Assert.notNullUnchecked(keyExpression, "index key expression is not set");
            return new RecordLayerIndex(tableName, indexType, name, keyExpression, predicate,
                    optionsBuilder == null ? ImmutableMap.of() : optionsBuilder.build());
        }
    }

    @Nonnull
    public static Builder newBuilder() {
        return new Builder();
    }
}
