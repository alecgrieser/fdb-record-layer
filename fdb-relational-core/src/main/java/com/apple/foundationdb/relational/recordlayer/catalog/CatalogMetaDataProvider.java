/*
 * CatalogMetaDataProvider.java
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

package com.apple.foundationdb.relational.recordlayer.catalog;

import com.apple.foundationdb.annotation.API;

import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.RecordMetaDataProto;
import com.apple.foundationdb.record.RecordMetaDataProvider;
import com.apple.foundationdb.relational.api.Transaction;
import com.apple.foundationdb.relational.api.catalog.StoreCatalog;
import com.apple.foundationdb.relational.api.exceptions.RelationalException;
import com.apple.foundationdb.relational.recordlayer.metadata.RecordLayerSchema;
import com.apple.foundationdb.relational.recordlayer.metadata.RecordLayerSchemaTemplate;
import com.apple.foundationdb.relational.util.Assert;

import javax.annotation.Nonnull;
import java.net.URI;

@API(API.Status.EXPERIMENTAL)
public class CatalogMetaDataProvider implements RecordMetaDataProvider {
    //TODO(bfines) there should probably be a cleaner way to deal with this

    private final StoreCatalog storeCatalog;
    private final URI dbUri;
    private final String schemaName;
    private final Transaction txn;

    private volatile RecordMetaData cachedMetaData;

    public CatalogMetaDataProvider(@Nonnull StoreCatalog storeCatalog,
                                   @Nonnull URI dbUri,
                                   @Nonnull String schemaName,
                                   @Nonnull Transaction txn) {
        this.storeCatalog = storeCatalog;
        this.dbUri = dbUri;
        this.schemaName = schemaName;
        this.txn = txn;
    }

    @Nonnull
    @Override
    public RecordMetaData getRecordMetaData() {
        RecordMetaData metaData = cachedMetaData;
        if (metaData == null) {
            try {
                final var recLayerSchema = storeCatalog.loadSchema(txn, dbUri, schemaName);
                Assert.thatUnchecked(recLayerSchema instanceof RecordLayerSchema);
                final RecordMetaDataProto.MetaData schema = recLayerSchema.getSchemaTemplate().unwrap(RecordLayerSchemaTemplate.class).toRecordMetadata().toProto();
                metaData = RecordMetaData.build(schema);
                cachedMetaData = metaData;
            } catch (RelationalException e) {
                //TODO(bfines): vomit, but I think this is probably the correct type, because RecordLayer is
                // the thing that's doing the processing here
                throw new RecordCoreException(e);
            }
        }
        return metaData;
    }

}
