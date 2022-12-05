# Storage Format

This page is designed to give an overview of how the Record Layer organizes and serializes data. For the
most part, this information should be relatively transparent to the user, though awareness of the format
may be necessary to get the most performance out of the system. This is also intended to be a reference
to those wishing to make changes to the system.

The Record Layer stores all of its data within [FoundationDB](https://www.foundationdb.org). An FDB
cluster exposes a data model that consists of a single keyspace of lexicographically-ordered unsigned
byte array keys that map to byte array values. Because the keyspace is ordered, related data can be
grouped by assigning the keys for that data a common prefix. For more information, see the
[FDB Developer Guide](https://apple.github.io/foundationdb/developer-guide.html).

As detailed in the [Overview](Overview.md), the Record Layer places all data in "record stores" which is
akin to a logical "database" as defined by a relational database management system, and each Record
Store has a "record meta-data" that defines what record types and indexes exist. This means that the
two problems that the storage format need to solve are: (1) how are record stores stored so that multiple
record stores can be stored on the same FDB cluster and (2) how are data serialized within each record store?
Each problem will be handled in turn.

The Record Layer makes heavy use of the FDB Tuple layer when serializing data. For more information on
that format, see the
[FDB Tuple format documentation](https://github.com/apple/foundationdb/blob/main/design/tuple.md).

## Subspaces and Key Space Paths

To facilitate storing multiple record stores on the same cluster, every record store is assigned a
"[subspace](https://apple.github.io/foundationdb/developer-guide.html#developer-guide-sub-keyspaces)".
Each record store must have assigned a unique subspace not overlapping with any other store. Every
key used by the record store is then prefixed by the subspace's byte array key, which will typically be some
serialized tuple.

In principle, users are allowed to assign stores to subspaces with any scheme they'd like, but we encourage
users to use the [`KeySpacePath`](https://javadoc.io/doc/org.foundationdb/fdb-record-layer-core-pb3/latest/com/apple/foundationdb/record/provider/foundationdb/keyspace/KeySpacePath.html)
abstraction. This abstraction allows subspaces to be constructed with named elements that can have semantic
meaning to operator.

To set up a `KeySpacePath`, the user should construct a
[`KeySpace`](https://javadoc.io/doc/org.foundationdb/fdb-record-layer-core-pb3/latest/com/apple/foundationdb/record/provider/foundationdb/keyspace/KeySpace.html)
containing a structure of nested
[`KeySpaceDirectories`](https://javadoc.io/doc/org.foundationdb/fdb-record-layer-core-pb3/latest/com/apple/foundationdb/record/provider/foundationdb/keyspace/KeySpaceDirectory.html).
Each `KeySpaceDirectory` consists of a name, a type, and (optionally) a constant value. The name of the
directory is not serialized into the database, and it serves only as an aid for the user. Each directory
will be handed a value to construct the `KeySpacePath`. That value will then be encoded as `Tuple` element
and concatenated with the other values in the path, and then serialized to bytes. By default, each
directory can be given multiple different values, and so uniqueness of the path is guaranteed by making
sure no two subdirectories of a single directory have the same type. If the constant value is provided,
then only that constant value may be given to the directory. Multiple subdirectories of the same type
_are_ allowed if they are all constant values (with different constants).

There is one special subclass of `KeySpaceDirectory`, the
[`DirectoryLayerDirectory`](https://javadoc.io/doc/org.foundationdb/fdb-record-layer-core-pb3/latest/com/apple/foundationdb/record/provider/foundationdb/keyspace/DirectoryLayerDirectory.html).
That directory takes string values, but instead of directly encoding the string into the resulting `Tuple`, it will
first use the [FDB directory layer](https://apple.github.io/foundationdb/developer-guide.html#developer-guide-directories)
to intern that string as an integer. This is more space efficient than a string, as
the tuple layer uses a variable length encoding scheme that means that small integers can typically be encoded
using a small number of bytes. Note that this does require performing an additional database read to resolve
the key space path to bytes, though the value is cached in a "directory cache" managed by the database object.

As an example of how key spaces might work, consider this key space:

```java
KeySpace keySpace = new KeySpace(
        new KeySpaceDirectory("env", KeyType.LONG)
            .addSubdirectory(new DirectoryLayerDirectory("application")
                .addSubdirectory(new KeySpaceDirectory("main_data", KeyType.STRING, "m"))
                .addSubdirectory(new KeySpaceDirectory("secondary_data", KeyType.STRING, "s"))
             )
            .addSubdirectory(new KeySpaceDirectory("bookkeeping", KeyType.NULL))
        );
```

Then, to construct a path, the user might do something like:

```java
KeySpacePath path = keySpace.path("env", 0L)
        .path("application", "my_application")
        .path("main_data");
```

This is then resolved as a keyspace by constructing a tuple of:

1. From the "env" directory, the value `0L`.
1. Looking up "my_application" in the directory layer for that cluster. This will be some integer and will depend
   on data in the cluster. For this example, let's suppose its `1066L`.
1. Using the constant value `"m"` for the "main_data" directory.

So we get the tuple `(0L, 1066L, "m")`, which is then encoded into bytes (as per the
[tuple spec](https://github.com/apple/foundationdb/blob/main/design/tuple.md)) as `\x14\x16\x04\x2a\x02m\x00`. So
all data for that record store will be prefixed by that 7 byte prefix.

## Record Store Data

Within a record store, data are further organized into further subdivisions. These are listed by the
[`FDBRecordStoreKeyspace`](https://javadoc.io/doc/org.foundationdb/fdb-record-layer-core-pb3/latest/com/apple/foundationdb/record/provider/foundationdb/FDBRecordStoreKeyspace.html)
enum. For each one, the record store will suffix the store's subspace with the value in the enum to access the
data in question. The most important of those keyspaces are:

 * `0L`, used for [the store's header](#the-store-header) (a single key)
 * `1L`, used for [records](#records) by primary key
 * `2L`, used for secondary [indexes](#indexes)
 * `5L`, used for storing [index state](#index-states) information

More information on those keyspaces is below.

### The Store Header

The store header is a single key-value pair that should be the first key in every record store. In it, information
is stored that is critical for correctly interpreting or manipulating the data in the store, and for correctness,
it is important that the header is read transactionally with the rest of the data in the store. The store header
contains:

 * The meta-data version. See [Schema Evolution](SchemaEvolution.md) for more details, but each time the meta-data
   for the store is modified, its meta-data should be given a new version that is strictly greater than the last one.
   When a store is opened, the meta-data version in the header is then compared to the version in the meta-data object.
   If they match, the store knows the meta-data is up-to-date, and it can continue with the store as-is. If the
   meta-data in the store is older (that is, smaller), then it will update the store's meta-data (by, for example,
   clearing out any deleted indexes or marking any new indexes as needing to be built) and write a new version of
   the header with the new version. If the meta-data in the store is newer, then the store knows its meta-data
   object is out of date, and it throws a
   [`RecordStoreStaleMetaDataVersionException`](https://javadoc.io/doc/org.foundationdb/fdb-record-layer-core-pb3/latest/com/apple/foundationdb/record/provider/foundationdb/RecordStoreStaleMetaDataVersionException.html).
   The user can then reason about refreshing its meta-data by catching that exception and reloading the meta-data
   if possible.
 * The user version. This version is not used by the Record Layer directly, but it is stored, maintained, and checked
   when the store is opened, and it is available for users who may want to use it to prevent accidentally reverting
   some durable change to the way data are stored. To use this field, the user should implement the
   [`UserVersionChecker`](https://javadoc.io/doc/org.foundationdb/fdb-record-layer-core-pb3/latest/com/apple/foundationdb/record/provider/foundationdb/FDBRecordStoreBase.UserVersionChecker.html)
   interface and supply it to the store (see: [`setUserVersionChecker()`](https://javadoc.io/doc/org.foundationdb/fdb-record-layer-core-pb3/latest/com/apple/foundationdb/record/provider/foundationdb/FDBRecordStoreBase.BaseBuilder.html)).
 * The format version. This version can be used to control things around how data are formatted to the store. For
   example, if one version of the Record Layer moves data from one internal keyspace to another, it can control that
   via the format version. When the store header is checked, if the store does not understand the current format
   version in the header, it will throw an
   [`UnsupportedFormatVersionException`](https://javadoc.io/doc/org.foundationdb/fdb-record-layer-core-pb3/latest/com/apple/foundationdb/record/provider/foundationdb/UnsupportedFormatVersionException.html).
   The store can also be configured with a default format version to use, and it will upgrade any record store on
   an older format version to that new version when opening. Note that it is important to wait to update that value
   until all Record Layer instances that may interact with a record store have been updated to a version that can
   understand that version.
 * Whether the store header is cacheable. Note that because the store header has to be read every time a store is
   opened, particularly busy stores can suffer performance problems from too many reads to this key. To speed
   up store opening times and to alleviate hot key problems, the user can enable store state caching, which
   caches the store header and index states. However, for correctness reasons, it is important that the cached
   header is not stale, so for cacheable headers, any time the header is updated, a global "cache invalidation key"
   must also be updated. (This special key can be read cheaply by the FDB client without causing hot shard problems.)
   See [`MetaDataVersionStampStoreStateCache`](https://javadoc.io/doc/org.foundationdb/fdb-record-layer-core-pb3/latest/com/apple/foundationdb/record/provider/foundationdb/storestate/MetaDataVersionStampStoreStateCache.html)
   for more details.
 * A map from string to bytes that constitute the "user fields" for the store header. These can be used for arbitrary
   fields that the user may want to associate with the store. These will then be read with the store header, so for
   critical data, the user can save round trips to the database when compared with storing the data in a separate
   location. However, this data should not be updated too often, as every update to the store header will cause
   conflicts with all other concurrent operations on the record store.
   
The storage for the store header is fairly straightforward. The data listed above (along with some other internal
bookkeeping information like the store's creation time) are packing into a `DataStoreInfo` protobuf message, and
then stored as the value of a single FDB key. This key is stored at subspace `0L`. So, for the example
store in the first section at subspace `(0L, 1066L, "m")`, the store header would be at a key with tuple
deserialization `(0L, 1066L, "m", 0L)` or `\x14\x16\x04\x2a\x02m\x00\x14`.

### Records

Record data is stored within the "records subspace", which (as is stated [above](#record-store-data)) is
constructed by suffixing the record store subspace with the Tuple-encoded value `1L`. For each record, the
meta-data defines the record's primary key, which can be evaluated against a record to produce a `Tuple`. The
primary key is then concatenated with the 

### Indexes

### Index States