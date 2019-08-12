/*
 * AugmentKeyExpressionVisitor.java
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

import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.metadata.expressions.EmptyKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.FieldKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.FunctionKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.GroupingKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.metadata.expressions.KeyWithValueExpression;
import com.apple.foundationdb.record.metadata.expressions.ListKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.LiteralKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.NestingKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.RecordTypeKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.SplitKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.ThenKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.VersionKeyExpression;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class AugmentKeyExpressionVisitor implements KeyExpressionVisitor<KeyExpression> {
    @Nonnull
    private final KeyExpression exprToAdd;

    private AugmentKeyExpressionVisitor(@Nonnull KeyExpression exprToAdd) {
        this.exprToAdd = exprToAdd;
    }

    @Nonnull
    private KeyExpression concatWith(@Nonnull KeyExpression orig) {
        return Key.Expressions.concat(orig, exprToAdd);
    }

    @Override
    public KeyExpression visit(@Nonnull EmptyKeyExpression empty) {
        return exprToAdd;
    }

    @Override
    public KeyExpression visit(@Nonnull FieldKeyExpression field) {
        return concatWith(field);
    }

    @Override
    public KeyExpression visit(@Nonnull FunctionKeyExpression function) {
        return concatWith(function);
    }

    @Override
    public KeyExpression visit(@Nonnull GroupingKeyExpression grouping) {
        KeyExpression newWholeKey = grouping.getWholeKey().accept(this);
        if (newWholeKey != grouping.getWholeKey()) {
            int newGroupedCount = newWholeKey.getColumnSize() - grouping.getGroupingCount();
            return new GroupingKeyExpression(newWholeKey, newGroupedCount);
        } else {
            return grouping;
        }
    }

    @Override
    public KeyExpression visit(@Nonnull KeyWithValueExpression keyWithValue) {
        KeyExpression newKeyExpression = keyWithValue.getKeyExpression().accept(this);
        KeyExpression newInnerKey = AugmentKeyExpressionVisitor.augmentKeyExpression(newKeyExpression, keyWithValue.getValueExpression());
        return new KeyWithValueExpression(newInnerKey, newKeyExpression.getColumnSize());
    }

    @Override
    public KeyExpression visit(@Nonnull ListKeyExpression list) {
        return concatWith(list);
    }

    @Override
    public KeyExpression visit(@Nonnull LiteralKeyExpression literal) {
        return concatWith(literal);
    }

    @Override
    public KeyExpression visit(@Nonnull NestingKeyExpression nesting) {
        return concatWith(nesting);
    }

    @Override
    public KeyExpression visit(@Nonnull RecordTypeKeyExpression recordType) {
        return concatWith(recordType);
    }

    @Override
    public KeyExpression visit(@Nonnull SplitKeyExpression split) {
        return concatWith(split);
    }

    @Override
    public KeyExpression visit(@Nonnull ThenKeyExpression then) {
        List<KeyExpression> children = new ArrayList<>(then.getChildren());
        children.add(exprToAdd);
        return new ThenKeyExpression(children);
    }

    @Override
    public KeyExpression visit(@Nonnull VersionKeyExpression version) {
        return concatWith(version);
    }

    @Nonnull
    public static KeyExpression augmentKeyExpression(@Nonnull KeyExpression expr, @Nonnull KeyExpression exprToAdd) {
        if (exprToAdd instanceof EmptyKeyExpression) {
            return expr;
        }
        AugmentKeyExpressionVisitor visitor = new AugmentKeyExpressionVisitor(exprToAdd);
        return expr.accept(visitor);
    }
}
