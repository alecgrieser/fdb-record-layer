/*
 * ColumnSizeVisitor.java
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
 * Visitor for key expressions that computes the number of columns of the output of the expression.
 */
public class ColumnSizeVisitor implements KeyExpressionVisitor<Integer> {
    @Override
    public Integer visit(@Nonnull EmptyKeyExpression empty) {
        return 0;
    }

    @Override
    public Integer visit(@Nonnull FieldKeyExpression field) {
        return 1;
    }

    @Override
    public Integer visit(@Nonnull FunctionKeyExpression function) {
        return function.getColumnSize();
    }

    @Override
    public Integer visit(@Nonnull GroupingKeyExpression grouping) {
        return grouping.getChild().accept(this);
    }

    @Override
    public Integer visit(@Nonnull KeyWithValueExpression keyWithValue) {
        return keyWithValue.getSplitPoint();
    }

    @Override
    public Integer visit(@Nonnull ListKeyExpression list) {
        return list.getChildren().size();
    }

    @Override
    public Integer visit(@Nonnull LiteralKeyExpression literal) {
        return 1;
    }

    @Override
    public Integer visit(@Nonnull NestingKeyExpression nesting) {
        return nesting.getChild().accept(this);
    }

    @Override
    public Integer visit(@Nonnull RecordTypeKeyExpression recordType) {
        return 1;
    }

    @Override
    public Integer visit(@Nonnull SplitKeyExpression split) {
        return split.getSplitSize();
    }

    @Override
    public Integer visit(@Nonnull ThenKeyExpression then) {
        return then.getChildren().stream()
                .mapToInt(child -> child.accept(this))
                .sum();
    }

    @Override
    public Integer visit(@Nonnull VersionKeyExpression version) {
        return 1;
    }
}
