/*
 * RecordLayerSchema.java
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

package com.apple.foundationdb.relational.recordlayer;

import com.apple.foundationdb.annotation.API;

import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.relational.api.ConnectionScoped;
import com.apple.foundationdb.relational.api.catalog.DatabaseSchema;
import com.apple.foundationdb.relational.api.exceptions.ErrorCode;
import com.apple.foundationdb.relational.api.exceptions.RelationalException;
import com.apple.foundationdb.relational.recordlayer.storage.BackingStore;
import com.apple.foundationdb.relational.util.Assert;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.HashMap;
import java.util.Map;

@NotThreadSafe
@ConnectionScoped
@API(API.Status.EXPERIMENTAL)
public class RecordLayerSchema implements DatabaseSchema {
    private final AbstractDatabase db;
    //could be accessed through the database, but this seems convenient
    final EmbeddedRelationalConnection conn;
    @Nonnull
    private final String schemaName;

    private final FDBRecordStoreBase.StoreExistenceCheck existenceCheck;

    //TODO(bfines) destroy this when the connection's transaction ends
    private BackingStore currentStore;

    /*
     * Used for reference tracking to make sure that we close all the tables that we open.
     */
    private final Map<String, RecordTypeTable> loadedTables = new HashMap<>();

    public RecordLayerSchema(@Nonnull String schemaName, AbstractDatabase database, EmbeddedRelationalConnection connection) throws RelationalException {
        this.schemaName = schemaName;
        this.db = database;
        this.conn = connection;
        this.existenceCheck = FDBRecordStoreBase.StoreExistenceCheck.ERROR_IF_NOT_EXISTS;
    }

    @Nonnull
    @Override
    public String getSchemaName() {
        return schemaName;
    }

    @SuppressWarnings("PMD.CloseResource") // false positive as resource not closed is null
    @Nonnull
    public Table loadTable(@Nonnull String tableName) throws RelationalException {
        //TODO(bfines) load the record type index, rather than just the generic type, then
        // return an index object instead
        RecordTypeTable t = loadedTables.get(tableName);
        boolean putBack = false;
        if (t == null) {
            t = new RecordTypeTable(this, tableName);
            putBack = true;
        }
        if (putBack) {
            loadedTables.put(tableName, t);
        }
        return t;
    }

    @SuppressWarnings("PMD.CloseResource") // appears to be a false positive, as we are closing the resources
    @Override
    public void close() throws RelationalException {
        currentStore = null;
        for (RecordTypeTable table : loadedTables.values()) {
            table.close();
        }
        loadedTables.clear();
    }

    @Nonnull
    public BackingStore loadStore() throws RelationalException {
        // loadStore() expects an active transaction which should be taken care by the caller.
        Assert.thatUnchecked(conn.inActiveTransaction(), ErrorCode.INTERNAL_ERROR, "No active transaction!");
        if (currentStore != null) {
            return currentStore;
        }
        currentStore = db.loadRecordStore(schemaName, existenceCheck);
        conn.addCloseListener(() -> currentStore = null);
        return currentStore;
    }
}
