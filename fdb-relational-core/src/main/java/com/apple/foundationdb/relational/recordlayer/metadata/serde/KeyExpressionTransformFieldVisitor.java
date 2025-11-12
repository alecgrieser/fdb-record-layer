/*
 * KeyExpressionTransformFieldVisitor.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2025 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.relational.recordlayer.metadata.serde;

import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.metadata.expressions.EmptyKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.FieldKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.FunctionKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.GroupingKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.metadata.expressions.KeyExpressionWithValue;
import com.apple.foundationdb.record.metadata.expressions.KeyWithValueExpression;
import com.apple.foundationdb.record.metadata.expressions.ListKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.LiteralKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.NestingKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.RecordTypeKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.SplitKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.ThenKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.VersionKeyExpression;
import com.apple.foundationdb.record.query.plan.cascades.KeyExpressionVisitor;
import com.apple.foundationdb.relational.api.exceptions.ErrorCode;
import com.apple.foundationdb.relational.util.Assert;
import com.google.common.collect.ImmutableList;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.UnaryOperator;

public class KeyExpressionTransformFieldVisitor implements KeyExpressionVisitor<KeyExpressionTransformFieldVisitor.KeyExpressionTransformFieldVisitorState, KeyExpression> {
    private final UnaryOperator<String> fieldRewriter;

    private KeyExpressionTransformFieldVisitor(UnaryOperator<String> fieldRewriter) {
        this.fieldRewriter = fieldRewriter;
    }

    @Override
    public KeyExpressionTransformFieldVisitorState getCurrentState() {
        return null;
    }

    @Nonnull
    @Override
    public KeyExpression visitExpression(@Nonnull final KeyExpression keyExpression) {
        if (keyExpression instanceof LiteralKeyExpression<?>
                || keyExpression instanceof VersionKeyExpression
                || keyExpression instanceof RecordTypeKeyExpression) {
            return keyExpression;
        } else if (keyExpression instanceof GroupingKeyExpression) {
            return visitExpression((GroupingKeyExpression)keyExpression);
        } else if (keyExpression instanceof SplitKeyExpression) {
            return visitExpression((SplitKeyExpression) keyExpression);
        } else {
            throw Assert.failUnchecked(ErrorCode.INVALID_SCHEMA_TEMPLATE, "unable to prepare schema template with key expression of type " + keyExpression.getClass());
        }
    }

    @Nonnull
    public GroupingKeyExpression visitExpression(@Nonnull final GroupingKeyExpression groupingKeyExpression) {
        KeyExpression wholeKey = groupingKeyExpression.getWholeKey();
        KeyExpression newWholeKey = wholeKey.expand(this);
        if (wholeKey == newWholeKey) {
            return groupingKeyExpression;
        }
        return new GroupingKeyExpression(newWholeKey, groupingKeyExpression.getGroupedCount());
    }

    @Nonnull
    public SplitKeyExpression visitExpression(@Nonnull final SplitKeyExpression splitKeyExpression) {
        KeyExpression joined = splitKeyExpression.getJoined();
        KeyExpression newJoined = joined.expand(this);
        if (joined == newJoined) {
            return splitKeyExpression;
        }
        return new SplitKeyExpression(joined, splitKeyExpression.getColumnSize());
    }

    @Nonnull
    @Override
    public EmptyKeyExpression visitExpression(@Nonnull final EmptyKeyExpression emptyKeyExpression) {
        return emptyKeyExpression;
    }

    @Nonnull
    @Override
    public FieldKeyExpression visitExpression(@Nonnull final FieldKeyExpression fieldKeyExpression) {
        final String newField = fieldRewriter.apply(fieldKeyExpression.getFieldName());
        if (newField.equals(fieldKeyExpression.getFieldName())) {
            return fieldKeyExpression;
        }
        return new FieldKeyExpression(newField, fieldKeyExpression.getFanType(), fieldKeyExpression.getNullStandin());
    }

    @Nonnull
    @Override
    public KeyExpressionWithValue visitExpression(@Nonnull final KeyExpressionWithValue keyExpressionWithValue) {
        throw Assert.failUnchecked(ErrorCode.INVALID_SCHEMA_TEMPLATE, "unable to prepare schema template with value key expression");
    }

    @Nonnull
    @Override
    public FunctionKeyExpression visitExpression(@Nonnull final FunctionKeyExpression functionKeyExpression) {
        final KeyExpression arguments = functionKeyExpression.getArguments();
        final KeyExpression newArguments = arguments.expand(this);
        if (arguments == newArguments) {
            return functionKeyExpression;
        }
        return Key.Expressions.function(functionKeyExpression.getName(), newArguments);
    }

    @Nonnull
    @Override
    public KeyWithValueExpression visitExpression(@Nonnull final KeyWithValueExpression keyWithValueExpression) {
        final KeyExpression wholeKey = keyWithValueExpression.getInnerKey();
        final KeyExpression newWholeKey = wholeKey.expand(this);
        if (wholeKey == newWholeKey) {
            return keyWithValueExpression;
        }
        return Key.Expressions.keyWithValue(newWholeKey, keyWithValueExpression.getSplitPoint());
    }

    @Nonnull
    @Override
    public NestingKeyExpression visitExpression(@Nonnull final NestingKeyExpression nestingKeyExpression) {
        final FieldKeyExpression parent = nestingKeyExpression.getParent();
        final FieldKeyExpression newParent = visitExpression(parent);
        final KeyExpression child = nestingKeyExpression.getChild();
        final KeyExpression newChild = child.expand(this);

        if (parent == newParent && child == newChild) {
            return nestingKeyExpression;
        }
        return new NestingKeyExpression(newParent, newChild);
    }

    @Nonnull
    @Override
    public KeyExpression visitExpression(@Nonnull final ThenKeyExpression thenKeyExpression) {
        final List<KeyExpression> children = thenKeyExpression.getChildren();
        final List<KeyExpression> newChildren = rewriteChildren(children);
        if (children == newChildren) {
            return thenKeyExpression;
        }
        return new ThenKeyExpression(newChildren);
    }

    @Nonnull
    @Override
    public KeyExpression visitExpression(@Nonnull final ListKeyExpression listKeyExpression) {
        final List<KeyExpression> children = listKeyExpression.getChildren();
        final List<KeyExpression> newChildren = rewriteChildren(children);
        if (children == newChildren) {
            return listKeyExpression;
        }
        return new ListKeyExpression(newChildren);
    }

    @Nonnull
    private List<KeyExpression> rewriteChildren(@Nonnull List<KeyExpression> children) {
        final ImmutableList.Builder<KeyExpression> newChildren = ImmutableList.builderWithExpectedSize(children.size());
        boolean anyChanged = false;
        for (KeyExpression child : children) {
            KeyExpression newChild = child.expand(this);
            newChildren.add(newChild);
            anyChanged |= newChild != child;
        }
        if (!anyChanged) {
            return children;
        }
        return newChildren.build();
    }

    public class KeyExpressionTransformFieldVisitorState implements KeyExpressionVisitor.State {

    }

    @Nonnull
    public static KeyExpression rewriteFieldExpressions(@Nonnull UnaryOperator<String> fieldRewriter, @Nonnull KeyExpression expression) {
        KeyExpressionTransformFieldVisitor visitor = new KeyExpressionTransformFieldVisitor(fieldRewriter);
        return expression.expand(visitor);
    }
}
