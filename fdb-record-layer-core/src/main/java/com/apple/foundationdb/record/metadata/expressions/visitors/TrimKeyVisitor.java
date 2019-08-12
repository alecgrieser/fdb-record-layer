/*
 * TrimKeyVisitor.java
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

import com.apple.foundationdb.record.RecordCoreArgumentException;
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
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A visitor used to trim a key according to a mask.
 */
public class TrimKeyVisitor implements KeyExpressionVisitor<KeyExpression> {
    @Nonnull
    private final int[] columnMask;
    private int currentPos;

    private TrimKeyVisitor(@Nonnull int[] columnMask) {
        this.columnMask = columnMask;
        this.currentPos = 0;
    }

    @Nonnull
    private KeyExpression scalarOrEmpty(@Nonnull KeyExpression expr) {
        if (columnMask[currentPos++] < 0) {
            return EmptyKeyExpression.EMPTY;
        } else {
            return expr;
        }
    }

    @Override
    public EmptyKeyExpression visit(@Nonnull EmptyKeyExpression empty) {
        return empty;
    }

    @Override
    public KeyExpression visit(@Nonnull FieldKeyExpression field) {
        return scalarOrEmpty(field);
    }

    @Override
    public KeyExpression visit(@Nonnull FunctionKeyExpression function) {
        for (int i = 0; i < function.getColumnSize(); i++) {
            if (columnMask[currentPos + i] < 0) {
                throw new RecordCoreArgumentException("cannot extract only some columns from function");
            }
        }
        currentPos += function.getColumnSize();
        return function;
    }

    @Override
    public KeyExpression visit(@Nonnull GroupingKeyExpression grouping) {
        return grouping.getWholeKey().accept(this);
    }

    @Override
    public KeyExpression visit(@Nonnull KeyWithValueExpression keyWithValue) {
        return keyWithValue.getKeyExpression().accept(this);
    }

    @Override
    public KeyExpression visit(@Nonnull ListKeyExpression list) {
        List<KeyExpression> childValues = new ArrayList<>(list.getChildren().size());
        boolean anyChanged = false;
        for (KeyExpression child : list.getChildren()) {
            if (columnMask[currentPos++] < 0) {
                childValues.add(child);
            } else {
                anyChanged = true;
            }
        }
        if (anyChanged) {
            if (childValues.isEmpty()) {
                return EmptyKeyExpression.EMPTY;
            } else {
                // Note that this is the right thing to do even if childValues.size() == 1
                // (unlike a ThenKeyExpression) because of how nested tuples work.
                return new ListKeyExpression(childValues);
            }
        } else {
            return list;
        }
    }

    @Override
    public KeyExpression visit(@Nonnull LiteralKeyExpression literal) {
        return scalarOrEmpty(literal);
    }

    @Override
    public KeyExpression visit(@Nonnull NestingKeyExpression nesting) {
        KeyExpression newChild = nesting.getChild().accept(this);
        if (newChild instanceof EmptyKeyExpression) {
            return EmptyKeyExpression.EMPTY;
        } else if (newChild == nesting.getChild()) {
            return nesting;
        } else {
            return new NestingKeyExpression(nesting.getParent(), newChild);
        }
    }

    @Override
    public KeyExpression visit(@Nonnull RecordTypeKeyExpression recordType) {
        return scalarOrEmpty(recordType);
    }

    @Override
    public KeyExpression visit(@Nonnull SplitKeyExpression split) {
        for (int i = 0; i < split.getSplitSize(); i++) {
            if (columnMask[currentPos + i] >= 0) {
                throw new RecordCoreArgumentException("cannot extract only some columns of split expression");
            }
        }
        currentPos += split.getSplitSize();
        return split;
    }

    @Override
    public KeyExpression visit(@Nonnull ThenKeyExpression then) {
        List<KeyExpression> childValues = new ArrayList<>(then.getChildren().size());
        boolean anyChanged = false;
        for (KeyExpression child : then.getChildren()) {
            KeyExpression newChild = child.accept(this);
            anyChanged = anyChanged || newChild != child;
            if (!(newChild instanceof EmptyKeyExpression)) {
                childValues.add(newChild);
            }
        }
        if (anyChanged) {
            if (childValues.isEmpty()) {
                return EmptyKeyExpression.EMPTY;
            } else if (childValues.size() == 1) {
                return childValues.get(0);
            } else {
                return new ThenKeyExpression(childValues);
            }
        } else {
            return then;
        }
    }

    @Override
    public KeyExpression visit(@Nonnull VersionKeyExpression version) {
        return scalarOrEmpty(version);
    }

    @Nonnull
    public static KeyExpression trimKey(@Nonnull KeyExpression expr, @Nullable int[] columnMask) {
        if (columnMask == null) {
            return expr;
        } else if (columnMask.length != expr.getColumnSize()) {
            throw new RecordCoreArgumentException("column mask does not match column size");
        } else {
            TrimKeyVisitor visitor = new TrimKeyVisitor(columnMask);
            return expr.accept(visitor);
        }
    }
}
