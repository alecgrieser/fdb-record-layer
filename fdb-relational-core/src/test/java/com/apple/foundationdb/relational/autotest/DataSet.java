/*
 * DataSet.java
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

package com.apple.foundationdb.relational.autotest;

import com.apple.foundationdb.relational.api.RelationalStruct;

import javax.annotation.Nonnull;
import java.sql.SQLException;
import java.util.stream.Stream;

public interface DataSet {

    /**
     * Get the data set, as a stream of records.
     *
     * Note that this method is <em>not</em> thread-safe. The Autotesting system won't(/shouldn't)
     * be calling this from multiple threads, so it's not a big deal, but if you want to, make sure you
     * have external synchronization.
     *
     * @param tableDescription the {@link TableDescription} to use.
     * @return a stream of messages built by the messageBuilder.
     */
    Stream<RelationalStruct> getData(@Nonnull TableDescription tableDescription) throws SQLException;
}