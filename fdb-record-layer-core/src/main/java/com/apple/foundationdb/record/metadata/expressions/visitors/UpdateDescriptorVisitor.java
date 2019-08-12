/*
 * RenameFieldVisitor.java
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
import com.google.protobuf.Descriptors;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * A key expression visitor that rewrites a key expression for a new descriptor.
 */
public class UpdateDescriptorVisitor implements KeyExpressionVisitor<KeyExpression> {
    @Nonnull
    private Descriptors.Descriptor oldDescriptor;
    @Nonnull
    private Descriptors.Descriptor newDescriptor;

    public UpdateDescriptorVisitor(@Nonnull Descriptors.Descriptor oldDescriptor, @Nonnull Descriptors.Descriptor newDescriptor) {
        this.oldDescriptor = oldDescriptor;
        this.newDescriptor = newDescriptor;
    }

    @Override
    public EmptyKeyExpression visit(@Nonnull EmptyKeyExpression empty) {
        return empty;
    }

    @Override
    public FieldKeyExpression visit(@Nonnull FieldKeyExpression field) {
        Descriptors.FieldDescriptor oldFieldDescriptor = oldDescriptor.findFieldByName(field.getFieldName());
        if (oldFieldDescriptor == null) {
            throw new RecordCoreArgumentException("field " + field.getFieldName() + " not found in old descriptor");
        }
        Descriptors.FieldDescriptor newFieldDescriptor = newDescriptor.findFieldByNumber(oldFieldDescriptor.getNumber());
        if (newFieldDescriptor == null) {
            throw new RecordCoreArgumentException("field number " + oldFieldDescriptor.getNumber() + " not found in new descriptor");
        }
        if (oldFieldDescriptor.getName().equals(newFieldDescriptor.getName())) {
            return field;
        } else {
            return new FieldKeyExpression(newFieldDescriptor.getName(), field.getFanType(), field.getNullStandin());
        }
    }

    @Override
    public FunctionKeyExpression visit(@Nonnull FunctionKeyExpression function) {
        KeyExpression newArguments = visitChild(function);
        if (newArguments == function.getArguments()) {
            return function;
        } else {
            return FunctionKeyExpression.create(function.getName(), newArguments);
        }
    }

    @Override
    public GroupingKeyExpression visit(@Nonnull GroupingKeyExpression grouping) {
        KeyExpression newWholeKey = visitChild(grouping);
        if (newWholeKey == grouping.getWholeKey()) {
            return grouping;
        } else {
            return new GroupingKeyExpression(newWholeKey, grouping.getGroupedCount());
        }
    }

    @Override
    public KeyWithValueExpression visit(@Nonnull KeyWithValueExpression keyWithValue) {
        KeyExpression newWholeKey = visitChild(keyWithValue);
        if (newWholeKey == keyWithValue.getChild()) {
            return keyWithValue;
        } else {
            return new KeyWithValueExpression(newWholeKey, keyWithValue.getSplitPoint());
        }
    }

    @Override
    public ListKeyExpression visit(@Nonnull ListKeyExpression list) {
        List<KeyExpression> newChildren = visitChildren(list);
        if (anyChildChanged(list.getChildren(), newChildren)) {
            return new ListKeyExpression(newChildren);
        } else {
            return list;
        }
    }

    @Override
    public LiteralKeyExpression visit(@Nonnull LiteralKeyExpression literal) {
        return literal;
    }

    @Override
    public NestingKeyExpression visit(@Nonnull NestingKeyExpression nesting) {
        FieldKeyExpression newParent = visit(nesting.getParent());
        UpdateDescriptorVisitor childVisitor = new UpdateDescriptorVisitor(
                oldDescriptor.findFieldByName(nesting.getParent().getFieldName()).getMessageType(),
                newDescriptor.findFieldByName(newParent.getFieldName()).getMessageType()
        );
        KeyExpression newChild = nesting.getChild().accept(childVisitor);
        if (newParent == nesting.getParent() && newChild == nesting.getChild()) {
            return nesting;
        } else {
            return new NestingKeyExpression(newParent, newChild);
        }
    }

    @Override
    public RecordTypeKeyExpression visit(@Nonnull RecordTypeKeyExpression recordType) {
        return recordType;
    }

    @Override
    public SplitKeyExpression visit(@Nonnull SplitKeyExpression split) {
        KeyExpression newJoined = split.getJoined().accept(this);
        if (newJoined == split.getJoined()) {
            return split;
        } else {
            return new SplitKeyExpression(newJoined, split.getSplitSize());
        }
    }

    @Override
    public ThenKeyExpression visit(@Nonnull ThenKeyExpression then) {
        List<KeyExpression> newChildren = visitChildren(then);
        if (anyChildChanged(then.getChildren(), newChildren)) {
            return new ThenKeyExpression(newChildren);
        } else {
            return then;
        }
    }

    @Override
    public VersionKeyExpression visit(@Nonnull VersionKeyExpression version) {
        return version;
    }

    private static boolean anyChildChanged(@Nonnull List<KeyExpression> oldChildren, @Nonnull List<KeyExpression> newChildren) {
        boolean anyChanged = false;
        for (int i = 0; i < oldChildren.size(); i++) {
            if (oldChildren.get(i) != newChildren.get(i)) {
                anyChanged = true;
                break;
            }
        }
        return anyChanged;
    }
}
