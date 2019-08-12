/*
 * KeyExpressionVisitor.java
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

import com.apple.foundationdb.record.metadata.expressions.EmptyKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.FieldKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.FunctionKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.GroupingKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.KeyExpressionWithChild;
import com.apple.foundationdb.record.metadata.expressions.KeyExpressionWithChildren;
import com.apple.foundationdb.record.metadata.expressions.KeyWithValueExpression;
import com.apple.foundationdb.record.metadata.expressions.ListKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.LiteralKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.NestingKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.RecordTypeKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.SplitKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.ThenKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.VersionKeyExpression;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Visitor interface for traversing a {@link com.apple.foundationdb.record.metadata.expressions.KeyExpression}
 * tree.
 *
 * @param <T> return type of visitor
 */
public interface KeyExpressionVisitor<T> {

    T visit(@Nonnull EmptyKeyExpression empty);

    T visit(@Nonnull FieldKeyExpression field);

    T visit(@Nonnull FunctionKeyExpression function);

    T visit(@Nonnull GroupingKeyExpression grouping);

    T visit(@Nonnull KeyWithValueExpression keyWithValue);

    T visit(@Nonnull ListKeyExpression list);

    T visit(@Nonnull LiteralKeyExpression literal);

    T visit(@Nonnull NestingKeyExpression nesting);

    T visit(@Nonnull RecordTypeKeyExpression recordType);

    T visit(@Nonnull SplitKeyExpression split);

    T visit(@Nonnull ThenKeyExpression then);

    T visit(@Nonnull VersionKeyExpression version);

    default T visitChild(@Nonnull KeyExpressionWithChild keyExpression) {
        return keyExpression.getChild().accept(this);
    }

    @Nonnull
    default List<T> visitChildren(@Nonnull KeyExpressionWithChildren keyExpression) {
        return keyExpression.getChildren().stream().map(child -> child.accept(this)).collect(Collectors.toList());
    }
}
