/*
 * IndexValidatorRegistry.java
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

package com.apple.foundationdb.record.metadata;

import com.apple.foundationdb.annotation.API;

import javax.annotation.Nonnull;

/**
 * A registry of {@link IndexValidator}s.
 * @see com.apple.foundationdb.record.provider.foundationdb.IndexMaintainerFactory
 */
@API(API.Status.UNSTABLE)
public interface IndexValidatorRegistry {
    @Nonnull
    IndexValidator getIndexValidator(@Nonnull Index index);
}
