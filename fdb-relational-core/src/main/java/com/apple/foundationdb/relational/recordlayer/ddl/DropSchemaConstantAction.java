/*
 * DropSchemaConstantAction.java
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

package com.apple.foundationdb.relational.recordlayer.ddl;

import com.apple.foundationdb.annotation.API;

import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStore;
import com.apple.foundationdb.record.provider.foundationdb.keyspace.KeySpace;
import com.apple.foundationdb.relational.api.Transaction;
import com.apple.foundationdb.relational.api.catalog.StoreCatalog;
import com.apple.foundationdb.relational.api.ddl.ConstantAction;
import com.apple.foundationdb.relational.api.exceptions.ErrorCode;
import com.apple.foundationdb.relational.api.exceptions.RelationalException;
import com.apple.foundationdb.relational.recordlayer.RelationalKeyspaceProvider;
import com.apple.foundationdb.relational.recordlayer.util.ExceptionUtil;

import java.net.URI;

@API(API.Status.EXPERIMENTAL)
public class DropSchemaConstantAction implements ConstantAction {
    private final URI dbUri;
    private final String schemaName;
    private final KeySpace keySpace;
    private final StoreCatalog catalog;

    public DropSchemaConstantAction(URI dbUri,
                                    String schemaName,
                                    KeySpace keySpace,
                                    StoreCatalog storeCatalog) {
        this.dbUri = dbUri;
        this.schemaName = schemaName;
        this.catalog = storeCatalog;
        this.keySpace = keySpace;
    }

    @SuppressWarnings("PMD.CloseResource") // context lifetime managed by transaction
    @Override
    public void execute(Transaction txn) throws RelationalException {
        if ("/__SYS".equals(dbUri.getPath())) {
            throw new RelationalException("Cannot drop /__SYS schemas", ErrorCode.INSUFFICIENT_PRIVILEGE);
        }
        FDBRecordContext ctx = txn.unwrap(FDBRecordContext.class);
        try {
            FDBRecordStore.deleteStore(ctx, RelationalKeyspaceProvider.toDatabasePath(dbUri, keySpace).schemaPath(schemaName));
        } catch (RecordCoreException ex) {
            throw ExceptionUtil.toRelationalException(ex);
        }
        catalog.deleteSchema(txn, dbUri, schemaName);
    }
}
