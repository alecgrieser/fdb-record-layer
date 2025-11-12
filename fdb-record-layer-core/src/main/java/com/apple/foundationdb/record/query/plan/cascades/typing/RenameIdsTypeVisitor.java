/*
 * RenameIdsTypeVisitor.java
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

package com.apple.foundationdb.record.query.plan.cascades.typing;

import com.google.common.collect.ImmutableList;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.function.UnaryOperator;

public class RenameIdsTypeVisitor implements TypeVisitor<Type> {
    private final UnaryOperator<String> idRenamer;

    private RenameIdsTypeVisitor(@Nonnull UnaryOperator<String> idRenamer) {
        this.idRenamer = idRenamer;
    }

    @Override
    public Type visit(final Type.Null nullType) {
        return nullType;
    }

    @Override
    public Type visit(final Type.None noneType) {
        return noneType;
    }

    @Override
    public Type visit(final Type.Any anyType) {
        return anyType;
    }

    @Override
    public Type visit(final Type.AnyRecord anyRecordType) {
        return anyRecordType;
    }

    @Override
    public Type visit(final Type.Relation relationType) {
        Type inner = relationType.getInnerType();
        if (inner == null) {
            return relationType;
        }
        Type renamedInner = inner.visit(this);
        if (renamedInner == inner) {
            return relationType;
        }
        return new Type.Relation(renamedInner);
    }

    @Override
    public Type visit(final Type.Array arrayType) {
        Type elementType = arrayType.getElementType();
        if (elementType == null) {
            return arrayType;
        }
        Type renamedElement = elementType.visit(this);
        if (renamedElement == elementType) {
            return arrayType;
        }
        return new Type.Array(arrayType.isNullable(), renamedElement);
    }

    @Override
    public Type visit(final Type.Primitive primitiveType) {
        return primitiveType;
    }

    @Override
    public Type visit(final Type.Vector vectorType) {
        return vectorType;
    }

    @Override
    public Type visit(final Type.Uuid uuidType) {
        return uuidType;
    }

    @Override
    public Type visit(final Type.Record recordType) {
        final String name = recordType.getName();
        final String newName = name == null ? null : idRenamer.apply(name);

        final ImmutableList.Builder<Type.Record.Field> newFields = ImmutableList.builderWithExpectedSize(recordType.getFields().size());
        for (Type.Record.Field field : recordType.getFields()) {
            Type newFieldType = field.getFieldType().visit(this);
            Optional<String> newFieldNameOptional = field.getFieldNameOptional().map(idRenamer);
            newFields.add(new Type.Record.Field(newFieldType, newFieldNameOptional, field.getFieldIndexOptional()));
        }

        return new Type.Record(newName, recordType.isNullable(), newFields.build());
    }

    @Override
    public Type visit(final Type.Enum enumType) {
        final String name = enumType.getName();
        final String newName = name == null ? null : idRenamer.apply(name);

        final ImmutableList.Builder<Type.Enum.EnumValue> newEnumValues = ImmutableList.builderWithExpectedSize(enumType.getEnumValues().size());
        for (Type.Enum.EnumValue enumValue : enumType.getEnumValues()) {
            newEnumValues.add(new Type.Enum.EnumValue(idRenamer.apply(enumValue.getName()), enumValue.getNumber()));
        }

        return new Type.Enum(enumType.isNullable, newEnumValues.build(), newName);
    }

    @Nonnull
    public static Type renameIds(@Nonnull UnaryOperator<String> idRenamer, @Nonnull Type type) {
        RenameIdsTypeVisitor renamerVisitor = new RenameIdsTypeVisitor(idRenamer);
        return type.visit(renamerVisitor);
    }
}
