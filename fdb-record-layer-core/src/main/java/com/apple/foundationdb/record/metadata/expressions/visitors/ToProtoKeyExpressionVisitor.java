/*
 * ToProtoKeyExpressionVisitor.java
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

package com.apple.foundationdb.record.metadata.expressions.visitors;

import com.apple.foundationdb.record.RecordMetaDataProto;
import com.apple.foundationdb.record.metadata.expressions.EmptyKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.FieldKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.FunctionKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.GroupingKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.KeyWithValueExpression;
import com.apple.foundationdb.record.metadata.expressions.ListKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.LiteralKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.NestingKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.RecordTypeKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.SplitKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.ThenKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.VersionKeyExpression;

import javax.annotation.Nonnull;

/**
 * A visitor that converts a {@link com.apple.foundationdb.record.metadata.expressions.KeyExpression}
 * into its Protobuf representation.
 */
public class ToProtoKeyExpressionVisitor implements KeyExpressionVisitor<RecordMetaDataProto.KeyExpression> {
    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull EmptyKeyExpression empty) {
        return EmptyKeyExpression.EMPTY_PROTO;
    }

    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull FieldKeyExpression field) {
        return RecordMetaDataProto.KeyExpression.newBuilder()
                .setField(field.toProto())
                .build();
    }

    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull FunctionKeyExpression function) {
        return RecordMetaDataProto.KeyExpression.newBuilder()
                .setFunction(function.toProto())
                .build();
    }

    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull GroupingKeyExpression grouping) {
        return RecordMetaDataProto.KeyExpression.newBuilder()
                .setGrouping(grouping.toProto())
                .build();
    }

    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull KeyWithValueExpression keyWithValue) {
        return RecordMetaDataProto.KeyExpression.newBuilder()
                .setKeyWithValue(keyWithValue.toProto())
                .build();
    }

    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull ListKeyExpression list) {
        return RecordMetaDataProto.KeyExpression.newBuilder()
                .setList(list.toProto())
                .build();
    }

    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull LiteralKeyExpression literal) {
        return RecordMetaDataProto.KeyExpression.newBuilder()
                .setValue(literal.toProto())
                .build();
    }

    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull NestingKeyExpression nesting) {
        return RecordMetaDataProto.KeyExpression.newBuilder()
                .setNesting(nesting.toProto())
                .build();
    }

    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull RecordTypeKeyExpression recordType) {
        return RecordMetaDataProto.KeyExpression.newBuilder()
                .setRecordTypeKey(recordType.toProto())
                .build();
    }

    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull SplitKeyExpression split) {
        return RecordMetaDataProto.KeyExpression.newBuilder()
                .setSplit(split.toProto())
                .build();
    }

    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull ThenKeyExpression then) {
        return RecordMetaDataProto.KeyExpression.newBuilder()
                .setThen(then.toProto())
                .build();
    }

    @Override
    public RecordMetaDataProto.KeyExpression visit(@Nonnull VersionKeyExpression version) {
        return RecordMetaDataProto.KeyExpression.newBuilder()
                .setVersion(version.toProto())
                .build();
    }
}
