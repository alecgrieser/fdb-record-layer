/*
 * PatternForLikeValue.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2022 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.query.plan.cascades.values;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.annotation.SpotBugsSuppressWarnings;
import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.ObjectPlanHash;
import com.apple.foundationdb.record.PlanDeserializer;
import com.apple.foundationdb.record.PlanHashable;
import com.apple.foundationdb.record.PlanSerializationContext;
import com.apple.foundationdb.record.planprotos.PPatternForLikeValue;
import com.apple.foundationdb.record.planprotos.PValue;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.record.query.plan.cascades.AliasMap;
import com.apple.foundationdb.record.query.plan.cascades.BuiltInFunction;
import com.apple.foundationdb.record.query.plan.explain.ExplainTokensWithPrecedence;
import com.apple.foundationdb.record.query.plan.explain.ExplainTokensWithPrecedence.Precedence;
import com.apple.foundationdb.record.query.plan.cascades.SemanticException;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type.TypeCode;
import com.apple.foundationdb.record.query.plan.cascades.typing.Typed;
import com.apple.foundationdb.util.StringUtils;
import com.google.auto.service.AutoService;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.protobuf.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A {@link Value} that applies a like operator on its child expressions.
 */
@API(API.Status.EXPERIMENTAL)
public class PatternForLikeValue extends AbstractValue {
    private static final ObjectPlanHash BASE_HASH = new ObjectPlanHash("Like-Operator-Value");
    private static final Map<String, String> REPLACE_MAP = ImmutableMap.<String, String>builder()
            .put("%", ".*")
            .put("_", ".")
            .put("|", "\\|")
            .put(".", "\\.")
            .put("^", "\\^")
            .put("$", "\\$")
            .put("\\", "\\\\")
            .put("*", "\\*")
            .put("+", "\\+")
            .put("?", "\\?")
            .put("[", "\\[")
            .put("]", "\\]")
            .put("{", "\\{")
            .put("}", "\\}")
            .put("(", "\\(")
            .put(")", "\\)")
            .build();

    @Nonnull
    private final Value patternChild;
    @Nonnull
    private final Value escapeChild;

    /**
     * Constructs a new instance of {@link PatternForLikeValue}.
     * @param patternChild the pattern
     * @param escapeChild the escape character
     */
    public PatternForLikeValue(@Nonnull final Value patternChild, @Nonnull final Value escapeChild) {
        this.patternChild = patternChild;
        this.escapeChild = escapeChild;
    }

    @Nullable
    @Override
    @SuppressWarnings("java:S6213")
    public <M extends Message> String eval(@Nullable final FDBRecordStoreBase<M> store, @Nonnull final EvaluationContext context) {
        String patternStr = (String)patternChild.eval(store, context);
        String escapeChar = (String)escapeChild.eval(store, context);
        if (patternStr == null) {
            return null;
        }
        Map<String, String> replaceMap;
        if (escapeChar == null) {
            replaceMap = REPLACE_MAP;
        } else {
            SemanticException.check(escapeChar.length() == 1, SemanticException.ErrorCode.ESCAPE_CHAR_OF_LIKE_OPERATOR_IS_NOT_SINGLE_CHAR);
            replaceMap = ImmutableMap.<String, String>builderWithExpectedSize(REPLACE_MAP.size() + 2)
                    .put(escapeChar + "_", "_")
                    .put(escapeChar + "%", "%")
                    .putAll(REPLACE_MAP)
                    .build();
        }
        return "^" + StringUtils.replaceEach(patternStr, replaceMap) + "$";
    }

    @Nonnull
    @Override
    protected Iterable<? extends Value> computeChildren() {
        return  ImmutableList.of(patternChild, escapeChild);
    }

    @Nonnull
    @Override
    public PatternForLikeValue withChildren(final Iterable<? extends Value> newChildren) {
        Verify.verify(Iterables.size(newChildren) == 2);
        return new PatternForLikeValue(
                Iterables.get(newChildren, 0),
                Iterables.get(newChildren, 1));
    }

    @Override
    public int hashCodeWithoutChildren() {
        return PlanHashable.objectsPlanHash(PlanHashable.CURRENT_FOR_CONTINUATION, BASE_HASH);
    }
    
