/*
 * ExtractDataFromIndexEntryVisitor.java
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

import com.apple.foundationdb.record.IndexEntry;
import com.apple.foundationdb.record.RecordCoreArgumentException;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.metadata.IndexTypes;
import com.apple.foundationdb.record.metadata.RecordType;
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
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordVersion;
import com.apple.foundationdb.record.provider.foundationdb.FDBStoredRecord;
import com.apple.foundationdb.record.query.expressions.Field;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.foundationdb.tuple.Versionstamp;
import com.google.common.collect.Iterators;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * A visitor that can extract data from an {@link IndexEntry} by associating the corresponding field names
 * with the value in the index.
 */
public class ExtractDataFromIndexEntryVisitor implements KeyExpressionVisitor<List<ExtractDataFromIndexEntryVisitor.FieldData>> {
    @Nonnull
    private final IndexEntry entry;
    @Nullable
    private FDBRecordVersion version;
    @Nullable
    private Object recordTypeKey;
    @Nullable
    private Iterator<? extends Object> iterator;

    /**
     * A struct combining the field name and fan type (so we know whether each object is a single element of a repeated
     * field, all values concatenated together, or the single value from a non-repeated field). Then there is code to
     * turn a list of this into a {@link Message}.
     */
    public static final class FieldData {
        @Nonnull
        private final String fieldName;
        @Nonnull
        private final KeyExpression.FanType fanType;
        @Nullable
        private final Object data;
        @Nullable
        private final List<FieldData> nestedData;

        private FieldData(@Nonnull FieldKeyExpression field, @Nullable Object data) {
            this.fieldName = field.getFieldName();
            this.fanType = field.getFanType();
            this.data = data;
            this.nestedData = null;
        }

        private FieldData(@Nonnull FieldKeyExpression field, @Nonnull List<FieldData> nestedData) {
            this.fieldName = field.getFieldName();
            this.fanType = field.getFanType();
            this.data = null;
            this.nestedData = nestedData;
        }

        public static Message toMessage(@Nonnull List<FieldData> fieldDataList, @Nonnull Descriptors.Descriptor descriptor) {
            return toMessage(fieldDataList, DynamicMessage.newBuilder(descriptor));
        }

        public static Message toMessage(@Nonnull List<FieldData> fieldDataList, @Nonnull Message.Builder builder) {
            for (FieldData fieldData : fieldDataList) {
                fieldData.addToMessage(builder);
            }
            return builder.build();
        }

        private void addToMessage(@Nonnull Message.Builder builder) {
            Descriptors.FieldDescriptor fieldDescriptor = builder.getDescriptorForType().findFieldByName(fieldName);
            switch (fanType) {
                case None:
                    if (nestedData == null) {
                        builder.setField(fieldDescriptor, data);
                    } else {
                        Message nestedMessage = toMessage(nestedData, fieldDescriptor.getMessageType());
                        Object existingValue = builder.getField(fieldDescriptor);
                        if (existingValue == null) {
                            builder.setField(fieldDescriptor, nestedMessage);
                        } else if (existingValue instanceof Message.Builder) {
                            builder.setField(fieldDescriptor, ((Message.Builder)existingValue).mergeFrom(nestedMessage));
                        } else if (existingValue instanceof Message) {
                            builder.setField(fieldDescriptor, ((Message)existingValue).toBuilder().mergeFrom(nestedMessage));
                        } else {
                            throw new RecordCoreException("unexpected type for existing value of nested field");
                        }
                    }
                    break;
                case FanOut:
                    if (nestedData == null) {
                        builder.addRepeatedField(fieldDescriptor, data);
                    } else {
                        builder.addRepeatedField(fieldDescriptor, toMessage(nestedData, fieldDescriptor.getMessageType()));
                    }
                    break;
                case Concatenate:
                    if (nestedData == null) {
                        if (data != null) {
                            if (data instanceof List) {
                                builder.setField(fieldDescriptor, data);
                            } else if (data instanceof Tuple) {
                                builder.setField(fieldDescriptor, ((Tuple)data).getItems());
                            } else {
                                throw new RecordCoreArgumentException("cannot convert data to concatenated field");
                            }
                        }
                    } else {
                        throw new RecordCoreException("i think this is broken");
                    }
                    break;
                default:
                    throw new RecordCoreArgumentException("unknown fan type: " + fanType);
            }
        }
    }

    private ExtractDataFromIndexEntryVisitor(@Nonnull IndexEntry entry) {
        this.entry = entry;
    }

    private ExtractDataFromIndexEntryVisitor(@Nonnull IndexEntry entry, @Nonnull Iterator<? extends Object> iterator) {
        this.entry = entry;
        this.iterator = iterator;
    }

    @Override
    public List<FieldData> visit(@Nonnull EmptyKeyExpression empty) {
        return Collections.emptyList();
    }

    @Override
    public List<FieldData> visit(@Nonnull FieldKeyExpression field) {
        if (iterator == null) {
            iterator = entry.getKey().iterator();
        }
        return Collections.singletonList(new FieldData(field, iterator.next()));
    }

    @Override
    public List<FieldData> visit(@Nonnull FunctionKeyExpression function) {
        if (iterator == null) {
            iterator = entry.getKey().iterator();
        }
        for (int i = 0; i < function.getColumnSize(); i++) {
            iterator.next();
        }
        return Collections.emptyList();
    }

