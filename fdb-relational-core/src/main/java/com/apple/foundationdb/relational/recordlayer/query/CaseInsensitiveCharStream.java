/*
 * CaseInsensitiveCharStream.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2021-2024 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.relational.recordlayer.query;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.misc.Interval;

import javax.annotation.Nonnull;

/**
 * A {@link IntStream} stream, that enables allows a case-sensitive {@link org.antlr.v4.runtime.Lexer}
 * to become case-insensitive by upper-casing the stream of symbols arriving to it.
 */
public class CaseInsensitiveCharStream implements CharStream {

    /** Character stream. */
    private final CharStream underlying;

    public CaseInsensitiveCharStream(@Nonnull final String sql) {
        this.underlying = CharStreams.fromString(sql);
    }

    @Override
    public int LA(int i) {
        int c = underlying.LA(i);
        if (c <= 0) {
            return c;
        }
        // case-insensitive
        return Character.toUpperCase(c);
    }

    @Override
    public String getText(Interval interval) {
        return underlying.getText(interval);
    }

    @Override
    public String toString() {
        return underlying.toString();
    }

    @Override
    public void consume() {
        underlying.consume();
    }

    @Override
    public int mark() {
        return underlying.mark();
    }

    @Override
    public void release(int marker) {
        underlying.release(marker);
    }

    @Override
    public int index() {
        return underlying.index();
    }

    @Override
    public void seek(int index) {
        underlying.seek(index);
    }

    @Override
    public int size() {
        return underlying.size();
    }

    @Override
    public String getSourceName() {
        return underlying.getSourceName();
    }
}