    @Override
    public int planHash(@Nonnull final PlanHashMode mode) {
        return PlanHashable.objectsPlanHash(mode, BASE_HASH, patternChild, escapeChild);
    }

    @Nonnull
    @Override
    public ExplainTokensWithPrecedence explain(@Nonnull final Iterable<Supplier<ExplainTokensWithPrecedence>> explainSuppliers) {
        final var pattern = Iterables.get(explainSuppliers, 0).get();
        final var escape = Iterables.get(explainSuppliers, 1).get();

        return ExplainTokensWithPrecedence.of(Precedence.BETWEEN.parenthesizeChild(pattern).addWhitespace()
                .addKeyword("ESCAPE").addWhitespace().addNested(Precedence.BETWEEN.parenthesizeChild(escape)));
    }

    @Override
    public int hashCode() {
        return semanticHashCode();
    }

    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    @SpotBugsSuppressWarnings("EQ_UNUSUAL")
    @Override
    public boolean equals(final Object other) {
        return semanticEquals(other, AliasMap.emptyMap());
    }

    @Nonnull
    @Override
    public Type getResultType() {
        return Type.primitiveType(TypeCode.STRING);
    }

    @Nonnull
    @Override
    public PPatternForLikeValue toProto(@Nonnull final PlanSerializationContext serializationContext) {
        return PPatternForLikeValue.newBuilder()
                .setPatternChild(patternChild.toValueProto(serializationContext))
                .setEscapeChild(escapeChild.toValueProto(serializationContext))
                .build();
    }

    @Nonnull
    @Override
    public PValue toValueProto(@Nonnull final PlanSerializationContext serializationContext) {
        return PValue.newBuilder().setPatternForLikeValue(toProto(serializationContext)).build();
    }

    @Nonnull
    public static PatternForLikeValue fromProto(@Nonnull final PlanSerializationContext serializationContext,
                                                @Nonnull final PPatternForLikeValue patternForLikeValueProto) {
        return new PatternForLikeValue(Value.fromValueProto(serializationContext, Objects.requireNonNull(patternForLikeValueProto.getPatternChild())),
                Value.fromValueProto(serializationContext, Objects.requireNonNull(patternForLikeValueProto.getEscapeChild())));
    }

    @Nonnull
    private static Value encapsulate(@Nonnull final List<? extends Typed> arguments) {
        Verify.verify(arguments.size() == 2);
        Type patternType = arguments.get(0).getResultType();
        Type escapeType = arguments.get(0).getResultType();
        SemanticException.check(patternType.getTypeCode().equals(TypeCode.STRING), SemanticException.ErrorCode.OPERAND_OF_LIKE_OPERATOR_IS_NOT_STRING);
        SemanticException.check(escapeType.getTypeCode().equals(TypeCode.STRING), SemanticException.ErrorCode.OPERAND_OF_LIKE_OPERATOR_IS_NOT_STRING);

        return new PatternForLikeValue((Value) arguments.get(0), (Value) arguments.get(1));
    }

    /**
     * The {@code patternForLike} operator.
     */
    @AutoService(BuiltInFunction.class)
    public static class PatternForLikeFn extends BuiltInFunction<Value> {
        public PatternForLikeFn() {
            super("patternForLike",
                    ImmutableList.of(Type.primitiveType(TypeCode.STRING), Type.primitiveType(TypeCode.STRING)),
                    (ignored, args) -> PatternForLikeValue.encapsulate(args));
        }
    }

    /**
     * Deserializer.
     */
    @AutoService(PlanDeserializer.class)
    public static class Deserializer implements PlanDeserializer<PPatternForLikeValue, PatternForLikeValue> {
        @Nonnull
        @Override
        public Class<PPatternForLikeValue> getProtoMessageClass() {
            return PPatternForLikeValue.class;
        }

        @Nonnull
        @Override
        public PatternForLikeValue fromProto(@Nonnull final PlanSerializationContext serializationContext,
                                             @Nonnull final PPatternForLikeValue patternForLikeValueProto) {
            return PatternForLikeValue.fromProto(serializationContext, patternForLikeValueProto);
        }
    }
}
