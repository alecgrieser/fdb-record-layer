/*
 * TupleRangeTest.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2018 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record;

import com.apple.foundationdb.Range;
import com.apple.foundationdb.subspace.Subspace;
import com.apple.foundationdb.tuple.ByteArrayUtil;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.foundationdb.tuple.TupleHelpers;
import com.apple.foundationdb.tuple.Versionstamp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link TupleRange}.
 */
public class TupleRangeTest {
    private static final Tuple PREFIX_TUPLE = Tuple.from("prefix");
    private static final Subspace PREFIX_SUBSPACE = new Subspace(PREFIX_TUPLE);
    private static final byte[] PREFIX_BYTES = PREFIX_SUBSPACE.getKey();

    @SuppressWarnings("unused") // used as parameter source for parameterized test
    static Stream<Arguments> toRange() {
        final Tuple a = Tuple.from("a");
        final Tuple b = Tuple.from("b");
        return Stream.of(
                Arguments.of(TupleRange.allOf(null), new Range(new byte[0], new byte[]{(byte)0xff})),
                Arguments.of(TupleRange.allOf(PREFIX_TUPLE), Range.startsWith(PREFIX_BYTES)),

                Arguments.of(new TupleRange(a, b, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_EXCLUSIVE),
                        new Range(a.pack(), b.pack())),
                Arguments.of(new TupleRange(a, b, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE),
                        new Range(a.pack(), ByteArrayUtil.strinc(b.pack()))),
                Arguments.of(new TupleRange(a, b, EndpointType.RANGE_EXCLUSIVE, EndpointType.RANGE_EXCLUSIVE),
                        new Range(ByteArrayUtil.strinc(a.pack()), b.pack())),
                Arguments.of(new TupleRange(a, b, EndpointType.RANGE_EXCLUSIVE, EndpointType.RANGE_INCLUSIVE),
                        new Range(ByteArrayUtil.strinc(a.pack()), ByteArrayUtil.strinc(b.pack()))),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_EXCLUSIVE),
                        new Range(new byte[0], b.pack())),
                Arguments.of(new TupleRange(a, null, EndpointType.RANGE_INCLUSIVE, EndpointType.TREE_END),
                        new Range(a.pack(), new byte[]{(byte)0xff})),

                Arguments.of(new TupleRange(a, b, EndpointType.CONTINUATION, EndpointType.RANGE_EXCLUSIVE),
                        new Range(ByteArrayUtil.join(a.pack(), new byte[]{0x00}), b.pack())),
                Arguments.of(new TupleRange(a, b, EndpointType.RANGE_INCLUSIVE, EndpointType.CONTINUATION),
                        new Range(a.pack(), b.pack())),

