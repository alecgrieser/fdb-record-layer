/*
 * package-info.java
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

/**
 * Large Scale, Autogenerated Testing for Relational.
 *
 * The system in this package (along with companions in the {@code com.apple.foundationdb.relational.compare} package) can be
 * used to create tests that scale to large numbers of tests automatically, so that a large space of values
 * can be explored to determine correctness.
 *
 * Version 1 (May 2022)
 * This version is a comparison-only test, where multiple different Connections can be made, controlling
 * the specific behavior of an operation; the testing system then creates tests automatically based on configuration
 * and runs the same queries against every connection; the test will fail if the results of any connection do not
 * match the results of all the other connections.
 *
 * To Use:
 *
 * 1. Create a test file. This can be any ol' Java class file.
 * 2. Annotate the test class with {@link com.apple.foundationdb.relational.autotest.AutomatedTest}. This indicates that the
 * test is not a standard JUnit test, but should be run using the AutoTest framework
 * 3. Create one or more  {@link com.apple.foundationdb.relational.autotest.Connector} instances. Each Connector is responsible
 * for creating new connections to different systems, and returning a {@link com.apple.foundationdb.relational.api.RelationalConnection}
 * on-demand. Only Connectors which are annotated by the {@link com.apple.foundationdb.relational.autotest.Connection} annotation
 *  will be used by the test engine. These can be defined by a method or an object instance, whichever is your preference
 *  in the testing situation.
 * 4. Create one or more {@link com.apple.foundationdb.relational.autotest.WorkloadConfig} instance and annotate it with a
 * {@link com.apple.foundationdb.relational.autotest.WorkloadConfiguration} annotation. This configuration object is used to control
 * different aspects of the test engine, but can also be repurposed to allow specific configuration of test queries.
 * 5. Create one or more {@link com.apple.foundationdb.relational.autotest.SchemaDescription} instances, either as a field
 * or as a method which returns either {@code SchemaDescription} or {@code Stream<SchemaDescription>}. Annotate
 * this field or method with the {@link com.apple.foundationdb.relational.autotest.Schema} annotation. These objects define
 * how the testing engine creates and manages schemas.
 * 6. Create one or more fields or methods annotated with the {@link com.apple.foundationdb.relational.autotest.Data} annotation.
 * The field (or the method return object) must be of type {@link com.apple.foundationdb.relational.autotest.DataSet},
 * {@code Collection<DataSet>} or {@code Stream<DataSet>}. This defines
 * how data is to be loaded into the schemas which are given.
 * 7. Create one or more fields or methods annotation with the {@link com.apple.foundationdb.relational.autotest.Query} annotation.
 * The field (or method return object) must be of type {@link com.apple.foundationdb.relational.autotest.ParameterizedQuery},
 * {@code Collection<ParameterizedQuery}, or {@code Stream<ParameterizedQuery}. These instances define the specific
 * queries which must be run by the test.
 * 8. IDEA should automatically pick up that this is a test, but when asked, make sure you use the {@code autoTest}
 * test engine and not the standard Junit test engine (running it with the standard JUnit test engine will just
 * blow up with a "No Tests defined" error).
 * 9. To run it with gradle, use `gradle autoTest`
 *
 *
 * Useful tools:
 * <ul>
 *     <li>
 *         {@link com.apple.foundationdb.relational.autotest.datagen.SchemaGenerator} can be used to randomly create Schemas
 *     </li>
 *     <li>
 *         {@link com.apple.foundationdb.relational.autotest.datagen.RandomDataSet} can be used to create random data sets within
 *         schema descriptions
 *     </li>
 * </ul>
 *
 */
package com.apple.foundationdb.relational.autotest;