    @Override
    public List<FieldData> visit(@Nonnull GroupingKeyExpression grouping) {
        // FIXME: I think the actual right thing here is to do different things for different index types
        return grouping.getChild().accept(this);
    }

    @Override
    public List<FieldData> visit(@Nonnull KeyWithValueExpression keyWithValue) {
        if (iterator != null) {
            throw new IllegalStateException("keyWithValue is not at top level of visitor");
        }
        iterator = Iterators.concat(entry.getKey().iterator(), entry.getValue().iterator());
        return keyWithValue.getChild().accept(this);
    }

    @Override
    public List<FieldData> visit(@Nonnull ListKeyExpression list) {
        if (iterator == null) {
            iterator = entry.getKey().iterator();
        }
        List<FieldData> extractedFromChildren = new ArrayList<>();
        for (KeyExpression child : list.getChildren()) {
            Object nested = iterator.next();
            Iterator<? extends Object> nestedIterator;
            if (nested instanceof Iterable<?>) {
                nestedIterator = ((Iterable<?>)nested).iterator();
            } else {
                throw new RecordCoreArgumentException("list key expression has non-iterable child");
            }
            ExtractDataFromIndexEntryVisitor childVisitor = new ExtractDataFromIndexEntryVisitor(entry, nestedIterator);
            List<FieldData> childData = child.accept(childVisitor);
            extractedFromChildren.addAll(childData);
            if (childVisitor.recordTypeKey != null) {
                recordTypeKey = childVisitor.recordTypeKey;
            }
            if (childVisitor.version != null) {
                version = childVisitor.version;
            }
        }
        return extractedFromChildren;
    }

    @Override
    public List<FieldData> visit(@Nonnull LiteralKeyExpression literal) {
        if (iterator == null) {
            iterator = entry.getKey().iterator();
        }
        Object obj = iterator.next();
        if (!Objects.equals(literal.getValue(), obj)) {
            throw new RecordCoreArgumentException("literal in index entry did not match literal in expression");
        }
        return Collections.emptyList();
    }

    @Override
    public List<FieldData> visit(@Nonnull NestingKeyExpression nesting) {
        if (nesting.getParent().getFanType() == KeyExpression.FanType.Concatenate) {
            throw new UnsupportedOperationException("i think this is broken");
        } else {
            List<FieldData> nestedData = nesting.getChild().accept(this);
            return Collections.singletonList(new FieldData(nesting.getParent(), nestedData));
        }
    }

    @Override
    public List<FieldData> visit(@Nonnull RecordTypeKeyExpression recordType) {
        if (iterator == null) {
            iterator = entry.getKey().iterator();
        }
        recordTypeKey = iterator.next();
        return Collections.emptyList();
    }

    @Override
    public List<FieldData> visit(@Nonnull SplitKeyExpression split) {
        if (iterator == null) {
            iterator = entry.getKey().iterator();
        }
        for (int i = 0; i < split.getSplitSize(); i++) {
            iterator.next();
        }
        return Collections.emptyList();
    }

    @Override
    public List<FieldData> visit(@Nonnull ThenKeyExpression then) {
        List<FieldData> extractedFromChildren = new ArrayList<>();
        for (KeyExpression child : then.getChildren()) {
            extractedFromChildren.addAll(child.accept(this));
        }
        return extractedFromChildren;
    }

    @Override
    public List<FieldData> visit(@Nonnull VersionKeyExpression version) {
        if (iterator == null) {
            iterator = entry.getKey().iterator();
        }
        Object versionObj = iterator.next();
        if (versionObj instanceof Versionstamp) {
            this.version = FDBRecordVersion.fromVersionstamp((Versionstamp) versionObj);
        } else if (versionObj instanceof FDBRecordVersion) {
            this.version = (FDBRecordVersion) versionObj;
        } else {
            throw new RecordCoreArgumentException("found non-version object where version expected in index entry");
        }
        return Collections.emptyList();
    }

    @Nonnull
    public static FDBStoredRecord<Message> getMessageFromIndexEntry(@Nonnull RecordMetaData metaData, @Nonnull IndexEntry indexEntry) {
        String indexType = indexEntry.getIndex().getType();
        if (!indexType.equals(IndexTypes.VALUE) && !indexType.equals(IndexTypes.VERSION)) {
            throw new RecordCoreArgumentException("cannot extract values if index type is not value or version");
        }
        ExtractDataFromIndexEntryVisitor visitor = new ExtractDataFromIndexEntryVisitor(indexEntry);
        // Not quite right--should have primary key information added
        List<FieldData> fieldDataList = indexEntry.getIndex().getRootExpression().accept(visitor);
        RecordType recordType;
        if (visitor.recordTypeKey != null) {
            recordType = metaData.getRecordTypes().values().stream()
                    .filter(type -> Objects.equals(type.getRecordTypeKey(), visitor.recordTypeKey))
                    .findAny()
                    .orElseThrow(() -> new RecordCoreException("no record type found for record type key " + visitor.recordTypeKey));
        } else if (metaData.recordTypesForIndex(indexEntry.getIndex()).size() == 1) {
            recordType = metaData.recordTypesForIndex(indexEntry.getIndex()).iterator().next();
        } else {
            throw new RecordCoreArgumentException("cannot determine record type from index entry");
        }
        Message record = FieldData.toMessage(fieldDataList, recordType.getDescriptor());
        return new FDBStoredRecord<>(indexEntry.getPrimaryKey(), recordType, record, null, visitor.version);
    }
}