                Arguments.of(TupleRange.prefixedBy("a"),
                        new Range(new byte[]{0x02, (byte)'a'}, new byte[]{0x02, (byte)'b'})),
                Arguments.of(new TupleRange(Tuple.from("apple"), a, EndpointType.CONTINUATION, EndpointType.PREFIX_STRING),
                        new Range(ByteArrayUtil.join(Tuple.from("apple").pack(), new byte[]{0x00}), new byte[]{0x02, (byte)'b'})),
                Arguments.of(new TupleRange(a, Tuple.from("apple"), EndpointType.PREFIX_STRING, EndpointType.CONTINUATION),
                        new Range(new byte[]{0x02, (byte)'a'}, Tuple.from("apple").pack()))

        );
    }

    @ParameterizedTest(name = "toRange[tupleRange={0}]")
    @MethodSource
    void toRange(TupleRange tupleRange, Range keyRange) {
        assertEquals(keyRange, tupleRange.toRange());
        TupleRange prefixedRange = tupleRange.prepend(PREFIX_TUPLE);
        assertEquals(prefixedRange.toRange(), tupleRange.toRange(PREFIX_SUBSPACE));
    }

    @SuppressWarnings("unused") // used as parameter source for parameterized test
    static Stream<TupleRange> illegalRanges() {
        final Tuple a = Tuple.from("a");
        final Tuple b = Tuple.from("b");
        return Stream.of(
                new TupleRange(a, b, EndpointType.RANGE_INCLUSIVE, EndpointType.TREE_START),
                new TupleRange(a, b, EndpointType.TREE_END, EndpointType.RANGE_EXCLUSIVE),
                new TupleRange(null, b, EndpointType.RANGE_EXCLUSIVE, EndpointType.RANGE_EXCLUSIVE),
                new TupleRange(a, null, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE),

                new TupleRange(null, a, EndpointType.PREFIX_STRING, EndpointType.PREFIX_STRING),
                new TupleRange(a, b, EndpointType.PREFIX_STRING, EndpointType.PREFIX_STRING),
                new TupleRange(a, a, EndpointType.RANGE_INCLUSIVE, EndpointType.PREFIX_STRING),
                new TupleRange(a, a, EndpointType.PREFIX_STRING, EndpointType.RANGE_INCLUSIVE),
                new TupleRange(a, b, EndpointType.CONTINUATION, EndpointType.PREFIX_STRING),
                new TupleRange(a, b, EndpointType.PREFIX_STRING, EndpointType.CONTINUATION),
                new TupleRange(Tuple.from(1066), Tuple.from(1066), EndpointType.PREFIX_STRING, EndpointType.PREFIX_STRING)
        );
    }

    @ParameterizedTest(name = "illegalRanges[tupleRange={0}]")
    @MethodSource
    void illegalRanges(TupleRange tupleRange) {
        assertThrows(RecordCoreException.class, tupleRange::toRange, () -> String.format("range %s should have thrown an error", tupleRange));
    }

    @SuppressWarnings("unused") // used as parameter source for parameterized test
    static Stream<Arguments> contains() {
        final Tuple a = Tuple.from("a");
        final Tuple b = Tuple.from("b");
        final Tuple c = Tuple.from("c");
        final Tuple d = Tuple.from("d");

        return Stream.of(
                // Range: <,>
                Arguments.of(TupleRange.ALL, TupleHelpers.EMPTY, true),
                Arguments.of(TupleRange.ALL, a, true),
                Arguments.of(TupleRange.ALL, a.add(1066L), true),
                Arguments.of(TupleRange.ALL, b, true),
                Arguments.of(TupleRange.ALL, c, true),

                // Range: <, b)
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_EXCLUSIVE), TupleHelpers.EMPTY, true),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_EXCLUSIVE), a, true),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_EXCLUSIVE), a.add(1066L), true),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_EXCLUSIVE), b, false),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_EXCLUSIVE), b.add(1066L), false),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_EXCLUSIVE), c, false),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_EXCLUSIVE), c.add(1066L), false),

                // Range: <, b]
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_INCLUSIVE), TupleHelpers.EMPTY, true),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_INCLUSIVE), a, true),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_INCLUSIVE), a.add(1066L), true),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_INCLUSIVE), b, true),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_INCLUSIVE), b.add(1066L), true),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_INCLUSIVE), c, false),
                Arguments.of(new TupleRange(null, b, EndpointType.TREE_START, EndpointType.RANGE_INCLUSIVE), c.add(1066L), false),

                // Range: (b, >
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_EXCLUSIVE, EndpointType.TREE_END), TupleHelpers.EMPTY, false),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_EXCLUSIVE, EndpointType.TREE_END), a, false),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_EXCLUSIVE, EndpointType.TREE_END), a.add(1066L), false),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_EXCLUSIVE, EndpointType.TREE_END), b, false),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_EXCLUSIVE, EndpointType.TREE_END), b.add(1066L), false),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_EXCLUSIVE, EndpointType.TREE_END), c, true),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_EXCLUSIVE, EndpointType.TREE_END), c.add(1066L), true),

                // Range: [b, >
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_INCLUSIVE, EndpointType.TREE_END), TupleHelpers.EMPTY, false),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_INCLUSIVE, EndpointType.TREE_END), a, false),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_INCLUSIVE, EndpointType.TREE_END), a.add(1066L), false),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_INCLUSIVE, EndpointType.TREE_END), b, true),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_INCLUSIVE, EndpointType.TREE_END), b.add(1066L), true),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_INCLUSIVE, EndpointType.TREE_END), c, true),
                Arguments.of(new TupleRange(b, null, EndpointType.RANGE_INCLUSIVE, EndpointType.TREE_END), c.add(1066L), true),

                // Range: [a, b)
                Arguments.of(TupleRange.between(a, b), TupleHelpers.EMPTY, false),
                Arguments.of(TupleRange.between(a, b), a, true),
                Arguments.of(TupleRange.between(a, b), a.add(1066L), true),
                Arguments.of(TupleRange.between(a, b), b, false),
                Arguments.of(TupleRange.between(a, b), b.add(1066L), false),

                // Range: (a, c)
                Arguments.of(new TupleRange(a, c, EndpointType.RANGE_EXCLUSIVE, EndpointType.RANGE_EXCLUSIVE), TupleHelpers.EMPTY, false),
                Arguments.of(new TupleRange(a, c, EndpointType.RANGE_EXCLUSIVE, EndpointType.RANGE_EXCLUSIVE), a, false),
                Arguments.of(new TupleRange(a, c, EndpointType.RANGE_EXCLUSIVE, EndpointType.RANGE_EXCLUSIVE), a.add(1066L), false),
                Arguments.of(new TupleRange(a, c, EndpointType.RANGE_EXCLUSIVE, EndpointType.RANGE_EXCLUSIVE), b, true),
                Arguments.of(new TupleRange(a, c, EndpointType.RANGE_EXCLUSIVE, EndpointType.RANGE_EXCLUSIVE), b.add(1415L), true),
                Arguments.of(new TupleRange(a, c, EndpointType.RANGE_EXCLUSIVE, EndpointType.RANGE_EXCLUSIVE), c, false),
                Arguments.of(new TupleRange(a, c, EndpointType.RANGE_EXCLUSIVE, EndpointType.RANGE_EXCLUSIVE), c.add("foo"), false),

                // Range: [b, b]
                Arguments.of(TupleRange.allOf(b), TupleHelpers.EMPTY, false),
                Arguments.of(TupleRange.allOf(b), a, false),
                Arguments.of(TupleRange.allOf(b), b, true),
                Arguments.of(TupleRange.allOf(b), c, false),

                // Range: [b, c)
                Arguments.of(TupleRange.between(b, c), TupleHelpers.EMPTY, false),
                Arguments.of(TupleRange.between(b, c), a, false),
                Arguments.of(TupleRange.between(b, c), a.add(1066L), false),
                Arguments.of(TupleRange.between(b, c), b, true),
                Arguments.of(TupleRange.between(b, c), b.add(1066L), true),
                Arguments.of(TupleRange.between(b, c), c, false),

                // Range: [b, c]
                Arguments.of(new TupleRange(b, c, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE), TupleHelpers.EMPTY, false),
                Arguments.of(new TupleRange(b, c, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE), a, false),
                Arguments.of(new TupleRange(b, c, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE), b, true),
                Arguments.of(new TupleRange(b, c, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE), b.add(1066L), true),
                Arguments.of(new TupleRange(b, c, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE), c, true),
                Arguments.of(new TupleRange(b, c, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE), c.add(1414L), true),
                Arguments.of(new TupleRange(b, c, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE), d, false),

                // Range: [(a, 1066L), (a, 1415L))
                Arguments.of(TupleRange.between(Tuple.from(1066L), Tuple.from(1415L)).prepend(a), TupleHelpers.EMPTY, false),
                Arguments.of(TupleRange.between(Tuple.from(1066L), Tuple.from(1415L)).prepend(a), a, false),
                Arguments.of(TupleRange.between(Tuple.from(1066L), Tuple.from(1415L)).prepend(a), a, false),
                Arguments.of(TupleRange.between(Tuple.from(1066L), Tuple.from(1415L)).prepend(a), a.add(1215L), true),
                Arguments.of(TupleRange.between(Tuple.from(1066L), Tuple.from(1415L)).prepend(a), a.add(1215L).add("foo"), true),
                Arguments.of(TupleRange.between(Tuple.from(1066L), Tuple.from(1415L)).prepend(a), a.add(1215L).add("foo").add(UUID.randomUUID()), true),
                Arguments.of(TupleRange.between(Tuple.from(1066L), Tuple.from(1415L)).prepend(a), a.add(1415L), false),
                Arguments.of(TupleRange.between(Tuple.from(1066L), Tuple.from(1415L)).prepend(a), a.add(1623L), false),
                Arguments.of(TupleRange.between(Tuple.from(1066L), Tuple.from(1415L)).prepend(a), b, false),
                Arguments.of(TupleRange.between(Tuple.from(1066L), Tuple.from(1415L)).prepend(a), b.add("foo"), false),
                Arguments.of(TupleRange.between(Tuple.from(1066L), Tuple.from(1415L)).prepend(a), c, false),

                // Prefix strings
                Arguments.of(TupleRange.prefixedBy("foo"), TupleHelpers.EMPTY, false),
                Arguments.of(TupleRange.prefixedBy("foo"), Tuple.from("food"), true),
                Arguments.of(TupleRange.prefixedBy("foo"), Tuple.from("food", 1066L), true),
                Arguments.of(TupleRange.prefixedBy("foo"), Tuple.from("fold"), false),
                Arguments.of(TupleRange.prefixedBy("foo"), Tuple.from(42L), false),
                Arguments.of(TupleRange.prefixedBy("foo").prepend(Tuple.from(42)), Tuple.from(42L), false),
                Arguments.of(TupleRange.prefixedBy("foo").prepend(Tuple.from(42)), Tuple.from(42L, "food"), true),
                Arguments.of(TupleRange.prefixedBy("foo").prepend(Tuple.from(42)), Tuple.from(42L, "food", 1066L), true),
                Arguments.of(TupleRange.prefixedBy("foo").prepend(Tuple.from(42)), Tuple.from(42L, "fold", 1066L), false),
                Arguments.of(TupleRange.prefixedBy("foo").prepend(Tuple.from(42)), Tuple.from(43L), false),
                Arguments.of(TupleRange.prefixedBy("foo").prepend(Tuple.from(42)), Tuple.from(43L, "food"), false),

                // Weird array types
                Arguments.of(new TupleRange(null, Tuple.from((Object) new byte[] { 0x0f, (byte)0xdb }), EndpointType.TREE_START, EndpointType.RANGE_INCLUSIVE), Tuple.from((Object)new byte[] { 0x0f, (byte)0xdb, 0x00 }), false)
        );
    }

    @ParameterizedTest(name = "contains[tupleRange={0}, tuple={1}]")
    @MethodSource
    void contains(TupleRange range, Tuple t, boolean expected) {
        assertEquals(expected, range.contains(t), () -> String.format("range %s should %scontain tuple %s", range, expected ? "" : "not ", t));

        // Make sure range comparison matches expectations from serializing to bytes
        assertContainsBytes(range, t);
    }

    static TupleRange randomRange(Random r) {
        return  new TupleRange(null, Tuple.from(TupleHelpers.EMPTY), EndpointType.TREE_START, EndpointType.RANGE_INCLUSIVE);
        /*
        if (r.nextDouble() < 0.05) {
            Tuple prefixTuple = randomTuple(r);
            return TupleRange.prefixedBy(randomString(r)).prepend(prefixTuple);
        }

        Tuple endpoint1 = randomTuple(r);
        Tuple endpoint2 = randomTuple(r);
        int comparison = endpoint1.compareTo(endpoint2);
        Tuple low = comparison <= 0 ? endpoint1 : endpoint2;
        Tuple high = comparison > 0 ? endpoint1 : endpoint2;

        EndpointType lowEndpoint;
        double lowEChoice = r.nextDouble();
        if (low.isEmpty() || lowEChoice < 0.04) {
            low = null;
            lowEndpoint = EndpointType.TREE_START;
        } else if (lowEChoice < 0.52) {
            lowEndpoint = EndpointType.RANGE_INCLUSIVE;
        } else {
            lowEndpoint = EndpointType.RANGE_EXCLUSIVE;
        }

        EndpointType highEndpoint;
        double highEChoice = r.nextDouble();
        if (high.isEmpty() || highEChoice < 0.04) {
            high = null;
            highEndpoint = EndpointType.TREE_END;
        } else if (highEChoice < 0.52) {
            highEndpoint = EndpointType.RANGE_INCLUSIVE;
        } else {
            highEndpoint = EndpointType.RANGE_EXCLUSIVE;
        }

        TupleRange tupleRange = new TupleRange(low, high, lowEndpoint, highEndpoint);
        if (r.nextDouble() < 0.25) {
            Tuple prefix = randomTuple(r);
            tupleRange = tupleRange.prepend(prefix);
        }
        return tupleRange;
         */
    }

    static String randomString(Random r) {
        int charCount = (int) Math.abs(r.nextGaussian() * 10);
        char[] chars = new char[charCount];
        for (int c = 0; c < charCount; c++) {
            chars[c] = (char)('a' + (r.nextInt(26)));
        }
        return new String(chars);
    }

    static Tuple randomTuple(Random r) {
        int elemCount = (int) Math.abs(r.nextGaussian() * 3);
        if (elemCount == 0) {
            return TupleHelpers.EMPTY;
        }
        List<Object> elements = new ArrayList<>(elemCount);
        for (int i = 0; i < elemCount; i++) {
            double choice = r.nextDouble();
            Object elem;
            if (choice < 0.1) {
                elem = null;
            } else if (choice < 0.2) {
                int byteCount = (int) Math.abs(r.nextGaussian() * 10);
                byte[] arr = new byte[byteCount];
                r.nextBytes(arr);
                elem = arr;
            }  else if (choice < 0.3) {
                elem = randomString(r);
            } else if (choice < 0.4) {
                elem = r.nextLong();
            } else if (choice < 0.5) {
                elem = r.nextDouble();
            } else if (choice < 0.6) {
                elem = r.nextFloat();
            } else if (choice < 0.7) {
                elem = r.nextBoolean();
            } else if (choice < 0.8) {
                elem = UUID.randomUUID();
            } else if (choice < 0.9) {
                byte[] arr = new byte[Versionstamp.LENGTH];
                r.nextBytes(arr);
                elem = Versionstamp.fromBytes(arr);
            } else {
                elem = randomTuple(r);
            }
            elements.add(elem);
        }
        return Tuple.fromList(elements);
    }

    @SuppressWarnings("unused") // used as parameter source for parameterized test
    static Stream<Arguments> containsRandom() {
        Random r = new Random();
        return Stream.generate(() -> randomRange(r))
                .flatMap(range -> Stream.generate(() -> Arguments.of(range, randomTuple(r))).limit(1))
                .limit(500);
    }

    @ParameterizedTest(name = "containsRandom[tupleRange={0}, tuple={1}]")
    @MethodSource
    void containsRandom(TupleRange range, Tuple t) {
        try {
            range.toRange();
        } catch (IllegalArgumentException e) {
            System.out.println("foo");
        }
        TupleRange betterRange = new TupleRange(null, Tuple.from(TupleHelpers.EMPTY), EndpointType.TREE_START, EndpointType.RANGE_INCLUSIVE);
        assertContainsBytes(betterRange, t);
    }

    private static void assertContainsBytes(TupleRange range, Tuple t) {
        Range keyRange = range.toRange();
        byte[] tupleBytes = t.pack();
        boolean inRange = ByteArrayUtil.compareUnsigned(keyRange.begin, tupleBytes) <= 0 && ByteArrayUtil.compareUnsigned(keyRange.end, tupleBytes) > 0;
        if (inRange != range.contains(t)) {
            System.out.println("gasp!");
        }
        assertEquals(inRange, range.contains(t), () -> String.format("byte array comparison for range %s and tuple %s does not match expected comparison", range, t));
    }
}
