//
// Copyright (c) 2016-2025 Deephaven Data Labs and Patent Pending
//
package io.deephaven.parquet.table;

import io.deephaven.UncheckedDeephavenException;
import io.deephaven.api.ColumnName;
import io.deephaven.api.Selectable;
import io.deephaven.api.SortColumn;
import io.deephaven.base.FileUtils;
import io.deephaven.base.verify.Assert;
import io.deephaven.engine.context.ExecutionContext;
import io.deephaven.engine.liveness.LivenessScopeStack;
import io.deephaven.engine.primitive.function.ByteConsumer;
import io.deephaven.engine.primitive.function.CharConsumer;
import io.deephaven.engine.primitive.function.FloatConsumer;
import io.deephaven.engine.primitive.function.ShortConsumer;
import io.deephaven.engine.primitive.iterator.CloseableIterator;
import io.deephaven.engine.primitive.iterator.CloseablePrimitiveIteratorOfByte;
import io.deephaven.engine.primitive.iterator.CloseablePrimitiveIteratorOfChar;
import io.deephaven.engine.primitive.iterator.CloseablePrimitiveIteratorOfDouble;
import io.deephaven.engine.primitive.iterator.CloseablePrimitiveIteratorOfFloat;
import io.deephaven.engine.primitive.iterator.CloseablePrimitiveIteratorOfInt;
import io.deephaven.engine.primitive.iterator.CloseablePrimitiveIteratorOfLong;
import io.deephaven.engine.primitive.iterator.CloseablePrimitiveIteratorOfShort;
import io.deephaven.engine.rowset.impl.TrackingWritableRowSetImpl;
import io.deephaven.engine.table.ColumnDefinition;
import io.deephaven.engine.table.ColumnSource;
import io.deephaven.engine.table.PartitionedTable;
import io.deephaven.engine.table.PartitionedTableFactory;
import io.deephaven.engine.table.impl.SourceTable;
import io.deephaven.engine.rowset.RowSet;
import io.deephaven.engine.table.*;
import io.deephaven.engine.table.impl.QueryTable;
import io.deephaven.engine.table.impl.dataindex.DataIndexUtils;
import io.deephaven.engine.table.impl.indexer.DataIndexer;
import io.deephaven.engine.table.impl.locations.ColumnLocation;
import io.deephaven.engine.table.impl.locations.impl.StandaloneTableKey;
import io.deephaven.engine.table.impl.select.FormulaEvaluationException;
import io.deephaven.engine.table.impl.select.FunctionalColumn;
import io.deephaven.engine.table.impl.select.SelectColumn;
import io.deephaven.engine.table.impl.sources.ReinterpretUtils;
import io.deephaven.engine.table.impl.util.ColumnHolder;
import io.deephaven.engine.table.iterators.*;
import io.deephaven.engine.testutil.ControlledUpdateGraph;
import io.deephaven.engine.testutil.junit4.EngineCleanup;
import io.deephaven.engine.util.BigDecimalUtils;
import io.deephaven.engine.util.TableTools;
import io.deephaven.engine.util.file.TrackedFileHandleFactory;
import io.deephaven.parquet.base.BigDecimalParquetBytesCodec;
import io.deephaven.parquet.base.BigIntegerParquetBytesCodec;
import io.deephaven.parquet.base.InvalidParquetFileException;
import io.deephaven.parquet.base.NullStatistics;
import io.deephaven.parquet.base.materializers.ParquetMaterializerUtils;
import io.deephaven.parquet.table.location.ParquetTableLocation;
import io.deephaven.parquet.table.location.ParquetTableLocationKey;
import io.deephaven.parquet.table.pagestore.ColumnChunkPageStore;
import io.deephaven.parquet.table.transfer.StringDictionary;
import io.deephaven.qst.type.Type;
import io.deephaven.stringset.ArrayStringSet;
import io.deephaven.stringset.StringSet;
import io.deephaven.test.types.OutOfBandTest;
import io.deephaven.time.DateTimeUtils;
import io.deephaven.util.QueryConstants;
import io.deephaven.util.SafeCloseable;
import io.deephaven.util.codec.SimpleByteArrayCodec;
import io.deephaven.util.compare.DoubleComparisons;
import io.deephaven.util.compare.FloatComparisons;
import io.deephaven.util.mutable.MutableInt;
import io.deephaven.util.mutable.MutableLong;
import io.deephaven.vector.Vector;
import io.deephaven.vector.*;
import junit.framework.TestCase;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.apache.commons.lang3.mutable.MutableFloat;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Types;
import org.junit.*;
import org.junit.experimental.categories.Category;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import static io.deephaven.base.FileUtils.convertToURI;
import static io.deephaven.engine.testutil.TstUtils.assertTableEquals;
import static io.deephaven.engine.util.TableTools.booleanCol;
import static io.deephaven.engine.util.TableTools.byteCol;
import static io.deephaven.engine.util.TableTools.charCol;
import static io.deephaven.engine.util.TableTools.doubleCol;
import static io.deephaven.engine.util.TableTools.emptyTable;
import static io.deephaven.engine.util.TableTools.floatCol;
import static io.deephaven.engine.util.TableTools.instantCol;
import static io.deephaven.engine.util.TableTools.intCol;
import static io.deephaven.engine.util.TableTools.longCol;
import static io.deephaven.engine.util.TableTools.merge;
import static io.deephaven.engine.util.TableTools.newTable;
import static io.deephaven.engine.util.TableTools.shortCol;
import static io.deephaven.engine.util.TableTools.stringCol;
import static io.deephaven.parquet.base.materializers.ParquetMaterializerUtils.MAX_CONVERTIBLE_MILLIS;
import static io.deephaven.parquet.table.ParquetTableWriter.INDEX_ROW_SET_COLUMN_NAME;
import static io.deephaven.parquet.table.ParquetTools.readTable;
import static io.deephaven.parquet.table.ParquetTools.writeKeyValuePartitionedTable;
import static io.deephaven.parquet.table.ParquetTools.writeTable;
import static io.deephaven.parquet.table.ParquetTools.writeTables;
import static io.deephaven.util.QueryConstants.*;
import static org.apache.parquet.schema.LogicalTypeAnnotation.decimalType;
import static org.apache.parquet.schema.LogicalTypeAnnotation.intType;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT32;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT64;
import static org.apache.parquet.schema.Types.optional;
import static org.junit.Assert.*;
import static org.junit.Assert.assertNotNull;

@Category(OutOfBandTest.class)
public final class ParquetTableReadWriteTest {

    private static final String ROOT_FILENAME = ParquetTableReadWriteTest.class.getName() + "_root";
    private static final int LARGE_TABLE_SIZE = 2_000_000;

    private static final ParquetInstructions EMPTY = ParquetInstructions.EMPTY;
    private static final ParquetInstructions REFRESHING = ParquetInstructions.builder().setIsRefreshing(true).build();

    private static File rootFile;

    @Rule
    public final EngineCleanup framework = new EngineCleanup();

    @Before
    public void setUp() {
        rootFile = new File(ROOT_FILENAME);
        if (rootFile.exists()) {
            FileUtils.deleteRecursively(rootFile);
        }
        // noinspection ResultOfMethodCallIgnored
        rootFile.mkdirs();
    }

    @After
    public void tearDown() {
        FileUtils.deleteRecursively(rootFile);
    }

    private static Table getTableFlat(int size, boolean includeSerializable, boolean includeBigDecimal) {
        ExecutionContext.getContext().getQueryLibrary().importClass(SomeSillyTest.class);
        final Collection<String> columns =
                new ArrayList<>(Arrays.asList("someStringColumn = i % 10 == 0?null:(`` + (i % 101))",
                        "nonNullString = `` + (i % 60)",
                        "nonNullPolyString = `` + (i % 600)",
                        "someIntColumn = i",
                        "someLongColumn = ii",
                        "someDoubleColumn = i*1.1",
                        "someFloatColumn = (float)(i*1.1)",
                        "someBoolColumn = i % 3 == 0?true:i%3 == 1?false:null",
                        "someShortColumn = (short)i",
                        "someByteColumn = (byte)i",
                        "someCharColumn = (char)i",
                        "someTime = DateTimeUtils.now() + i",
                        "someKey = `` + (int)(i /100)",
                        "someBiColumn = i % 10 == 0 ? null : java.math.BigInteger.valueOf(ii)",
                        "someDateColumn = i % 10 == 0 ? null : java.time.LocalDate.ofEpochDay(i)",
                        "someTimeColumn = i % 10 == 0 ? null : java.time.LocalTime.of(i%24, i%60, (i+10)%60)",
                        "someDateTimeColumn = i % 10 == 0 ? null : java.time.LocalDateTime.of(2000+i%10, i%12+1, i%30+1, (i+4)%24, (i+5)%60, (i+6)%60, i)",
                        "nullKey = i < -1?`123`:null",
                        "nullIntColumn = (int)null",
                        "nullLongColumn = (long)null",
                        "nullDoubleColumn = (double)null",
                        "nullFloatColumn = (float)null",
                        "nullBoolColumn = (Boolean)null",
                        "nullShortColumn = (short)null",
                        "nullByteColumn = (byte)null",
                        "nullCharColumn = (char)null",
                        "nullTime = (Instant)null",
                        "nullBiColumn = (java.math.BigInteger)null",
                        "nullString = (String)null",
                        "nullDateColumn = (java.time.LocalDate)null",
                        "nullTimeColumn = (java.time.LocalTime)null"));
        if (includeBigDecimal) {
            columns.add("bdColumn = i % 10 == 0 ? null : java.math.BigDecimal.valueOf(ii).stripTrailingZeros()");
        }
        if (includeSerializable) {
            columns.add("someSerializable = i % 10 == 0 ? null : new SomeSillyTest(i)");
        }
        return TableTools.emptyTable(size).select(
                Selectable.from(columns));
    }

    private static Table getOneColumnTableFlat(int size) {
        ExecutionContext.getContext().getQueryLibrary().importClass(SomeSillyTest.class);
        return TableTools.emptyTable(size).select(
                // "someBoolColumn = i % 3 == 0?true:i%3 == 1?false:null"
                "someIntColumn = i % 3 == 0 ? null:i");
    }

    private static Table getGroupedOneColumnTable(int size) {
        Table t = getOneColumnTableFlat(size);
        ExecutionContext.getContext().getQueryLibrary().importClass(ArrayStringSet.class);
        ExecutionContext.getContext().getQueryLibrary().importClass(StringSet.class);
        Table result = t.updateView("groupKey = i % 100 + (int)(i/10)").groupBy("groupKey");
        result = result.select(result.getDefinition().getColumnNames().stream()
                .map(name -> name.equals("groupKey") ? name
                        : (name + " = i % 5 == 0 ? null:(i%3 == 0?" + name + ".subVector(0,0):" + name
                                + ")"))
                .toArray(String[]::new));
        return result;
    }

    public static class SomeSillyTest implements Serializable {
        private static final long serialVersionUID = 6668727512367188538L;
        final int value;

        public SomeSillyTest(int value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "SomeSillyTest{" +
                    "value=" + value +
                    '}';
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof SomeSillyTest)) {
                return false;
            }
            return value == ((SomeSillyTest) obj).value;
        }
    }

    private static Table getEmptyArray(int size) {
        ExecutionContext.getContext().getQueryLibrary().importClass(SomeSillyTest.class);
        return TableTools.emptyTable(size).select(
                "someEmptyString = new String[0]",
                "someEmptyInt = new int[0]",
                "someEmptyBool = new Boolean[0]",
                "someEmptyObject = new SomeSillyTest[0]");
    }

    private static Table getGroupedTable(int size, boolean includeSerializable) {
        Table t = getTableFlat(size, includeSerializable, true);
        ExecutionContext.getContext().getQueryLibrary().importClass(ArrayStringSet.class);
        ExecutionContext.getContext().getQueryLibrary().importClass(StringSet.class);
        Table result = t.updateView("groupKey = i % 100 + (int)(i/10)").groupBy("groupKey");
        result = result.select(result.getDefinition().getColumnNames().stream()
                .map(name -> name.equals("groupKey") ? name
                        : (name + " = i % 5 == 0 ? null:(i%3 == 0?" + name + ".subVector(0,0):" + name
                                + ")"))
                .toArray(String[]::new));
        result = result.update(
                "someStringSet = (StringSet)new ArrayStringSet( ((Object)nonNullString) == null?new String[0]:(String[])nonNullString.toArray())");
        result = result.update(
                "largeStringSet = (StringSet)new ArrayStringSet(((Object)nonNullPolyString) == null?new String[0]:(String[])nonNullPolyString.toArray())");
        result = result.update(
                "nullStringSet = (StringSet)null");
        result = result.update(
                "someStringColumn = (String[])(((Object)someStringColumn) == null?null:someStringColumn.toArray())",
                "nonNullString = (String[])(((Object)nonNullString) == null?null:nonNullString.toArray())",
                "nonNullPolyString = (String[])(((Object)nonNullPolyString) == null?null:nonNullPolyString.toArray())",
                "someBoolColumn = (Boolean[])(((Object)someBoolColumn) == null?null:someBoolColumn.toArray())",
                "someTime = (Instant[])(((Object)someTime) == null?null:someTime.toArray())");
        return result;
    }

    private void flatTable(String tableName, int size, boolean includeSerializable) {
        final Table tableToSave = getTableFlat(size, includeSerializable, true);
        final File dest = new File(rootFile, "ParquetTest_" + tableName + "_test.parquet");
        writeTable(tableToSave, dest.getPath());
        checkSingleTable(maybeFixBigDecimal(tableToSave), dest);
    }

    private void groupedTable(String tableName, int size, boolean includeSerializable) {
        final Table tableToSave = getGroupedTable(size, includeSerializable);
        final File dest = new File(rootFile, "ParquetTest_" + tableName + "_test.parquet");
        writeTable(tableToSave, dest.getPath());
        checkSingleTable(tableToSave, dest);
    }

    private void groupedOneColumnTable(String tableName, int size) {
        final Table tableToSave = getGroupedOneColumnTable(size);
        TableTools.show(tableToSave, 50);
        final File dest = new File(rootFile, "ParquetTest_" + tableName + "_test.parquet");
        writeTable(tableToSave, dest.getPath());
        checkSingleTable(tableToSave, dest);
    }

    private void testEmptyArrayStore(String tableName, int size) {
        final Table tableToSave = getEmptyArray(size);
        final File dest = new File(rootFile, "ParquetTest_" + tableName + "_test.parquet");
        writeTable(tableToSave, dest.getPath());
        checkSingleTable(tableToSave, dest);
    }

    @Test
    public void emptyTrivialTable() {
        final Table t = TableTools.emptyTable(0).select("A = i");
        assertEquals(int.class, t.getDefinition().getColumn("A").getDataType());
        final File dest = new File(rootFile, "ParquetTest_emptyTrivialTable.parquet");
        writeTable(t, dest.getPath());
        final Table fromDisk = checkSingleTable(t, dest);
        assertEquals(t.getDefinition(), fromDisk.getDefinition());
    }

    @Test
    public void flatParquetFormat() {
        flatTable("emptyFlatParquet", 0, true);
        flatTable("smallFlatParquet", 20, true);
        flatTable("largeFlatParquet", LARGE_TABLE_SIZE, false);
    }

    @Test
    public void vectorParquetFormat() {
        testEmptyArrayStore("smallEmpty", 20);
        groupedOneColumnTable("smallAggOneColumn", 20);
        groupedTable("smallAggParquet", 20, true);
        testEmptyArrayStore("largeEmpty", LARGE_TABLE_SIZE);
        groupedOneColumnTable("largeAggOneColumn", LARGE_TABLE_SIZE);
        groupedTable("largeAggParquet", LARGE_TABLE_SIZE, false);
    }

    @Test
    public void indexRetentionThroughGC() {
        final String destPath = Path.of(rootFile.getPath(), "ParquetTest_indexRetention_test").toString();
        final int tableSize = 10_000;
        final Table testTable = TableTools.emptyTable(tableSize).update(
                "symbol = randomInt(0,4)",
                "price = randomInt(0,10000) * 0.01",
                "str_id = `str_` + String.format(`%08d`, randomInt(0,1_000_000))",
                "indexed_val = ii % 10_000");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .addIndexColumns("indexed_val")
                .build();
        final PartitionedTable partitionedTable = testTable.partitionBy("symbol");
        ParquetTools.writeKeyValuePartitionedTable(partitionedTable, destPath, writeInstructions);
        final Table child;

        // We don't need this liveness scope for liveness management, but rather to opt out of the enclosing scope's
        // enforceStrongReachability
        try (final SafeCloseable ignored = LivenessScopeStack.open()) {
            // Read from disk and validate the indexes through GC.
            Table parent = ParquetTools.readTable(destPath);
            child = parent.update("new_val = indexed_val + 1")
                    .update("new_val = new_val + 1")
                    .update("new_val = new_val + 1")
                    .update("new_val = new_val + 1");

            // These indexes will survive GC because the parent table is holding strong references.
            System.gc();

            // The parent table should have the indexes.
            Assert.eqTrue(DataIndexer.hasDataIndex(parent, "symbol"), "hasDataIndex -> symbol");
            Assert.eqTrue(DataIndexer.hasDataIndex(parent, "indexed_val"), "hasDataIndex -> indexed_val");

            // The child table should have the indexes while the parent is retained.
            Assert.eqTrue(DataIndexer.hasDataIndex(child, "symbol"), "hasDataIndex -> symbol");
            Assert.eqTrue(DataIndexer.hasDataIndex(child, "indexed_val"), "hasDataIndex -> indexed_val");

            // Force the parent to null to allow GC to collect it.
            parent = null;
        }

        // After a GC, the child table should still have access to the indexes.
        System.gc();
        Assert.eqTrue(DataIndexer.hasDataIndex(child, "symbol"), "hasDataIndex -> symbol");
        Assert.eqTrue(DataIndexer.hasDataIndex(child, "indexed_val"), "hasDataIndex -> indexed_val");
    }

    @Test
    public void remappedIndexRetentionThroughGC() {
        final String destPath =
                Path.of(rootFile.getPath(), "ParquetTest_remappedIndexRetention_test.parquet").toString();
        final int tableSize = 10_000;
        final Table testTable = TableTools.emptyTable(tableSize).update(
                "symbol = randomInt(0,4)",
                "price = randomInt(0,10000) * 0.01",
                "str_id = `str_` + String.format(`%08d`, randomInt(0,1_000_000))",
                "indexed_val = ii % 10_000");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .addIndexColumns("symbol")
                .addIndexColumns("indexed_val")
                .build();
        ParquetTools.writeTable(testTable, destPath, writeInstructions);
        final Table child;

        // We don't need this liveness scope for liveness management, but rather to opt out of the enclosing scope's
        // enforceStrongReachability
        try (final SafeCloseable ignored = LivenessScopeStack.open()) {
            // Read from disk and validate the indexes through GC.
            Table parent = ParquetTools.readTable(destPath);

            // select() produces in-memory column sources, triggering the remapping of the indexes.
            child = parent.select();

            // These indexes will survive GC because the parent table is holding strong references.
            System.gc();

            // The parent table should have the indexes.
            Assert.eqTrue(DataIndexer.hasDataIndex(parent, "symbol"), "hasDataIndex -> symbol");
            Assert.eqTrue(DataIndexer.hasDataIndex(parent, "indexed_val"), "hasDataIndex -> indexed_val");

            // The child table should have the indexes while the parent is retained.
            Assert.eqTrue(DataIndexer.hasDataIndex(child, "symbol"), "hasDataIndex -> symbol");
            Assert.eqTrue(DataIndexer.hasDataIndex(child, "indexed_val"), "hasDataIndex -> indexed_val");

            // Force the parent to null to allow GC to collect it.
            parent = null;
        }

        // After a GC, the child table should still have access to the indexes.
        System.gc();
        Assert.eqTrue(DataIndexer.hasDataIndex(child, "symbol"), "hasDataIndex -> symbol");
        Assert.eqTrue(DataIndexer.hasDataIndex(child, "indexed_val"), "hasDataIndex -> indexed_val");
    }

    @Test
    public void indexByLongKey() {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofInt("someInt"),
                ColumnDefinition.ofLong("someLong"));

        final Table testTable =
                ((QueryTable) TableTools.emptyTable(10).select("someInt = i", "someLong  = ii % 3")
                        .groupBy("someLong").ungroup("someInt")).withDefinitionUnsafe(definition);
        DataIndexer.getOrCreateDataIndex(testTable, "someLong");
        DataIndexer.getOrCreateDataIndex(testTable, "someInt", "someLong");

        final File dest = new File(rootFile, "ParquetTest_groupByLong_test.parquet");
        writeTable(testTable, dest.getPath());
        final Table fromDisk = checkSingleTable(testTable, dest);

        // Validate the indexes and lookup functions.
        verifyIndexingInfoExists(fromDisk, "someLong");
        verifyIndexingInfoExists(fromDisk, "someInt", "someLong");
        verifyIndexingInfoExists(fromDisk, "someLong", "someInt");
    }

    @Test
    public void indexByStringKey() {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofInt("someInt"),
                ColumnDefinition.ofString("someString"));
        final Table testTable =
                ((QueryTable) TableTools.emptyTable(10).select("someInt = i", "someString  = `foo`")
                        .where("i % 2 == 0").groupBy("someString").ungroup("someInt"))
                        .withDefinitionUnsafe(definition);

        DataIndexer.getOrCreateDataIndex(testTable, "someString");
        DataIndexer.getOrCreateDataIndex(testTable, "someInt", "someString");

        final File dest = new File(rootFile, "ParquetTest_groupByString_test.parquet");
        writeTable(testTable, dest.getPath());
        final Table fromDisk = checkSingleTable(testTable, dest);

        // Validate the indexes and lookup functions.
        verifyIndexingInfoExists(fromDisk, "someString");
        verifyIndexingInfoExists(fromDisk, "someInt", "someString");
        verifyIndexingInfoExists(fromDisk, "someString", "someInt");
    }

    @Test
    public void indexByBigInt() {
        ExecutionContext.getContext().getQueryLibrary().importClass(BigInteger.class);
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofInt("someInt"),
                ColumnDefinition.fromGenericType("someBigInt", BigInteger.class));
        final Table testTable = ((QueryTable) TableTools.emptyTable(10)
                .select("someInt = i", "someBigInt  =  BigInteger.valueOf(i % 3)").where("i % 2 == 0")
                .groupBy("someBigInt").ungroup("someInt")).withDefinitionUnsafe(definition);

        DataIndexer.getOrCreateDataIndex(testTable, "someBigInt");
        DataIndexer.getOrCreateDataIndex(testTable, "someInt", "someBigInt");

        final File dest = new File(rootFile, "ParquetTest_groupByBigInt_test.parquet");
        writeTable(testTable, dest.getPath());
        final Table fromDisk = checkSingleTable(testTable, dest);

        // Validate the indexes and lookup functions.
        verifyIndexingInfoExists(fromDisk, "someBigInt");
        verifyIndexingInfoExists(fromDisk, "someInt", "someBigInt");
        verifyIndexingInfoExists(fromDisk, "someBigInt", "someInt");
    }

    @Test
    public void testSortingMetadata() {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofInt("someInt"),
                ColumnDefinition.ofString("someString"));
        final Table testTable =
                ((QueryTable) TableTools.emptyTable(10).select("someInt = i", "someString  = `foo`")
                        .where("i % 2 == 0").groupBy("someString").ungroup("someInt")
                        .sortDescending("someInt"))
                        .withDefinitionUnsafe(definition);

        DataIndexer.getOrCreateDataIndex(testTable, "someString");
        DataIndexer.getOrCreateDataIndex(testTable, "someInt", "someString");

        final File dest = new File(rootFile, "ParquetTest_sortingMetadata_test.parquet");
        writeTable(testTable, dest.getPath());

        final Table fromDisk = checkSingleTable(testTable, dest);

        // Validate the indexes and lookup functions.
        verifyIndexingInfoExists(fromDisk, "someString");
        verifyIndexingInfoExists(fromDisk, "someInt", "someString");
        verifyIndexingInfoExists(fromDisk, "someString", "someInt");

        final ParquetTableLocation tableLocation = new ParquetTableLocation(
                StandaloneTableKey.getInstance(),
                new ParquetTableLocationKey(
                        convertToURI(dest, false),
                        0, Map.of(), EMPTY),
                EMPTY);
        assertEquals(tableLocation.getSortedColumns(), List.of(SortColumn.desc(ColumnName.of("someInt"))));

        final ParquetTableLocation index1Location = new ParquetTableLocation(
                StandaloneTableKey.getInstance(),
                new ParquetTableLocationKey(
                        convertToURI(new File(rootFile,
                                ParquetTools.getRelativeIndexFilePath(dest.getName(), "someString")), false),
                        0, Map.of(), EMPTY),
                EMPTY);
        assertEquals(index1Location.getSortedColumns(), List.of(SortColumn.asc(ColumnName.of("someString"))));
        final Table index1Table = DataIndexer.getDataIndex(fromDisk, "someString").table();
        assertTableEquals(index1Table, index1Table.sort("someString"));

        final ParquetTableLocation index2Location = new ParquetTableLocation(
                StandaloneTableKey.getInstance(),
                new ParquetTableLocationKey(
                        convertToURI(new File(rootFile,
                                ParquetTools.getRelativeIndexFilePath(dest.getName(), "someInt", "someString")), false),
                        0, Map.of(), EMPTY),
                EMPTY);
        assertEquals(index2Location.getSortedColumns(), List.of(
                SortColumn.asc(ColumnName.of("someInt")),
                SortColumn.asc(ColumnName.of("someString"))));
        final Table index2Table = DataIndexer.getDataIndex(fromDisk, "someInt", "someString").table();
        assertTableEquals(index2Table, index2Table.sort("someInt", "someString"));
    }

    static void verifyIndexingInfoExists(final Table table, final String... columnNames) {
        assertTrue(DataIndexer.hasDataIndex(table, columnNames));
        final DataIndex fullIndex = DataIndexer.getDataIndex(table, columnNames);
        Assert.neqNull(fullIndex, "fullIndex");
        assertLookupFromTable(table, fullIndex, columnNames);
    }

    private static void assertLookupFromTable(
            final Table sourceTable,
            final DataIndex fullIndex,
            final String... columnNames) {
        final ColumnSource<?>[] columns = Arrays.stream(columnNames).map(sourceTable::getColumnSource)
                .toArray(ColumnSource[]::new);
        final DataIndex.RowKeyLookup fullIndexRowKeyLookup = fullIndex.rowKeyLookup(columns);
        final ColumnSource<RowSet> fullIndexRowSetColumn = fullIndex.rowSetColumn();

        final ChunkSource.WithPrev<?> tableKeys = DataIndexUtils.makeBoxedKeySource(columns);

        // Iterate through the entire source table and verify the lookup row set is valid and contains this row.
        try (final RowSet.Iterator rsIt = sourceTable.getRowSet().iterator();
                final CloseableIterator<Object> keyIt =
                        ChunkedColumnIterator.make(tableKeys, sourceTable.getRowSet())) {

            while (rsIt.hasNext() && keyIt.hasNext()) {
                final long rowKey = rsIt.nextLong();
                final Object key = keyIt.next();

                // Verify the row sets at the lookup keys match.
                final long fullRowKey = fullIndexRowKeyLookup.apply(key, false);
                Assert.geqZero(fullRowKey, "fullRowKey");

                final RowSet fullRowSet = fullIndexRowSetColumn.get(fullRowKey);
                assertNotNull(fullRowSet);

                assertTrue(fullRowSet.containsRange(rowKey, rowKey));
            }
        }
    }

    private void compressionCodecTestHelper(final ParquetInstructions codec) {
        File dest = new File(rootFile + File.separator + "Table1.parquet");
        final Table table1 = getTableFlat(10000, false, true);
        writeTable(table1, dest.getPath(), codec);
        assertTrue(dest.length() > 0L);
        checkSingleTable(maybeFixBigDecimal(table1), dest);
    }

    @Test
    public void testParquetUncompressedCompressionCodec() {
        compressionCodecTestHelper(ParquetTools.UNCOMPRESSED);
    }

    @Test
    public void testParquetLzoCompressionCodec() {
        compressionCodecTestHelper(ParquetTools.LZO);
    }

    @Test
    public void testParquetLz4CompressionCodec() {
        compressionCodecTestHelper(ParquetTools.LZ4);
    }

    @Test
    public void testLz4RawCompressed() {
        // Write and read a LZ4 compressed file
        File dest = new File(rootFile + File.separator + "Table.parquet");
        final Table table = getTableFlat(100, false, false);
        writeTable(table, dest.getPath(), ParquetTools.LZ4);

        final Table fromDisk = checkSingleTable(table, dest).select();

        // The following file is tagged as LZ4 compressed based on its metadata, but is actually compressed with
        // LZ4_RAW. We should be able to read it anyway with no exceptions.
        final String path =
                ParquetTableReadWriteTest.class.getResource("/sample_lz4_compressed.parquet").getFile();
        readTable(path, EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE)).select();
        final File randomDest = new File(rootFile, "random.parquet");
        writeTable(fromDisk, randomDest.getPath(), ParquetTools.LZ4_RAW);

        // Read the LZ4 compressed file again, to make sure we use a new adapter
        checkSingleTable(table, randomDest);
    }

    @Test
    public void testParquetLz4RawCompressionCodec() {
        compressionCodecTestHelper(ParquetTools.LZ4_RAW);
    }

    @Ignore("See BrotliParquetReadWriteTest instead")
    @Test
    public void testParquetBrotliCompressionCodec() {
        compressionCodecTestHelper(ParquetTools.BROTLI);
    }

    @Test
    public void testParquetZstdCompressionCodec() {
        compressionCodecTestHelper(ParquetTools.ZSTD);
    }

    @Test
    public void testParquetGzipCompressionCodec() {
        compressionCodecTestHelper(ParquetTools.GZIP);
    }

    @Test
    public void testParquetSnappyCompressionCodec() {
        // while Snappy is covered by other tests, this is a very fast test to quickly confirm that it works in the same
        // way as the other similar codec tests.
        compressionCodecTestHelper(ParquetTools.SNAPPY);
    }

    @Test
    public void testBigDecimalPrecisionScale() {
        // https://github.com/deephaven/deephaven-core/issues/3650
        final BigDecimal myBigDecimal = new BigDecimal(".0005");
        assertEquals(1, myBigDecimal.precision());
        assertEquals(4, myBigDecimal.scale());
        final Table table = newTable(new ColumnHolder<>("MyBigDecimal", BigDecimal.class, null, false, myBigDecimal));
        final File dest = new File(rootFile, "ParquetTest_testBigDecimalPrecisionScale.parquet");
        writeTable(table, dest.getPath());
        final Table fromDisk = readTable(dest.getPath(),
                EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE));
        try (final CloseableIterator<BigDecimal> it = fromDisk.objectColumnIterator("MyBigDecimal")) {
            assertTrue(it.hasNext());
            final BigDecimal item = it.next();
            assertFalse(it.hasNext());
            assertEquals(myBigDecimal, item);
        }
    }

    private static void writeReadTableTest(final Table table, final File dest) {
        writeReadTableTest(table, dest, EMPTY);
    }

    private static void writeReadTableTest(final Table table, final File dest,
            final ParquetInstructions writeInstructions) {
        writeTable(table, dest.getPath(), writeInstructions);
        checkSingleTable(table, dest);
    }

    @Test
    public void basicParquetWrongDestinationTest() {
        final Table table = TableTools.emptyTable(5).update("A=(int)i");
        final File dest = new File(rootFile, "basicParquetWrongDestinationTest.parquet");
        writeTable(table, dest.getPath());
        final File wrongDest = new File(rootFile, "basicParquetWrongDestinationTest");
        try {
            writeTable(table, wrongDest.getPath());
            fail("Expected an exception because destination does not end with .parquet");
        } catch (final IllegalArgumentException expected) {
        }
    }

    @Test
    public void basicParquetWithMetadataTest() {
        final Table table = TableTools.emptyTable(5).update("A=(int)i", "B=(long)i", "C=(double)i");
        final String filename = "basicParquetWithMetadataTest.parquet";
        final File destFile = new File(rootFile, filename);
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .build();
        writeTable(table, destFile.getPath(), writeInstructions);

        final File metadataFile = new File(rootFile, "_metadata");
        assertTrue(metadataFile.exists());
        final File commonMetadataFile = new File(rootFile, "_common_metadata");
        assertTrue(commonMetadataFile.exists());

        final Table fromDisk = readTable(destFile.getPath());
        assertTableEquals(table, fromDisk);

        Table fromDiskWithMetadata = readTable(metadataFile.getPath());
        assertTableEquals(table, fromDiskWithMetadata);
        Table fromDiskWithCommonMetadata = readTable(commonMetadataFile.getPath());
        assertTableEquals(table, fromDiskWithCommonMetadata);

        final ParquetInstructions readInstructions = ParquetInstructions.builder()
                .setFileLayout(ParquetInstructions.ParquetFileLayout.METADATA_PARTITIONED)
                .build();
        fromDiskWithMetadata = readTable(metadataFile.getPath(), readInstructions);
        assertTableEquals(table, fromDiskWithMetadata);
        fromDiskWithCommonMetadata = readTable(commonMetadataFile.getPath(), readInstructions);
        assertTableEquals(table, fromDiskWithCommonMetadata);
    }

    @Test
    public void testOverrideBooleanColumnType() {
        final Table table = TableTools.emptyTable(5).update("A = i % 3 == 0 ? true : i % 3 == 1 ? false : null");
        final File dest = new File(rootFile, "testOverrideBooleanColumnType.parquet");
        ParquetTools.writeTable(table, dest.getPath());
        assertTableEquals(table, ParquetTools.readTable(dest.getPath()));

        final Table arrayTable =
                TableTools.emptyTable(5).update("A = new Boolean[] {i % 3 == 0 ? true : i % 3 == 1 ? false : null}");
        final File arrayTableDest = new File(rootFile, "testOverrideBooleanArrayType.parquet");
        ParquetTools.writeTable(arrayTable, arrayTableDest.getPath());
        assertTableEquals(arrayTable, ParquetTools.readTable(arrayTableDest.getPath()));
        // Boolean -> byte
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofByte("A")))
                    .build();
            final Table byteTable =
                    TableTools.emptyTable(5).update("A = i % 3 == 0 ? (byte)1 : i % 3 == 1 ? (byte)0 : null");
            assertTableEquals(byteTable, ParquetTools.readTable(dest.getPath(), readInstructions));

            final Table byteArrayTable =
                    TableTools.emptyTable(5)
                            .update("A = new byte[] {i % 3 == 0 ? (byte)1 : i % 3 == 1 ? (byte)0 : (byte)null}");
            assertTableEquals(byteArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(byteArrayTable.getDefinition())).select());
        }

        // Boolean -> short
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofShort("A")))
                    .build();
            final Table shortTable =
                    TableTools.emptyTable(5).update("A = i % 3 == 0 ? (short)1 : i % 3 == 1 ? (short)0 : null");
            assertTableEquals(shortTable, ParquetTools.readTable(dest.getPath(), readInstructions));

            final Table shortArrayTable =
                    TableTools.emptyTable(5)
                            .update("A = new short[] {i % 3 == 0 ? (short)1 : i % 3 == (short)1 ? 0 : (short)null}");
            assertTableEquals(shortArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(shortArrayTable.getDefinition())).select());
        }

        // Boolean -> int
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofInt("A")))
                    .build();
            final Table intTable = TableTools.emptyTable(5).update("A = i % 3 == 0 ? 1 : i % 3 == 1 ? 0 : null");
            assertTableEquals(intTable, ParquetTools.readTable(dest.getPath(), readInstructions));

            final Table intArrayTable =
                    TableTools.emptyTable(5).update("A = new int[] {i % 3 == 0 ? 1 : i % 3 == 1 ? 0 : null}");
            assertTableEquals(intArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(intArrayTable.getDefinition())).select());
        }
        // Boolean -> long
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofLong("A")))
                    .build();
            final Table longTable = TableTools.emptyTable(5).update("A = i % 3 == 0 ? 1L : i % 3 == 1 ? 0L : null");
            assertTableEquals(longTable, ParquetTools.readTable(dest.getPath(), readInstructions));

            final Table longArrayTable =
                    TableTools.emptyTable(5).update("A = new long[] {i % 3 == 0 ? 1L : i % 3 == 1 ? 0L : null}");
            assertTableEquals(longArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(longArrayTable.getDefinition())).select());
        }
    }

    @Test
    public void testOverrideByteColumnType() {
        final Table table = TableTools.emptyTable(5).update("A=(byte)(i-2)");
        final File dest = new File(rootFile, "testOverrideByteColumnType.parquet");
        ParquetTools.writeTable(table, dest.getPath());
        assertTableEquals(table, ParquetTools.readTable(dest.getPath()));

        final Table arrayTable = TableTools.emptyTable(5).update("A = new byte[] {i == 0 ? null : (byte)(i-2)}");
        final File arrayTableDest = new File(rootFile, "testOverrideByteArrayColumnType.parquet");
        ParquetTools.writeTable(arrayTable, arrayTableDest.getPath());
        assertTableEquals(arrayTable, ParquetTools.readTable(arrayTableDest.getPath()));

        // byte -> short
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofShort("A")))
                    .build();
            assertTableEquals(table.updateView("A=(short)A"),
                    ParquetTools.readTable(dest.getPath(), readInstructions));

            final Table shortArrayTable =
                    TableTools.emptyTable(5).update("A = new short[] {i == 0 ? null : (short)(i-2)}");
            assertTableEquals(shortArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(shortArrayTable.getDefinition())).select());
        }
        // byte -> int
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofInt("A")))
                    .build();
            assertTableEquals(table.updateView("A=(int)A"),
                    ParquetTools.readTable(dest.getPath(), readInstructions));

            final Table intArrayTable = TableTools.emptyTable(5).update("A = new int[] {i == 0 ? null : (int)(i-2)}");
            assertTableEquals(intArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(intArrayTable.getDefinition())).select());
        }
        // byte -> long
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofLong("A")))
                    .build();
            assertTableEquals(table.updateView("A=(long)A"),
                    ParquetTools.readTable(dest.getPath(), readInstructions));

            final Table longArrayTable =
                    TableTools.emptyTable(5).update("A = new long[] {i == 0 ? null : (long)(i-2)}");
            assertTableEquals(longArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(longArrayTable.getDefinition())).select());
        }
        // byte -> char
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofChar("A")))
                    .build();
            try {
                ParquetTools.readTable(dest.getPath(), readInstructions).select();
                fail("Expected an exception because cannot convert byte to char");
            } catch (final RuntimeException ignored) {
            }
        }
    }

    @Test
    public void testOverrideShortColumnType() {
        final Table table = TableTools.emptyTable(5).update("A=(short)(i-2)");
        final File dest = new File(rootFile, "testOverrideShortColumnType.parquet");
        ParquetTools.writeTable(table, dest.getPath());
        assertTableEquals(table, ParquetTools.readTable(dest.getPath()));

        final Table arrayTable = TableTools.emptyTable(5).update("A = new short[] {i == 0 ? null : (short)(i-2)}");
        final File arrayTableDest = new File(rootFile, "testOverrideShortArrayColumnType.parquet");
        ParquetTools.writeTable(arrayTable, arrayTableDest.getPath());
        assertTableEquals(arrayTable, ParquetTools.readTable(arrayTableDest.getPath()));

        // short -> int
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofInt("A")))
                    .build();
            assertTableEquals(table.updateView("A=(int)A"),
                    ParquetTools.readTable(dest.getPath(), readInstructions));

            final Table intArrayTable = TableTools.emptyTable(5).update("A = new int[] {i == 0 ? null : (int)(i-2)}");
            assertTableEquals(intArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(intArrayTable.getDefinition())).select());
        }
        // short -> long
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofLong("A")))
                    .build();
            assertTableEquals(table.updateView("A=(long)A"),
                    ParquetTools.readTable(dest.getPath(), readInstructions));

            final Table longArrayTable =
                    TableTools.emptyTable(5).update("A = new long[] {i == 0 ? null : (long)(i-2)}");
            assertTableEquals(longArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(longArrayTable.getDefinition())).select());
        }
        // short -> byte
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofByte("A")))
                    .build();
            try {
                ParquetTools.readTable(dest.getPath(), readInstructions).select();
                fail("Expected an exception because cannot convert short to byte");
            } catch (final RuntimeException ignored) {
            }
        }
    }

    @Test
    public void testOverrideCharColumnType() {
        final Table table = TableTools.emptyTable(5).update("A=(char)i");
        final File dest = new File(rootFile, "testOverrideCharColumnType.parquet");
        ParquetTools.writeTable(table, dest.getPath());
        assertTableEquals(table, ParquetTools.readTable(dest.getPath()));

        final Table arrayTable = TableTools.emptyTable(5).update("A = new char[] {i == 0 ? null : (char)i}");
        final File arrayTableDest = new File(rootFile, "testOverrideCharArrayColumnType.parquet");
        ParquetTools.writeTable(arrayTable, arrayTableDest.getPath());
        assertTableEquals(arrayTable, ParquetTools.readTable(arrayTableDest.getPath()));

        // char -> int
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofInt("A")))
                    .build();
            assertTableEquals(table.updateView("A=(int)A"),
                    ParquetTools.readTable(dest.getPath(), readInstructions));

            final Table intArrayTable = TableTools.emptyTable(5).update("A = new int[] {i == 0 ? null : (int)i}");
            assertTableEquals(intArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(intArrayTable.getDefinition())).select());
        }
        // char -> long
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofLong("A")))
                    .build();
            assertTableEquals(table.updateView("A=(long)A"),
                    ParquetTools.readTable(dest.getPath(), readInstructions));

            final Table longArrayTable = TableTools.emptyTable(5).update("A = new long[] {i == 0 ? null : (long)i}");
            assertTableEquals(longArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(longArrayTable.getDefinition())).select());
        }
        // char -> short
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofShort("A")))
                    .build();
            try {
                ParquetTools.readTable(dest.getPath(), readInstructions).select();
                fail("Expected an exception because cannot convert char to short");
            } catch (final RuntimeException ignored) {
            }
        }
    }

    @Test
    public void testOverrideIntColumnType() {
        final Table table = TableTools.emptyTable(5).update("A=(int)(i-2)");
        final File dest = new File(rootFile, "testOverrideIntColumnType.parquet");
        ParquetTools.writeTable(table, dest.getPath());
        assertTableEquals(table, ParquetTools.readTable(dest.getPath()));

        final Table arrayTable = TableTools.emptyTable(5).update("A = new int[] {i == 0 ? null : (int)(i-2)}");
        final File arrayTableDest = new File(rootFile, "testOverrideIntArrayColumnType.parquet");
        ParquetTools.writeTable(arrayTable, arrayTableDest.getPath());
        assertTableEquals(arrayTable, ParquetTools.readTable(arrayTableDest.getPath()));

        // int -> long
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofLong("A")))
                    .build();
            assertTableEquals(table.updateView("A=(long)A"),
                    ParquetTools.readTable(dest.getPath(), readInstructions));
            final Table longArrayTable =
                    TableTools.emptyTable(5).update("A = new long[] {i == 0 ? null : (long)(i-2)}");
            assertTableEquals(longArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(longArrayTable.getDefinition())).select());
        }

        // int -> short
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofShort("A")))
                    .build();
            try {
                ParquetTools.readTable(dest.getPath(), readInstructions).select();
                fail("Expected an exception because cannot convert int to short");
            } catch (final RuntimeException ignored) {
            }
        }
    }

    @Test
    public void testOverrideFloatColumnType() {
        final Table table = TableTools.emptyTable(5).update("A=(float)(i-2)");
        final File dest = new File(rootFile, "testOverrideFloatColumnType.parquet");
        ParquetTools.writeTable(table, dest.getPath());
        assertTableEquals(table, ParquetTools.readTable(dest.getPath()));

        final Table arrayTable = TableTools.emptyTable(5).update("A = new float[] {i == 0 ? null : (float)(i-2)}");
        final File arrayTableDest = new File(rootFile, "testOverrideFloatArrayColumnType.parquet");
        ParquetTools.writeTable(arrayTable, arrayTableDest.getPath());
        assertTableEquals(arrayTable, ParquetTools.readTable(arrayTableDest.getPath()));

        // float -> double
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofDouble("A")))
                    .build();
            assertTableEquals(table.updateView("A=(double)A"),
                    ParquetTools.readTable(dest.getPath(), readInstructions));
            final Table doubleArrayTable =
                    TableTools.emptyTable(5).update("A = new double[] {i == 0 ? null : (double)(i-2)}");
            assertTableEquals(doubleArrayTable, ParquetTools.readTable(arrayTableDest.getPath(),
                    readInstructions.withTableDefinition(doubleArrayTable.getDefinition())).select());
        }

        // float -> short
        {
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(TableDefinition.of(ColumnDefinition.ofShort("A")))
                    .build();
            try {
                ParquetTools.readTable(dest.getPath(), readInstructions).select();
                fail("Expected an exception because cannot convert float to short");
            } catch (final RuntimeException ignored) {
            }
        }
    }


    @Test
    public void parquetIndexingBuilderTest() {
        final Table source = TableTools.emptyTable(1_000_000).updateView(
                "A = (int)(ii%3)",
                "B = (double)(ii%2)",
                "C = ii");
        DataIndexer.getOrCreateDataIndex(source, "A", "B");
        final File destFile = new File(rootFile, "parquetIndexingBuilderTest.parquet");
        writeTable(source, destFile.getPath());
        Table fromDisk = readTable(destFile.getPath());
        assertTableEquals(source, fromDisk);
        verifyIndexingInfoExists(fromDisk, "A", "B");

        // Set a single column for indexing
        ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .addIndexColumns("A")
                .build();
        writeTable(source, destFile.getPath(), writeInstructions);
        fromDisk = readTable(destFile.getPath());
        assertTableEquals(source, fromDisk);
        verifyIndexingInfoExists(fromDisk, "A");
        assertFalse(DataIndexer.hasDataIndex(fromDisk, "A", "B"));
        assertNull(DataIndexer.getDataIndex(fromDisk, "A", "B"));

        // Clear all indexing columns
        writeInstructions = ParquetInstructions.builder()
                .addAllIndexColumns(Collections.emptyList())
                .build();
        writeTable(source, destFile.getPath(), writeInstructions);
        fromDisk = readTable(destFile.getPath());
        assertFalse(DataIndexer.hasDataIndex(fromDisk, "A", "B"));

        // Set multiple columns for indexing
        final Collection<List<String>> indexColumns = List.of(List.of("A", "C"), List.of("C"));
        writeInstructions = ParquetInstructions.builder()
                .addAllIndexColumns(indexColumns)
                .build();
        writeTable(source, destFile.getPath(), writeInstructions);
        fromDisk = readTable(destFile.getPath());
        assertTableEquals(source, fromDisk);
        verifyIndexingInfoExists(fromDisk, "A", "C");
        verifyIndexingInfoExists(fromDisk, "C");
    }

    @Test
    public void parquetWithIndexingDataAndMetadataTest() {
        final File parentDir = new File(rootFile, "tempDir");
        final int[] data = new int[500 * 4];
        for (int i = 0; i < data.length; i++) {
            data[i] = i / 4;
        }
        final Table indexedTable = newTable(TableTools.intCol("vvv", data));
        DataIndexer.getOrCreateDataIndex(indexedTable, "vvv");

        final File destFile = new File(parentDir, "parquetWithIndexingDataAndMetadataTest.parquet");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .build();
        writeTable(indexedTable, destFile.getPath(), writeInstructions);

        final Table fromDisk = readTable(destFile.getPath());
        assertTableEquals(indexedTable, fromDisk);
        verifyIndexingInfoExists(fromDisk, "vvv");

        final File metadataFile = new File(parentDir, "_metadata");
        final Table fromDiskWithMetadata = readTable(metadataFile.getPath());
        assertTableEquals(indexedTable, fromDiskWithMetadata);
        verifyIndexingInfoExists(fromDiskWithMetadata, "vvv");
    }

    @Test
    public void flatPartitionedParquetWithMetadataTest() throws IOException {
        // Create an empty parent directory
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();
        assertTrue(parentDir.exists() && parentDir.isDirectory() && parentDir.list().length == 0);

        final Table someTable = TableTools.emptyTable(5).update("A=(int)i");
        final File firstDataFile = new File(parentDir, "data1.parquet");
        final File secondDataFile = new File(parentDir, "data2.parquet");

        // Write without any metadata files
        writeTables(new Table[] {someTable, someTable},
                new String[] {firstDataFile.getPath(), secondDataFile.getPath()},
                ParquetInstructions.EMPTY);
        final Table source = readTable(parentDir.getPath()).select();

        // Now write with metadata files
        parentDir.delete();
        parentDir.mkdir();
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .build();
        writeTables(new Table[] {someTable, someTable},
                new String[] {firstDataFile.getPath(), secondDataFile.getPath()},
                writeInstructions);

        final Table fromDisk = readTable(parentDir.getPath());
        assertTableEquals(source, fromDisk);

        final File metadataFile = new File(parentDir, "_metadata");
        final Table fromDiskWithMetadata = readTable(metadataFile.getPath());
        assertTableEquals(source, fromDiskWithMetadata);

        // Now replace the underlying data files with empty files and read the size from metadata file verifying that
        // we can read the size without touching the data
        firstDataFile.delete();
        firstDataFile.createNewFile();
        secondDataFile.delete();
        secondDataFile.createNewFile();
        final Table fromDiskWithMetadataWithoutData = readTable(metadataFile.getPath());
        assertEquals(source.size(), fromDiskWithMetadataWithoutData.size());

        // If we call select now, this should fail because the data files are empty
        try {
            fromDiskWithMetadataWithoutData.select();
            fail("Expected exception when reading table with empty data files");
        } catch (final RuntimeException expected) {
        }

        // Now write with flat partitioned parquet files to different directories with metadata file
        parentDir.delete();
        final File updatedSecondDataFile = new File(rootFile, "testDir/data2.parquet");
        try {
            writeTables(new Table[] {someTable, someTable},
                    new String[] {firstDataFile.getPath(), updatedSecondDataFile.getPath()},
                    writeInstructions);
            fail("Expected exception when writing the metadata files for tables with different parent directories");
        } catch (final RuntimeException expected) {
        }
    }

    @Test
    public void flatPartitionedParquetWithBigDecimalMetadataTest() throws IOException {
        final File parentDir = new File(rootFile, "tempDir");

        // Both tables have different precision for big decimal column
        final Table firstTable = TableTools.emptyTable(5).update("bdColumn = java.math.BigDecimal.valueOf((double)ii)");
        final Table secondTable =
                TableTools.emptyTable(5).update("bdColumn = java.math.BigDecimal.valueOf((double)(ii*0.001))");
        final File firstDataFile = new File(parentDir, "data1.parquet");
        final File secondDataFile = new File(parentDir, "data2.parquet");

        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .build();
        final Table[] sources = new Table[] {firstTable, secondTable};
        writeTables(sources, new String[] {firstDataFile.getPath(), secondDataFile.getPath()}, writeInstructions);

        // Merge the tables and compute the precision and scale as per the union of the two tables
        final Table expected =
                maybeFixBigDecimal(PartitionedTableFactory.ofTables(firstTable.getDefinition(), sources).merge());

        final Table fromDisk = readTable(parentDir.getPath()).select();
        assertTableEquals(expected, fromDisk);
        final Table fromDiskWithMetadata = readTable(new File(parentDir, "_metadata").getPath());
        assertTableEquals(expected, fromDiskWithMetadata);
    }

    @Test
    public void keyValuePartitionedWithMetadataTest() throws IOException {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofInt("PC1").withPartitioning(),
                ColumnDefinition.ofInt("PC2").withPartitioning(),
                ColumnDefinition.ofLong("I"));
        final Table source = ((QueryTable) TableTools.emptyTable(1_000_000)
                .updateView("PC1 = (int)(ii%3)",
                        "PC2 = (int)(ii%2)",
                        "I = ii"))
                .withDefinitionUnsafe(definition);

        final File parentDir = new File(rootFile, "keyValuePartitionedWithMetadataTest");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .setBaseNameForPartitionedParquetData("data")
                .build();
        writeKeyValuePartitionedTable(source, parentDir.getAbsolutePath(), writeInstructions);

        final Table fromDisk = readTable(parentDir.getPath());
        assertTableEquals(source.sort("PC1", "PC2"), fromDisk.sort("PC1", "PC2"));

        final File metadataFile = new File(parentDir, "_metadata");
        final Table fromDiskWithMetadata = readTable(metadataFile.getPath());
        assertTableEquals(source.sort("PC1", "PC2"), fromDiskWithMetadata.sort("PC1", "PC2"));

        final File firstDataFile =
                new File(parentDir, "PC1=0" + File.separator + "PC2=0" + File.separator + "data.parquet");
        final File secondDataFile =
                new File(parentDir, "PC1=0" + File.separator + "PC2=1" + File.separator + "data.parquet");
        assertTrue(firstDataFile.exists());
        assertTrue(secondDataFile.exists());

        // Now replace the underlying data files with empty files and read the size from metadata file verifying that
        // we can read the size without touching the data
        firstDataFile.delete();
        firstDataFile.createNewFile();
        secondDataFile.delete();
        secondDataFile.createNewFile();
        final Table fromDiskWithMetadataWithoutData = readTable(metadataFile.getPath());
        assertEquals(source.size(), fromDiskWithMetadataWithoutData.size());

        // If we call select now, this should fail because the data files are empty
        try {
            fromDiskWithMetadataWithoutData.select();
            fail("Expected exception when reading table with empty data files");
        } catch (final RuntimeException expected) {
        }
    }

    @Test
    public void writeKeyValuePartitionedDataWithIntegerPartitionsTest() {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofInt("PC1").withPartitioning(),
                ColumnDefinition.ofInt("PC2").withPartitioning(),
                ColumnDefinition.ofLong("I"));
        final Table indexedtable = ((QueryTable) TableTools.emptyTable(1_000_000)
                .updateView("PC1 = (int)(ii%3)",
                        "PC2 = (int)(ii%2)",
                        "I = ii"))
                .withDefinitionUnsafe(definition);
        DataIndexer.getOrCreateDataIndex(indexedtable, "I");

        final File parentDir = new File(rootFile, "writeKeyValuePartitionedDataTest");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .setBaseNameForPartitionedParquetData("data")
                .build();
        writeKeyValuePartitionedTable(indexedtable, parentDir.getAbsolutePath(), writeInstructions);

        // Verify that metadata files are generated
        assertTrue(new File(parentDir, "_common_metadata").exists());
        assertTrue(new File(parentDir, "_metadata").exists());

        // Verify that the partitioning and indexing data exists
        for (int PC1 = 0; PC1 <= 2; PC1++) {
            for (int PC2 = 0; PC2 <= 1; PC2++) {
                final File dir = new File(parentDir, "PC1=" + PC1 + File.separator + "PC2=" + PC2);
                assertTrue(dir.exists() && dir.isDirectory());
                final File dataFile = new File(dir, "data.parquet");
                assertTrue(dataFile.exists() && dataFile.isFile());
                final File indexFile = new File(dir, ".dh_metadata/indexes/I/index_I_data.parquet");
                assertTrue(indexFile.exists() && indexFile.isFile());
            }
        }

        final Table fromDisk = readTable(parentDir.getPath(),
                EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
        verifyIndexingInfoExists(fromDisk, "I");
        fromDisk.where("I == 3").select();
        assertTableEquals(indexedtable.sort("PC1", "PC2"), fromDisk.sort("PC1", "PC2"));

        final File commonMetadata = new File(parentDir, "_common_metadata");
        final Table fromDiskWithMetadata = readTable(commonMetadata.getPath());
        fromDiskWithMetadata.where("I == 3").select();
        assertTableEquals(indexedtable.sort("PC1", "PC2"), fromDiskWithMetadata.sort("PC1", "PC2"));

        // Write the same table without generating metadata files
        final File parentDirWithoutMetadata = new File(rootFile, "writeKeyValuePartitionedDataWithoutMetadata");
        writeKeyValuePartitionedTable(indexedtable, parentDirWithoutMetadata.getAbsolutePath(), EMPTY);

        // Verify that no metadata files are generated
        assertFalse(new File(parentDirWithoutMetadata, "_common_metadata").exists());
        assertFalse(new File(parentDirWithoutMetadata, "_metadata").exists());

        // Verify that the partitioning and indexing data exists
        for (int PC1 = 0; PC1 <= 2; PC1++) {
            for (int PC2 = 0; PC2 <= 1; PC2++) {
                final File dir = new File(parentDirWithoutMetadata, "PC1=" + PC1 + File.separator + "PC2=" + PC2);
                assertTrue(dir.exists() && dir.isDirectory());
                final File[] fileList = dir.listFiles();
                for (final File dataFile : fileList) {
                    // hidden indexing data
                    assertTrue(dataFile.getName().equals(".dh_metadata")
                            || dataFile.getName().endsWith(".parquet"));
                }
            }
        }
        final Table fromDiskWithoutMetadata = readTable(parentDirWithoutMetadata.getPath(),
                EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
        assertTableEquals(fromDisk, fromDiskWithoutMetadata);
        verifyIndexingInfoExists(fromDiskWithoutMetadata, "I");
    }

    @Test
    public void writeKeyValuePartitionedDataWithNoNonPartitioningColumnsTest() {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofInt("PC1").withPartitioning(),
                ColumnDefinition.ofInt("PC2").withPartitioning());
        final Table inputData = ((QueryTable) TableTools.emptyTable(20)
                .updateView("PC1 = (int)(ii%3)",
                        "PC2 = (int)(ii%2)"))
                .withDefinitionUnsafe(definition);

        final File parentDir = new File(rootFile, "writeKeyValuePartitionedDataTest");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .setBaseNameForPartitionedParquetData("data")
                .build();
        try {
            writeKeyValuePartitionedTable(inputData, parentDir.getAbsolutePath(), writeInstructions);
            fail("Expected exception when writing the partitioned table with no non-partitioning columns");
        } catch (final RuntimeException expected) {
        }
    }

    @Test
    public void writeKeyValuePartitionedDataWithNonUniqueKeys() {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofInt("PC1").withPartitioning(),
                ColumnDefinition.ofLong("I"));
        final Table inputData = ((QueryTable) TableTools.emptyTable(10)
                .updateView("PC1 = (int)(ii%3)",
                        "I = ii"))
                .withDefinitionUnsafe(definition);
        final PartitionedTable partitionedTable = inputData.partitionBy("PC1");
        final Table internalTable = partitionedTable.table();
        final Table internalTableDuplicated = merge(internalTable, internalTable);
        final PartitionedTable partitionedTableWithDuplicatedKeys = PartitionedTableFactory.of(internalTableDuplicated);
        assertFalse(partitionedTableWithDuplicatedKeys.uniqueKeys());
        final File parentDir = new File(rootFile, "writeKeyValuePartitionedDataWithNonUniqueKeys");
        ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .setBaseNameForPartitionedParquetData("{partitions}-data")
                .build();
        try {
            writeKeyValuePartitionedTable(partitionedTableWithDuplicatedKeys, parentDir.getAbsolutePath(),
                    writeInstructions);
            fail("Expected exception when writing the partitioned table with non-unique keys without {i} or {uuid} in "
                    + "base name");
        } catch (final RuntimeException expected) {
        }

        // Write the partitioned table with non-unique keys with {i} in the base name
        writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .setBaseNameForPartitionedParquetData("{partitions}-data-{i}")
                .build();
        writeKeyValuePartitionedTable(partitionedTableWithDuplicatedKeys, parentDir.getAbsolutePath(),
                writeInstructions);

        // Verify that the partitioned data exists
        for (int PC1 = 0; PC1 <= 2; PC1++) {
            final File dir = new File(parentDir, "PC1=" + PC1 + File.separator);
            assertTrue(dir.exists() && dir.isDirectory());
            final String[] dataFileList = dir.list();
            assertEquals(2, dataFileList.length);
            for (final String dataFile : dataFileList) {
                assertTrue(dataFile.equals("PC1=" + PC1 + "-data-0.parquet") ||
                        dataFile.equals("PC1=" + PC1 + "-data-1.parquet"));
            }
        }

        final Table expected = merge(inputData, inputData);
        final Table fromDisk = readTable(parentDir.getPath(),
                EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
        assertTableEquals(expected.sort("PC1"), fromDisk.sort("PC1"));

        final File commonMetadata = new File(parentDir, "_common_metadata");
        final Table fromDiskWithMetadata = readTable(commonMetadata.getPath());
        assertTableEquals(expected.sort("PC1"), fromDiskWithMetadata.sort("PC1"));

        FileUtils.deleteRecursively(parentDir);

        // Write the partitioned table with non-unique keys with {uuid} in the base name
        writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .setBaseNameForPartitionedParquetData("data-{uuid}")
                .build();
        writeKeyValuePartitionedTable(partitionedTableWithDuplicatedKeys, parentDir.getAbsolutePath(),
                writeInstructions);

        // Verify that the partitioned data exists with uuid in names
        for (int PC1 = 0; PC1 <= 2; PC1++) {
            final File dir = new File(parentDir, "PC1=" + PC1 + File.separator);
            assertTrue(dir.exists() && dir.isDirectory());
            final String[] dataFileList = dir.list();
            assertEquals(2, dataFileList.length);
            for (final String dataFile : dataFileList) {
                assertTrue(dataFile.startsWith("data-") && dataFile.endsWith(".parquet"));
            }
        }

        FileUtils.deleteRecursively(parentDir);

        // Write the partitioned table with non-unique keys without a base name
        writeKeyValuePartitionedTable(partitionedTableWithDuplicatedKeys, parentDir.getAbsolutePath(),
                writeInstructions);

        // Verify that the partitioned data exists with uuid in names
        for (int PC1 = 0; PC1 <= 2; PC1++) {
            final File dir = new File(parentDir, "PC1=" + PC1 + File.separator);
            assertTrue(dir.exists() && dir.isDirectory());
            final String[] dataFileList = dir.list();
            assertEquals(2, dataFileList.length);
            for (final String dataFile : dataFileList) {
                assertTrue(dataFile.endsWith(".parquet"));
            }
        }
    }

    @Test
    public void writeKeyValuePartitionedDataWithNullKeys() {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofInt("PC1").withPartitioning(),
                ColumnDefinition.ofLong("I"));
        final Table inputData = ((QueryTable) TableTools.emptyTable(10)
                .updateView("PC1 = (ii%2==0)? null : (int)(ii%2)",
                        "I = ii"))
                .withDefinitionUnsafe(definition);
        final PartitionedTable partitionedTable = inputData.partitionBy("PC1");
        final File parentDir = new File(rootFile, "writeKeyValuePartitionedDataWithNullKeys");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .build();
        writeKeyValuePartitionedTable(partitionedTable, parentDir.getAbsolutePath(), writeInstructions);
        final Table fromDisk = readTable(parentDir.getPath(),
                EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED)).select();
        assertTableEquals(inputData.sort("PC1"), fromDisk.sort("PC1"));
        final Table fromDiskWithMetadata = readTable(new File(parentDir, "_common_metadata").getPath()).select();
        assertTableEquals(inputData.sort("PC1"), fromDiskWithMetadata.sort("PC1"));
    }

    @Test
    public void writeKeyValuePartitionedDataWithMixedPartitionsTest() {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofInt("PC1").withPartitioning(),
                ColumnDefinition.ofChar("PC2"),
                ColumnDefinition.ofString("PC3"),
                ColumnDefinition.ofInt("PC4").withPartitioning(),
                ColumnDefinition.ofLong("II"),
                ColumnDefinition.ofInt("I"));
        final Table inputData = ((QueryTable) TableTools.emptyTable(10)
                .updateView("PC1 = (int)(ii%3)",
                        "PC2 = (char)(65 + (ii % 2))",
                        "PC3 = java.time.LocalDate.ofEpochDay(i%2).toString()",
                        "PC4 = (int)(ii%4)",
                        "II = ii",
                        "I = i"))
                .withDefinitionUnsafe(definition);

        // We skip one partitioning and one non-partitioning column in the definition, and add some more partitioning
        // and non-partitioning columns
        final TableDefinition tableDefinitionToWrite = TableDefinition.of(
                ColumnDefinition.ofInt("PC1").withPartitioning(),
                ColumnDefinition.ofChar("PC2").withPartitioning(),
                ColumnDefinition.ofString("PC3").withPartitioning(),
                ColumnDefinition.ofInt("I"),
                ColumnDefinition.ofInt("J"));

        final File parentDir = new File(rootFile, "writeKeyValuePartitionedDataTest");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .setTableDefinition(tableDefinitionToWrite)
                .build();
        writeKeyValuePartitionedTable(inputData, parentDir.getPath(), writeInstructions);

        // Verify that the partitioned data exists
        for (int PC1 = 0; PC1 <= 2; PC1++) {
            for (int idx = 0; idx <= 1; idx++) {
                final char PC2 = (char) ('A' + idx);
                final String PC3 = java.time.LocalDate.ofEpochDay(idx).toString();
                final File dir = new File(parentDir, "PC1=" + PC1 + File.separator + "PC2=" + PC2 +
                        File.separator + "PC3=" + PC3);
                assertTrue(dir.exists() && dir.isDirectory());
                final String[] dataFileList = dir.list();
                assertEquals(1, dataFileList.length);
                assertTrue(dataFileList[0].endsWith(".parquet"));
            }
        }

        // Give then updated table definition used to write the data, we drop the column "II" and add a new column "J"
        final Table expected = inputData.dropColumns("II", "PC4").updateView("J = (int)null");
        final Table fromDisk =
                readTable(parentDir.getPath(), EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
        assertTableEquals(expected.sort("PC1", "PC2"), fromDisk.sort("PC1", "PC2"));

        final File commonMetadata = new File(parentDir, "_common_metadata");
        final Table fromDiskWithMetadata = readTable(commonMetadata.getPath());
        assertTableEquals(expected.sort("PC1", "PC2"), fromDiskWithMetadata.sort("PC1", "PC2"));

        // Delete some files from the partitioned data and read the required rows to verify that we only read the
        // required partitions
        FileUtils.deleteRecursivelyOnNFS(new File(parentDir, "PC1=0"));
        FileUtils.deleteRecursivelyOnNFS(new File(parentDir, "PC1=1"));
        assertTableEquals(expected.where("PC1 == 2").sort("PC1", "PC2", "PC3"),
                readTable(commonMetadata.getPath()).where("PC1 == 2").sort("PC1", "PC2", "PC3"));
    }

    @Test
    public void testWritingPartitionedDatasetWithIndexOnPartitioningColumn() {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofInt("PC1").withPartitioning(),
                ColumnDefinition.ofInt("PC2").withPartitioning(),
                ColumnDefinition.ofLong("I"));
        final Table inputData = TableTools.emptyTable(10)
                .update("PC1 = (ii%2==0)? null : (int)(ii%2)",
                        "PC2 = (int)(ii%3)",
                        "I = ii");
        final File parentDir = new File(rootFile, "writeKeyValuePartitionedDataWithIndexOnPC");
        final ParquetInstructions instructionsWithIndexOnPC = ParquetInstructions.builder()
                .setTableDefinition(definition)
                .addIndexColumns("PC1") // Adding index on partitioning column
                .build();

        try {
            writeKeyValuePartitionedTable(inputData, parentDir.getAbsolutePath(), instructionsWithIndexOnPC);
            fail("Expected exception when adding index on partitioning column");
        } catch (final IllegalArgumentException exception) {
            assertTrue(exception.getMessage().contains("Cannot add index on partitioning column"));
        }
        try {
            writeKeyValuePartitionedTable(
                    inputData.partitionBy("PC1"), parentDir.getAbsolutePath(), instructionsWithIndexOnPC);
            fail("Expected exception when adding index on partitioning column");
        } catch (final IllegalArgumentException exception) {
            assertTrue(exception.getMessage().contains("Cannot add index on partitioning column"));
        }

        // Add a data index on a partitioning column
        DataIndexer.getOrCreateDataIndex(inputData, "PC1");
        writeKeyValuePartitionedTable(inputData, parentDir.getAbsolutePath(), ParquetInstructions.builder()
                .setTableDefinition(definition)
                .setBaseNameForPartitionedParquetData("data")
                .build());

        // Make sure we didn't write the index
        final File parquetDataDir = new File(parentDir, "PC1=1/PC2=1");
        verifyFilesInDir(parquetDataDir, new String[] {"data.parquet"}, null);

        final Table fromDisk = readTable(parentDir.getPath());

        // Make sure an index is present on the partitioning column, which will be the partitioning index
        verifyIndexingInfoExists(fromDisk, "PC1");
        verifyIndexingInfoExists(fromDisk, "PC2");

        // Make sure the data is read correctly
        assertEquals(definition, fromDisk.getDefinition());
        assertTableEquals(inputData.sort("PC1", "PC2"), fromDisk.sort("PC1", "PC2"));
    }

    @Test
    public void testReadingPartitionedDatasetWithIndexOnPartitioningColumn() {
        final TableDefinition expectedDefinition = TableDefinition.of(
                ColumnDefinition.ofInt("PC1").withPartitioning(),
                ColumnDefinition.ofInt("PC2").withPartitioning(),
                ColumnDefinition.ofLong("I"));
        final Table expectedValues = TableTools.emptyTable(10)
                .update("PC1 = (ii%2==0)? null : (int)(ii%2)",
                        "PC2 = (int)(ii%3)",
                        "I = ii");
        final String parentDir =
                ParquetTableReadWriteTest.class.getResource("/referencePartitionedDataWithIndexOnPC").getFile();
        final File parquetDataDir = new File(parentDir, "PC1=1/PC2=1");
        final String pc1IndexFilePath = ".dh_metadata/indexes/PC1/index_PC1_data.parquet";
        final String pc2IndexFilePath = ".dh_metadata/indexes/PC2/index_PC2_data.parquet";
        verifyFilesInDir(parquetDataDir, new String[] {"data.parquet"},
                Map.of("PC1", new String[] {pc1IndexFilePath},
                        "PC2", new String[] {pc2IndexFilePath}));

        final Table fromDisk = readTable(parentDir);
        // Make sure an index is present on the partitioning column, which will be the partitioning index
        verifyIndexingInfoExists(fromDisk, "PC1");
        verifyIndexingInfoExists(fromDisk, "PC2");

        // Make sure the data is read correctly
        assertEquals(expectedDefinition, fromDisk.getDefinition());
        assertTableEquals(expectedValues.sort("PC1", "PC2"), fromDisk.sort("PC1", "PC2"));
    }

    @Test
    public void someMoreKeyValuePartitionedTestsWithComplexKeys() {
        // Verify complex keys both with and without data index
        someMoreKeyValuePartitionedTestsWithComplexKeysHelper(true);
        someMoreKeyValuePartitionedTestsWithComplexKeysHelper(false);
    }

    private void someMoreKeyValuePartitionedTestsWithComplexKeysHelper(final boolean addDataIndex) {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofString("symbol").withPartitioning(),
                ColumnDefinition.ofString("epic_collection_id"),
                ColumnDefinition.ofString("epic_request_id"),
                ColumnDefinition.ofLong("I"));
        final Table inputData = ((QueryTable) TableTools.emptyTable(10)
                .updateView("symbol = (i % 2 == 0) ? `AA` : `BB`",
                        "epic_collection_id = (i % 2 == 0) ? `fss_tick%1234%4321` : `fss_tick%5678%8765`",
                        "epic_request_id = (i % 2 == 0) ? `223ea-asd43` : `98dce-oiu23`",
                        "I = ii"))
                .withDefinitionUnsafe(definition);

        final File parentDir = new File(rootFile, "someMoreKeyValuePartitionedTestsWithComplexKeys");
        if (parentDir.exists()) {
            FileUtils.deleteRecursively(parentDir);
        }
        final ParquetInstructions writeInstructions;
        if (addDataIndex) {
            writeInstructions = ParquetInstructions.builder()
                    .setGenerateMetadataFiles(true)
                    .addIndexColumns("I", "epic_request_id")
                    .build();
        } else {
            writeInstructions = ParquetInstructions.builder()
                    .setGenerateMetadataFiles(true)
                    .build();
        }
        final String[] partitioningCols = new String[] {"symbol", "epic_collection_id", "epic_request_id"};
        final PartitionedTable partitionedTable = inputData.partitionBy(partitioningCols);
        writeKeyValuePartitionedTable(partitionedTable, parentDir.getPath(), writeInstructions);

        final Table fromDisk =
                readTable(parentDir.getPath(), EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
        if (addDataIndex) {
            // Verify if index present on columns "I, epic_request_id"
            verifyIndexingInfoExists(fromDisk, "I", "epic_request_id");
        }

        for (final String col : partitioningCols) {
            assertTrue(fromDisk.getDefinition().getColumn(col).isPartitioning());
        }
        assertTableEquals(inputData.sort("symbol", "epic_collection_id"),
                fromDisk.sort("symbol", "epic_collection_id"));

        final Table fromDiskMetadataPartitioned =
                readTable(parentDir.getPath(),
                        EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.METADATA_PARTITIONED));
        for (final String col : partitioningCols) {
            assertTrue(fromDiskMetadataPartitioned.getDefinition().getColumn(col).isPartitioning());
        }
        assertTableEquals(inputData.sort("symbol", "epic_collection_id"),
                fromDiskMetadataPartitioned.sort("symbol", "epic_collection_id"));

        final File metadata = new File(parentDir, "_metadata");
        final Table fromDiskWithMetadata = readTable(metadata.getPath());
        assertTableEquals(inputData.sort("symbol", "epic_collection_id"),
                fromDiskWithMetadata.sort("symbol", "epic_collection_id"));

        final File commonMetadata = new File(parentDir, "_common_metadata");
        final Table fromDiskWithCommonMetadata = readTable(commonMetadata.getPath());
        assertTableEquals(inputData.sort("symbol", "epic_collection_id"),
                fromDiskWithCommonMetadata.sort("symbol", "epic_collection_id"));
    }

    @Test
    public void testAllPartitioningColumnTypes() {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofString("PC1").withPartitioning(),
                ColumnDefinition.ofBoolean("PC2").withPartitioning(),
                ColumnDefinition.ofChar("PC3").withPartitioning(),
                ColumnDefinition.ofByte("PC4").withPartitioning(),
                ColumnDefinition.ofShort("PC5").withPartitioning(),
                ColumnDefinition.ofInt("PC6").withPartitioning(),
                ColumnDefinition.ofLong("PC7").withPartitioning(),
                ColumnDefinition.ofFloat("PC8").withPartitioning(),
                ColumnDefinition.ofDouble("PC9").withPartitioning(),
                ColumnDefinition.of("PC10", Type.find(BigInteger.class)).withPartitioning(),
                ColumnDefinition.of("PC11", Type.find(BigDecimal.class)).withPartitioning(),
                ColumnDefinition.of("PC12", Type.find(Instant.class)).withPartitioning(),
                ColumnDefinition.of("PC13", Type.find(LocalDate.class)).withPartitioning(),
                ColumnDefinition.of("PC14", Type.find(LocalTime.class)).withPartitioning(),
                ColumnDefinition.ofInt("data"));

        final Table inputData = ((QueryTable) TableTools.emptyTable(10).updateView(
                "PC1 =  (ii%2 == 0) ? null: ((ii%3 == 0) ? `AA` : `BB`)",
                "PC2 = (ii%2 == 0) ? null : (ii % 3 == 0)",
                "PC3 = (ii%2 == 0) ? null : (char)(65 + (ii % 2))",
                "PC4 = (ii%2 == 0) ? null : (byte)(ii % 2)",
                "PC5 = (ii%2 == 0) ? null : (short)(ii % 2)",
                "PC6 = (ii%2 == 0) ? null : (int)(ii%3)",
                "PC7 = (ii%2 == 0) ? null : (long)(ii%2)",
                "PC8 = (ii%2 == 0) ? null : (float)(ii % 2)",
                "PC9 = (ii%2 == 0) ? null : (double)(ii % 2)",
                "PC10 = (ii%2 == 0) ? null : java.math.BigInteger.valueOf(ii)",
                "PC11 = (ii%2 == 0) ? null : ((ii%3 == 0) ? java.math.BigDecimal.valueOf((double)ii) : java.math.BigDecimal.valueOf((double)(ii*0.001)))",
                "PC12 = (ii%2 == 0) ? null : java.time.Instant.ofEpochSecond(ii)",
                "PC13 = (ii%2 == 0) ? null : java.time.LocalDate.ofEpochDay(ii)",
                "PC14 = (ii%2 == 0) ? null : java.time.LocalTime.of(i%24, i%60, (i+10)%60)",
                "data = (int)(ii)"))
                .withDefinitionUnsafe(definition);

        final File parentDir = new File(rootFile, "testAllPartitioningColumnTypes");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .build();
        writeKeyValuePartitionedTable(inputData, parentDir.getPath(), writeInstructions);

        // Verify that we can read the partition values, but types like LocalDate or LocalTime will be read as strings,
        // and byte, short will be read as integers. Therefore, we cannot compare the tables directly
        final Table fromDiskPartitioned = readTable(parentDir.getPath(),
                EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
        assertNotEquals(fromDiskPartitioned.getDefinition(), inputData.getDefinition());

        // Reading the directory directly should correctly detect the metadata files and deduce the correct types
        final Table fromDiskWithMetadata = readTable(parentDir.getPath());
        assertEquals(fromDiskWithMetadata.getDefinition(), inputData.getDefinition());
        final String[] partitioningColumns = definition.getPartitioningColumns().stream()
                .map(ColumnDefinition::getName).toArray(String[]::new);
        assertTableEquals(inputData.sort(partitioningColumns), fromDiskWithMetadata.sort(partitioningColumns));
    }

    @Test
    public void testAllNonPartitioningColumnTypes() {
        final TableDefinition definition = TableDefinition.of(
                ColumnDefinition.ofString("PC1").withPartitioning(),
                ColumnDefinition.ofInt("PC2").withPartitioning(),
                ColumnDefinition.ofString("NPC1"),
                ColumnDefinition.ofBoolean("NPC2"),
                ColumnDefinition.ofChar("NPC3"),
                ColumnDefinition.ofByte("NPC4"),
                ColumnDefinition.ofShort("NPC5"),
                ColumnDefinition.ofInt("NPC6"),
                ColumnDefinition.ofLong("NPC7"),
                ColumnDefinition.ofFloat("NPC8"),
                ColumnDefinition.ofDouble("NPC9"),
                ColumnDefinition.of("NPC10", Type.find(BigInteger.class)),
                ColumnDefinition.of("bdColumn", Type.find(BigDecimal.class)),
                ColumnDefinition.of("NPC12", Type.find(Instant.class)),
                ColumnDefinition.of("NPC13", Type.find(LocalDate.class)),
                ColumnDefinition.of("NPC14", Type.find(LocalTime.class)));

        final Table inputData = ((QueryTable) TableTools.emptyTable(10).updateView(
                "PC1 =  (ii%2 == 0) ? `AA` : `BB`",
                "PC2 = (int)(ii%3)",
                "NPC1 = (ii%2 == 0) ? `AA` : `BB`",
                "NPC2 = (ii % 2 == 0)",
                "NPC3 = (char)(65 + (ii % 2))",
                "NPC4 = (byte)(ii % 2)",
                "NPC5 = (short)(ii % 2)",
                "NPC6 = (int)(ii%3)",
                "NPC7 = (long)(ii%2)",
                "NPC8 = (float)(ii % 2)",
                "NPC9 = (double)(ii % 2)",
                "NPC10 = java.math.BigInteger.valueOf(ii)",
                "bdColumn = (ii%2 == 0) ? java.math.BigDecimal.valueOf((double)ii) : java.math.BigDecimal.valueOf((double)(ii*0.001))",
                "NPC12 = java.time.Instant.ofEpochSecond(ii)",
                "NPC13 = java.time.LocalDate.ofEpochDay(ii)",
                "NPC14 = java.time.LocalTime.of(i%24, i%60, (i+10)%60)"))
                .withDefinitionUnsafe(definition);

        final File parentDir = new File(rootFile, "testAllNonPartitioningColumnTypes");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .build();

        // First we test passing the table directly without any table definition
        writeKeyValuePartitionedTable(inputData, parentDir.getPath(), writeInstructions);

        // Store the big decimal with the precision and scale consistent with what we write to parquet
        final Table bigDecimalFixedInputData = maybeFixBigDecimal(inputData);

        final String[] partitioningColumns = definition.getPartitioningColumns().stream()
                .map(ColumnDefinition::getName).toArray(String[]::new);
        {
            final Table fromDisk = readTable(parentDir.getPath(),
                    EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED)).select();
            assertTableEquals(bigDecimalFixedInputData.sort(partitioningColumns), fromDisk.sort(partitioningColumns));
            final Table fromDiskWithMetadata = readTable(new File(parentDir, "_common_metadata").getPath());
            assertTableEquals(bigDecimalFixedInputData.sort(partitioningColumns),
                    fromDiskWithMetadata.sort(partitioningColumns));
            FileUtils.deleteRecursively(parentDir);
        }

        // Next test passing additional indexing columns
        final String indexColumn = "NPC5";
        final Collection<List<String>> indexColumns = Collections.singleton(List.of(indexColumn));
        final ParquetInstructions withIndexColumns = writeInstructions.withIndexColumns(indexColumns);
        {
            writeKeyValuePartitionedTable(inputData, parentDir.getPath(), withIndexColumns);
            assertFalse(DataIndexer.hasDataIndex(inputData, indexColumn));
            final Table fromDisk = readTable(parentDir.getPath(),
                    EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            verifyIndexingInfoExists(fromDisk, indexColumn);
            assertTableEquals(bigDecimalFixedInputData.sort(partitioningColumns), fromDisk.sort(partitioningColumns));
            final Table fromDiskWithMetadata = readTable(new File(parentDir, "_common_metadata").getPath());
            assertTableEquals(bigDecimalFixedInputData.sort(partitioningColumns),
                    fromDiskWithMetadata.sort(partitioningColumns));
            FileUtils.deleteRecursively(parentDir);
        }

        // Next test passing the partitioned table without any table definition
        final PartitionedTable partitionedTable = inputData.partitionBy("PC1");
        {
            writeKeyValuePartitionedTable(partitionedTable, parentDir.getPath(), writeInstructions);
            final Table fromDisk = readTable(parentDir.getPath(),
                    EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED)).select();
            assertTableEquals(bigDecimalFixedInputData.sort(partitioningColumns), fromDisk.sort(partitioningColumns));
            final Table fromDiskWithMetadata = readTable(new File(parentDir, "_common_metadata").getPath());
            assertTableEquals(bigDecimalFixedInputData.sort(partitioningColumns),
                    fromDiskWithMetadata.sort(partitioningColumns));
            FileUtils.deleteRecursively(parentDir);
        }

        // Next test passing additional indexing columns with partitioned table and no definition
        {
            writeKeyValuePartitionedTable(partitionedTable, parentDir.getPath(), withIndexColumns);
            final Table fromDisk = readTable(parentDir.getPath(),
                    EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            verifyIndexingInfoExists(fromDisk, "NPC5");
            assertTableEquals(bigDecimalFixedInputData.sort(partitioningColumns), fromDisk.sort(partitioningColumns));
            final Table fromDiskWithMetadata = readTable(new File(parentDir, "_common_metadata").getPath());
            assertTableEquals(bigDecimalFixedInputData.sort(partitioningColumns),
                    fromDiskWithMetadata.sort(partitioningColumns));
            FileUtils.deleteRecursively(parentDir);
        }

        // Next test passing the regular table with an updated table definition where we drop
        // some partitioning columns and non-partitioning columns and add some new non-partitioning columns
        final List<ColumnDefinition<?>> oldColumns = definition.getColumns();
        final List<ColumnDefinition<?>> newColumns = oldColumns.stream()
                .filter(cd -> (cd.getName() != "PC2") && (cd.getName() != "NPC6"))
                .collect(Collectors.toList());
        newColumns.add(ColumnDefinition.ofInt("NPC15"));
        final TableDefinition newDefinition = TableDefinition.of(newColumns);
        final ParquetInstructions withDefinition = writeInstructions.withTableDefinition(newDefinition);
        final Table expected = bigDecimalFixedInputData.dropColumns("PC2", "NPC6").updateView("NPC15 = (int)null");
        {
            writeKeyValuePartitionedTable(inputData, parentDir.getAbsolutePath(), withDefinition);
            final Table fromDisk = readTable(parentDir.getPath(),
                    EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED)).select();
            assertTableEquals(expected.sort("PC1"), fromDisk.sort("PC1"));
            final Table fromDiskWithMetadata = readTable(new File(parentDir, "_common_metadata").getPath());
            assertTableEquals(expected.sort("PC1"), fromDiskWithMetadata.sort("PC1"));
            FileUtils.deleteRecursively(parentDir);
        }

        // Next test passing table with updated definition and additional indexing columns
        final ParquetInstructions withDefinitionAndIndexColumns = withDefinition.withIndexColumns(indexColumns);
        {
            writeKeyValuePartitionedTable(inputData, parentDir.getAbsolutePath(), withDefinitionAndIndexColumns);
            assertFalse(DataIndexer.hasDataIndex(inputData, indexColumn));
            final Table fromDisk = readTable(parentDir.getPath(),
                    EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            verifyIndexingInfoExists(fromDisk, indexColumn);
            assertTableEquals(expected.sort("PC1"), fromDisk.sort("PC1"));
            final Table fromDiskWithMetadata = readTable(new File(parentDir, "_common_metadata").getPath());
            assertTableEquals(expected.sort("PC1"), fromDiskWithMetadata.sort("PC1"));
            FileUtils.deleteRecursively(parentDir);
        }

        // Next test passing the partitioned table with an updated table definition
        {
            writeKeyValuePartitionedTable(partitionedTable, parentDir.getPath(), withDefinition);
            final Table fromDisk = readTable(parentDir.getPath(),
                    EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED)).select();
            assertTableEquals(expected.sort("PC1"), fromDisk.sort("PC1"));
            final Table fromDiskWithMetadata = readTable(new File(parentDir, "_common_metadata").getPath());
            assertTableEquals(expected.sort("PC1"), fromDiskWithMetadata.sort("PC1"));
            FileUtils.deleteRecursively(parentDir);
        }

        // Next test passing the indexing columns with partitioned table and an updated table definition
        {
            writeKeyValuePartitionedTable(partitionedTable, parentDir.getPath(), withDefinitionAndIndexColumns);
            final Table fromDisk = readTable(parentDir.getPath(),
                    EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            verifyIndexingInfoExists(fromDisk, "NPC5");
            assertTableEquals(expected.sort("PC1"), fromDisk.sort("PC1"));
            final Table fromDiskWithMetadata = readTable(new File(parentDir, "_common_metadata").getPath());
            assertTableEquals(expected.sort("PC1"), fromDiskWithMetadata.sort("PC1"));
            FileUtils.deleteRecursively(parentDir);
        }
    }

    private static Instant epochMicrosToInstant(final long micros) {
        return Instant.ofEpochSecond(micros / 1000000L, micros % 1000000L * 1000L);
    }

    private static Instant epochMillisToInstant(final long millis) {
        return Instant.ofEpochMilli(millis);
    }

    /**
     * This test verified how Deephaven handles DH Null sentinel values in timestamps and dates when reading from
     * Parquet files.
     *
     * <pre>
     * import pyarrow as pa
     * import pyarrow.parquet as pq
     * import numpy as np
     *
     * # Deephaven “null” sentinels
     * NULL_LONG = -0x8000000000000000
     * NULL_INT  = np.int32(-0x80000000)
     *
     * MAX_LONG      = 0x7fffffffffffffff
     * MAX_CONVERTIBLE_MICROS   =  MAX_LONG // 1_000
     * MIN_CONVERTIBLE_MICROS   = -MAX_CONVERTIBLE_MICROS
     * MAX_CONVERTIBLE_MILLIS   =  MAX_LONG // 1_000_000
     * MIN_CONVERTIBLE_MILLIS   = -MAX_CONVERTIBLE_MILLIS
     *
     * arrays = [
     *     pa.array([NULL_LONG], type=pa.timestamp('ms', tz='UTC')),  # timestamps_ms_null
     *     pa.array([MAX_CONVERTIBLE_MILLIS], type=pa.timestamp('ms', tz='UTC')), # timestamps_ms_max
     *     pa.array([MIN_CONVERTIBLE_MILLIS], type=pa.timestamp('ms', tz='UTC')), # timestamps_ms_min
     *
     *     pa.array([NULL_LONG],  type=pa.timestamp('us', tz='UTC')), # timestamps_us_null
     *     pa.array([MAX_CONVERTIBLE_MICROS], type=pa.timestamp('us', tz='UTC')), # timestamps_us_max
     *     pa.array([MIN_CONVERTIBLE_MICROS], type=pa.timestamp('us', tz='UTC')), # timestamps_us_min
     *
     *     pa.array([NULL_LONG],  type=pa.timestamp('ns', tz='UTC')), # timestamps_ns_null
     *
     *     pa.array([NULL_INT],   type=pa.date32()),                  # date_null
     * ]
     *
     * names = [
     *     "timestamps_ms_null",   "timestamps_ms_max",   "timestamps_ms_min",
     *     "timestamps_us_null",   "timestamps_us_max",   "timestamps_us_min",
     *     "timestamps_ns_null",
     *     "date_null"
     * ]
     *
     * table = pa.Table.from_arrays(arrays, names=names)
     * pq.write_table(table, "ReferenceDHNullTimestamp.parquet")
     * </pre>
     */
    @Test
    public void testReadingDeephavenNullsForTimestamp() {
        final String path =
                ParquetTableReadWriteTest.class.getResource("/ReferenceDHNullTimestamp.parquet").getFile();
        final Table fromDisk = readTable(path, EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE));
        {
            try {
                fromDisk.getColumnSource("timestamps_ms_null").get(0);
                fail("Expected exception for because DH cannot represent NULL_LONG as a timestamp in millis");
            } catch (final UncheckedDeephavenException e) {
                assertTrue(e.getCause().getMessage().contains("millis to nanos would overflow"));
            }
            assertEquals(epochMillisToInstant(MAX_CONVERTIBLE_MILLIS),
                    fromDisk.getColumnSource("timestamps_ms_max").get(0));
            assertEquals(epochMillisToInstant(-MAX_CONVERTIBLE_MILLIS),
                    fromDisk.getColumnSource("timestamps_ms_min").get(0));
        }
        {
            try {
                fromDisk.getColumnSource("timestamps_us_null").get(0);
                fail("Expected exception for because DH cannot represent NULL_LONG as a timestamp in millis");
            } catch (final UncheckedDeephavenException e) {
                assertTrue(e.getCause().getMessage().contains("micros to nanos would overflow"));
            }
            assertEquals(epochMicrosToInstant(ParquetMaterializerUtils.MAX_CONVERTIBLE_MICROS),
                    fromDisk.getColumnSource("timestamps_us_max").get(0));
            assertEquals(epochMicrosToInstant(-ParquetMaterializerUtils.MAX_CONVERTIBLE_MICROS),
                    fromDisk.getColumnSource("timestamps_us_min").get(0));
        }
        assertEquals(null, fromDisk.getColumnSource("timestamps_ns_null").get(0));
        assertEquals(LocalDate.ofEpochDay(NULL_INT), fromDisk.getColumnSource("date_null").get(0));
    }

    @Test
    public void testReadingParquetDataWithEmptyRowGroups() {
        {
            // Single parquet file with empty row group
            final String path =
                    ParquetTableReadWriteTest.class.getResource("/ReferenceParquetWithEmptyRowGroup1.parquet")
                            .getFile();
            final Table fromDisk =
                    readTable(path, EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE)).select();
            assertEquals(0, fromDisk.size());
            assertTrue(fromDisk.getRowSet().isEmpty());
        }

        {
            // Single parquet file with three row groups, first and third row group are non-empty, and second row group
            // is empty. To generate this file, the following branch was used:
            // https://github.com/malhotrashivam/deephaven-core/tree/sm-ref-branch
            final String path =
                    ParquetTableReadWriteTest.class.getResource("/ReferenceParquetWithEmptyRowGroup2.parquet")
                            .getFile();
            final Table fromDisk =
                    readTable(path, EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE)).select();
            assertEquals(20, fromDisk.size());
            final Table table = TableTools.emptyTable(10).update("integers = (int)(ii%3)");
            final Table expected = merge(table, table);
            assertTableEquals(expected, fromDisk);
        }

        {
            // Parquet dataset with three files, first and third file have three row groups, two non-empty followed by
            // an empty row group, and second file has just one empty row group.
            final String dirPath = ParquetTableReadWriteTest.class.getResource("/datasetWithEmptyRowgroups").getFile();
            assertFalse(readTable(dirPath + "/file1.parquet").isEmpty());
            assertTrue(readTable(dirPath + "/file2.parquet").isEmpty());
            assertFalse(readTable(dirPath + "/file3.parquet").isEmpty());

            final Table table = readTable(dirPath).select();
            assertEquals(2138182, table.size());
            assertEquals(4, table.numColumns());
            assertEquals(1068950, table.selectDistinct("price").size());
        }
    }

    @Test
    public void testReadingReferenceParquetDataWithCodec() {
        {
            final BigDecimalParquetBytesCodec bdCodec = new BigDecimalParquetBytesCodec(20, 1);
            final BigIntegerParquetBytesCodec biCodec = new BigIntegerParquetBytesCodec();
            ExecutionContext.getContext().getQueryScope().putParam("__bdCodec", bdCodec);
            ExecutionContext.getContext().getQueryScope().putParam("__biCodec", biCodec);
            final Table source = TableTools.emptyTable(10_000).update(
                    "LocalDateColumn = ii % 10 == 0 ? null :  java.time.LocalDate.ofEpochDay(ii)",
                    "CompactLocalDateColumn = ii % 10 == 0 ? null :  java.time.LocalDate.ofEpochDay(ii)",
                    "LocalTimeColumn = ii % 10 == 0 ? null :  java.time.LocalTime.ofSecondOfDay(ii % 86400)",
                    "ZonedDateTimeColumn = ii % 10 == 0 ? null :  java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(ii), java.time.ZoneId.of(\"UTC\"))",
                    "StringColumn = ii % 10 == 0 ? null : java.lang.String.valueOf(ii)",
                    "BigDecimalColumn = ii % 10 == 0 ? null : ii % 2 == 0 ? java.math.BigDecimal.valueOf(ii).stripTrailingZeros() : java.math.BigDecimal.valueOf(-1 * ii).stripTrailingZeros()",
                    "BigDecimalColumnEncoded = ii % 10 == 0 ? null : __bdCodec.encode(BigDecimalColumn)",
                    "BigDecimalColumnDecoded = ii % 10 == 0 ? null : __bdCodec.decode(BigDecimalColumnEncoded, 0, BigDecimalColumnEncoded.length)",
                    "BigIntegerColumn = ii % 10 == 0 ? null : ii % 2 == 0 ? java.math.BigInteger.valueOf(ii*512) : java.math.BigInteger.valueOf(-1*ii*512)",
                    "BigIntegerColumnEncoded = ii % 10 == 0 ? null : __biCodec.encode(BigIntegerColumn)",
                    "BigIntegerColumnDecoded = ii % 10 == 0 ? null : __biCodec.decode(BigIntegerColumnEncoded, 0, BigIntegerColumnEncoded.length)");

            // Set codecs for each column
            final ParquetInstructions instructions = ParquetInstructions.builder()
                    .addColumnCodec("LocalDateColumn", "io.deephaven.util.codec.LocalDateCodec")
                    .addColumnCodec("CompactLocalDateColumn", "io.deephaven.util.codec.LocalDateCodec", "Compact")
                    .addColumnCodec("LocalTimeColumn", "io.deephaven.util.codec.LocalTimeCodec")
                    .addColumnCodec("ZonedDateTimeColumn", "io.deephaven.util.codec.ZonedDateTimeCodec")
                    .addColumnCodec("StringColumn", "io.deephaven.util.codec.UTF8StringAsByteArrayCodec")
                    .addColumnCodec("BigDecimalColumn", "io.deephaven.util.codec.BigDecimalCodec", "20,1,allowrounding")
                    .addColumnCodec("BigIntegerColumn", "io.deephaven.util.codec.BigIntegerCodec")
                    .build();

            {
                // Verify that we can write and read the table with codecs
                final File dest = new File(rootFile, "ReferenceParquetWithCodecData.parquet");
                ParquetTools.writeTable(source, dest.getPath(), instructions);
                checkSingleTable(source, dest);
                dest.delete();
            }
            {
                // Verify that we can read the reference parquet file with these codecs
                final String path =
                        ParquetTableReadWriteTest.class.getResource("/ReferenceParquetWithCodecData.parquet").getFile();
                final Table fromDisk = readParquetFileFromGitLFS(new File(path));
                assertTableEquals(source, fromDisk);
            }
        }

        {
            // Repeat similar tests for RowSetCodec
            final Table source = TableTools.emptyTable(10_000).updateView(
                    "A = (int)(ii%3)",
                    "B = (double)(ii%2)",
                    "C = ii");
            final DataIndex dataIndex = DataIndexer.getOrCreateDataIndex(source, "A", "B");
            final File destFile = new File(rootFile, "ReferenceParquetWithRowsetCodecData.parquet");
            final Table indexTable = dataIndex.table();
            final ParquetInstructions instructions = ParquetInstructions.builder()
                    .addColumnCodec(INDEX_ROW_SET_COLUMN_NAME, "io.deephaven.engine.table.impl.dataindex.RowSetCodec")
                    .build();
            {
                writeTable(indexTable, destFile.getPath(), instructions);
                final Table fromDisk = readTable(destFile.getPath());
                assertTableEquals(indexTable, fromDisk);
                destFile.delete();
            }
            {
                final String path =
                        ParquetTableReadWriteTest.class.getResource("/ReferenceParquetWithRowsetCodecData.parquet")
                                .getFile();
                final Table fromDiskWithCodec = readParquetFileFromGitLFS(new File(path));
                assertTableEquals(indexTable, fromDiskWithCodec);
            }
        }
    }

    @Test
    public void decimalLogicalTypeTest() {
        final Table expected = TableTools.emptyTable(100_000).update(
                "DecimalIntCol = ii % 10 == 0 ? null : java.math.BigDecimal.valueOf(ii*12, 5)",
                "DecimalLongCol = ii % 10 == 0 ? null : java.math.BigDecimal.valueOf(ii*212, 8)");

        {
            // This reference file has Decimal logical type columns stored as INT32 and INT64 physical types
            final String path =
                    ParquetTableReadWriteTest.class.getResource("/ReferenceDecimalLogicalType.parquet").getFile();
            final Table fromDisk = readParquetFileFromGitLFS(new File(path));
            final ParquetMetadata metadata =
                    new ParquetTableLocationKey(new File(path).toURI(), 0, null, ParquetInstructions.EMPTY)
                            .getMetadata();
            final MessageType expectedSchema = Types.buildMessage()
                    .addFields(
                            optional(INT32).as(decimalType(5, 7)).named("DecimalIntCol"),
                            optional(INT64).as(decimalType(8, 12)).named("DecimalLongCol"))
                    .named("root");
            assertEquals(expectedSchema, metadata.getFileMetaData().getSchema());
            assertTableEquals(expected, fromDisk);
        }

        {
            // This reference file has Decimal logical type columns stored as FIXED_LEN_BYTE_ARRAY physical types
            final String path =
                    ParquetTableReadWriteTest.class.getResource("/ReferenceDecimalLogicalType2.parquet").getFile();
            final Table fromDisk = readParquetFileFromGitLFS(new File(path));
            final ParquetMetadata metadata =
                    new ParquetTableLocationKey(new File(path).toURI(), 0, null, ParquetInstructions.EMPTY)
                            .getMetadata();
            final MessageType expectedSchema = Types.buildMessage()
                    .addFields(
                            optional(FIXED_LEN_BYTE_ARRAY).length(4).as(decimalType(5, 7)).named("DecimalIntCol"),
                            optional(FIXED_LEN_BYTE_ARRAY).length(6).as(decimalType(8, 12)).named("DecimalLongCol"))
                    .named("schema"); // note: this file must have been written down with a different schema name
            assertEquals(expectedSchema, metadata.getFileMetaData().getSchema());
            assertTableEquals(expected, fromDisk);
        }
    }

    @Test
    public void metadataPartitionedDataWithCustomTableDefinition() throws IOException {
        final Table inputData = TableTools.emptyTable(100).update(
                "PC1 = (ii%2 == 0) ? `Apple` : `Ball`",
                "PC2 = (ii%3 == 0) ? `Pen` : `Pencil`",
                "numbers = ii",
                "characters = (char) (65 + (ii % 23))");
        final TableDefinition tableDefinition = TableDefinition.of(
                ColumnDefinition.ofString("PC1").withPartitioning(),
                ColumnDefinition.ofString("PC2").withPartitioning(),
                ColumnDefinition.ofLong("numbers"),
                ColumnDefinition.ofChar("characters"));
        final File parentDir = new File(rootFile, "metadataPartitionedDataWithCustomTableDefinition");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .setTableDefinition(tableDefinition)
                .build();
        writeKeyValuePartitionedTable(inputData, parentDir.getAbsolutePath(), writeInstructions);
        final Table fromDisk = readTable(parentDir.getPath(),
                EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.METADATA_PARTITIONED));
        assertEquals(fromDisk.getDefinition(), tableDefinition);
        assertTableEquals(inputData.sort("PC1", "PC2"), fromDisk.sort("PC1", "PC2"));

        // Now set a different table definitions and read the data
        {
            // Remove a column
            final TableDefinition newTableDefinition = TableDefinition.of(
                    ColumnDefinition.ofString("PC1").withPartitioning(),
                    ColumnDefinition.ofString("PC2").withPartitioning(),
                    ColumnDefinition.ofLong("numbers"));
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(newTableDefinition)
                    .setFileLayout(ParquetInstructions.ParquetFileLayout.METADATA_PARTITIONED)
                    .build();
            final Table fromDiskWithNewDefinition = readTable(parentDir.getPath(), readInstructions);
            assertEquals(newTableDefinition, fromDiskWithNewDefinition.getDefinition());
            assertTableEquals(inputData.select("PC1", "PC2", "numbers").sort("PC1", "PC2"),
                    fromDiskWithNewDefinition.sort("PC1", "PC2"));
        }

        {
            // Remove another column
            final TableDefinition newTableDefinition = TableDefinition.of(
                    ColumnDefinition.ofString("PC1").withPartitioning(),
                    ColumnDefinition.ofString("PC2").withPartitioning(),
                    ColumnDefinition.ofChar("characters"));
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(newTableDefinition)
                    .setFileLayout(ParquetInstructions.ParquetFileLayout.METADATA_PARTITIONED)
                    .build();
            final Table fromDiskWithNewDefinition = readTable(parentDir.getPath(), readInstructions);
            assertEquals(newTableDefinition, fromDiskWithNewDefinition.getDefinition());
            assertTableEquals(inputData.select("PC1", "PC2", "characters").sort("PC1", "PC2"),
                    fromDiskWithNewDefinition.sort("PC1", "PC2"));
        }

        {
            // Remove a partitioning column
            final TableDefinition newTableDefinition = TableDefinition.of(
                    ColumnDefinition.ofString("PC1").withPartitioning(),
                    ColumnDefinition.ofChar("characters"),
                    ColumnDefinition.ofLong("numbers"));
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(newTableDefinition)
                    .setFileLayout(ParquetInstructions.ParquetFileLayout.METADATA_PARTITIONED)
                    .build();
            try {
                readTable(parentDir.getPath(), readInstructions);
                fail("Exception expected because of missing partitioning column in table definition");
            } catch (final RuntimeException expected) {
            }
        }

        {
            // Add an extra column
            final TableDefinition newTableDefinition = TableDefinition.of(
                    ColumnDefinition.ofString("PC1").withPartitioning(),
                    ColumnDefinition.ofString("PC2").withPartitioning(),
                    ColumnDefinition.ofLong("numbers"),
                    ColumnDefinition.ofChar("characters"),
                    ColumnDefinition.ofInt("extraColumn"));
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(newTableDefinition)
                    .setFileLayout(ParquetInstructions.ParquetFileLayout.METADATA_PARTITIONED)
                    .build();
            final Table fromDiskWithNewDefinition = readTable(parentDir.getPath(), readInstructions);
            assertEquals(newTableDefinition, fromDiskWithNewDefinition.getDefinition());
            assertTableEquals(inputData.update("extraColumn = (int) null").sort("PC1", "PC2"),
                    fromDiskWithNewDefinition.sort("PC1", "PC2"));
        }

        {
            // Reorder partitioning and non-partitioning columns
            final TableDefinition newTableDefinition = TableDefinition.of(
                    ColumnDefinition.ofString("PC2").withPartitioning(),
                    ColumnDefinition.ofString("PC1").withPartitioning(),
                    ColumnDefinition.ofChar("characters"),
                    ColumnDefinition.ofLong("numbers"));
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(newTableDefinition)
                    .setFileLayout(ParquetInstructions.ParquetFileLayout.METADATA_PARTITIONED)
                    .build();
            final Table fromDiskWithNewDefinition = readTable(parentDir.getPath(), readInstructions);
            assertEquals(newTableDefinition, fromDiskWithNewDefinition.getDefinition());
            assertTableEquals(inputData.select("PC2", "PC1", "characters", "numbers").sort("PC2", "PC1"),
                    fromDiskWithNewDefinition.sort("PC2", "PC1"));
        }

        {
            // Rename partitioning and non partitioning columns
            final TableDefinition newTableDefinition = TableDefinition.of(
                    ColumnDefinition.ofString("PartCol1").withPartitioning(),
                    ColumnDefinition.ofString("PartCol2").withPartitioning(),
                    ColumnDefinition.ofLong("nums"),
                    ColumnDefinition.ofChar("chars"));
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setTableDefinition(newTableDefinition)
                    .setFileLayout(ParquetInstructions.ParquetFileLayout.METADATA_PARTITIONED)
                    .addColumnNameMapping("PC1", "PartCol1")
                    .addColumnNameMapping("PC2", "PartCol2")
                    .addColumnNameMapping("numbers", "nums")
                    .addColumnNameMapping("characters", "chars")
                    .build();
            final Table fromDiskWithNewDefinition = readTable(parentDir.getPath(), readInstructions);
            assertEquals(newTableDefinition, fromDiskWithNewDefinition.getDefinition());
            assertTableEquals(
                    inputData.sort("PC1", "PC2"),
                    fromDiskWithNewDefinition
                            .renameColumns("PC1 = PartCol1", "PC2 = PartCol2", "numbers = nums", "characters = chars")
                            .sort("PC1", "PC2"));
        }
    }

    @Test
    public void metadataPartitionedDataWithCustomTableDefinition2() throws IOException {
        final Table inputData = TableTools.emptyTable(100)
                .update("PC1 = (ii%2 == 0) ? `Apple` : `Ball`",
                        "PC2 = (ii%3 == 0) ? `Pen` : `Pencil`",
                        "numbers = ii",
                        "characters = (char) (65 + (ii % 23))");
        final TableDefinition tableDefinition = TableDefinition.of(
                ColumnDefinition.ofString("PC1").withPartitioning(),
                ColumnDefinition.ofString("PC2").withPartitioning(),
                ColumnDefinition.ofLong("numbers"),
                ColumnDefinition.ofChar("characters"));
        final File parentDir = new File(rootFile, "metadataPartitionedDataWithCustomTableDefinition");
        final ParquetInstructions writeInstructions = ParquetInstructions.builder()
                .setGenerateMetadataFiles(true)
                .setTableDefinition(tableDefinition)
                .build();
        writeKeyValuePartitionedTable(inputData, parentDir.getAbsolutePath(), writeInstructions);

        // Replace files in directory with empty files and read the data
        final File dir = new File(parentDir, "PC1=Apple" + File.separator + "PC2=Pen");
        final String[] dataFileList = dir.list();
        assertNotNull(dataFileList);
        for (final String dataFile : dataFileList) {
            final File file = new File(dir, dataFile);
            assertTrue(file.delete());
            assertTrue(file.createNewFile());
        }

        {
            // Read as metadata partitioned
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setFileLayout(ParquetInstructions.ParquetFileLayout.METADATA_PARTITIONED)
                    .build();
            final Table fromDiskAfterDelete = readTable(parentDir.getPath(), readInstructions);

            // Make sure the definition is correct, and we can read the size as well as data for the remaining
            // partitions
            assertEquals(tableDefinition, fromDiskAfterDelete.getDefinition());
            assertEquals(inputData.size(), fromDiskAfterDelete.size());
            assertTableEquals(
                    inputData.where("PC1 == `Ball` && PC2 == `Pencil`").sort("PC1", "PC2"),
                    fromDiskAfterDelete.where("PC1 == `Ball` && PC2 == `Pencil`").sort("PC1", "PC2"));
        }

        {
            // Read as key-value partitioned
            final ParquetInstructions readInstructions = ParquetInstructions.builder()
                    .setFileLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED)
                    .build();
            final Table fromDiskAfterDelete = readTable(parentDir.getPath(), readInstructions);

            // The definition should be the same as the original table definition, but it won't be able to compute the
            // size
            assertEquals(tableDefinition, fromDiskAfterDelete.getDefinition());
            try {
                fromDiskAfterDelete.size();
                fail("Exception expected because one of the partitions is not a valid parquet file");
            } catch (final RuntimeException expected) {
            }
        }
    }

    @Test
    public void testVectorColumns() {
        final Table table = getTableFlat(20000, true, false);
        // Take a groupBy to create vector columns containing null values
        Table vectorTable = table.groupBy().select();

        final File dest = new File(rootFile + File.separator + "testVectorColumns.parquet");
        writeReadTableTest(vectorTable, dest);

        // Take a join with empty table to repeat the same row multiple times
        vectorTable = vectorTable.join(TableTools.emptyTable(100)).select();
        writeReadTableTest(vectorTable, dest);

        // Convert the table from vector to array column
        final Table arrayTable = vectorTable.updateView(vectorTable.getDefinition()
                .getColumnStream()
                .map(ColumnDefinition::getName)
                .map(name -> name + " = " + name + ".toArray()")
                .toArray(String[]::new));
        writeReadTableTest(arrayTable, dest);

        // Enforce a smaller page size to overflow the page
        final ParquetInstructions writeInstructions = new ParquetInstructions.Builder()
                .setTargetPageSize(ParquetInstructions.MIN_TARGET_PAGE_SIZE)
                .build();
        writeReadTableTest(arrayTable, dest, writeInstructions);
        writeReadTableTest(vectorTable, dest, writeInstructions);
    }

    private static Table arrayToVectorTable(final Table table) {
        final TableDefinition tableDefinition = table.getDefinition();
        final Collection<SelectColumn> arrayToVectorFormulas = new ArrayList<>();
        for (final ColumnDefinition<?> columnDefinition : tableDefinition.getColumns()) {
            final String columnName = columnDefinition.getName();
            // noinspection unchecked
            final Class<Object> sourceDataType = (Class<Object>) columnDefinition.getDataType();
            if (!sourceDataType.isArray()) {
                continue;
            }
            final Class<?> componentType = Objects.requireNonNull(columnDefinition.getComponentType());
            final VectorFactory vectorFactory = VectorFactory.forElementType(componentType);
            final Class<? extends Vector<?>> destinationDataType = vectorFactory.vectorType();
            final Function<Object, Vector<?>> vectorWrapFunction = vectorFactory::vectorWrap;
            // noinspection unchecked,rawtypes
            arrayToVectorFormulas.add(new FunctionalColumn(
                    columnName, sourceDataType, columnName, destinationDataType, componentType, vectorWrapFunction));
        }
        return arrayToVectorFormulas.isEmpty() ? table : table.updateView(arrayToVectorFormulas);
    }

    @Test
    public void testArrayColumns() {
        ArrayList<String> columns =
                new ArrayList<>(Arrays.asList(
                        "someStringArrayColumn = new String[] {i % 10 == 0 ? null : (`` + (i % 101))}",
                        "someIntArrayColumn = new int[] {i % 10 == 0 ? null : i}",
                        "someLongArrayColumn = new long[] {i % 10 == 0 ? null : i}",
                        "someDoubleArrayColumn = new double[] {i % 10 == 0 ? null : i*1.1}",
                        "someFloatArrayColumn = new float[] {i % 10 == 0 ? null : (float)(i*1.1)}",
                        "someBoolArrayColumn = new Boolean[] {i % 3 == 0 ? true :i % 3 == 1 ? false : null}",
                        "someShorArrayColumn = new short[] {i % 10 == 0 ? null : (short)i}",
                        "someByteArrayColumn = new byte[] {i % 10 == 0 ? null : (byte)i}",
                        "someCharArrayColumn = new char[] {i % 10 == 0 ? null : (char)i}",
                        "someTimeArrayColumn = new Instant[] {i % 10 == 0 ? null : (Instant)DateTimeUtils.now() + i}",
                        "someBigDecimalArrayColumn = new java.math.BigDecimal[] {i % 10 == 0 ? null : java.math.BigDecimal.valueOf(ii).stripTrailingZeros()}",
                        "someBiArrayColumn = new java.math.BigInteger[] {i % 10 == 0 ? null : java.math.BigInteger.valueOf(i)}",
                        "someDateArrayColumn = new java.time.LocalDate[] {i % 10 == 0 ? null : java.time.LocalDate.ofEpochDay(i)}",
                        "someTimeArrayColumn = new java.time.LocalTime[] {i % 10 == 0 ? null : java.time.LocalTime.of(i%24, i%60, (i+10)%60)}",
                        "someDateTimeArrayColumn = new java.time.LocalDateTime[] {i % 10 == 0 ? null : java.time.LocalDateTime.of(2000+i%10, i%12+1, i%30+1, (i+4)%24, (i+5)%60, (i+6)%60, i)}",
                        "nullStringArrayColumn = new String[] {(String)null}",
                        "nullIntArrayColumn = new int[] {(int)null}",
                        "nullLongArrayColumn = new long[] {(long)null}",
                        "nullDoubleArrayColumn = new double[] {(double)null}",
                        "nullFloatArrayColumn = new float[] {(float)null}",
                        "nullBoolArrayColumn = new Boolean[] {(Boolean)null}",
                        "nullShorArrayColumn = new short[] {(short)null}",
                        "nullByteArrayColumn = new byte[] {(byte)null}",
                        "nullCharArrayColumn = new char[] {(char)null}",
                        "nullTimeArrayColumn = new Instant[] {(Instant)null}",
                        "nullBiColumn = new java.math.BigInteger[] {(java.math.BigInteger)null}",
                        "nullDateColumn = new java.time.LocalDate[] {(java.time.LocalDate)null}",
                        "nullTimeColumn = new java.time.LocalTime[] {(java.time.LocalTime)null}"));

        Table arrayTable = TableTools.emptyTable(10000).select(Selectable.from(columns));
        final File dest = new File(rootFile + File.separator + "testArrayColumns.parquet");
        writeReadTableTest(arrayTable, dest);

        // Convert array table to vector
        Table vectorTable = arrayToVectorTable(arrayTable);
        writeReadTableTest(vectorTable, dest);

        // Enforce a smaller dictionary size to overflow the dictionary and test plain encoding
        final ParquetInstructions writeInstructions = new ParquetInstructions.Builder()
                .setMaximumDictionarySize(20)
                .build();
        arrayTable = arrayTable.select("someStringArrayColumn", "nullStringArrayColumn");
        writeReadTableTest(arrayTable, dest, writeInstructions);

        // Make sure the column didn't use dictionary encoding
        ParquetMetadata metadata =
                new ParquetTableLocationKey(dest.toURI(), 0, null, ParquetInstructions.EMPTY).getMetadata();
        String firstColumnMetadata = metadata.getBlocks().get(0).getColumns().get(0).toString();
        assertTrue(firstColumnMetadata.contains("someStringArrayColumn")
                && !firstColumnMetadata.contains("RLE_DICTIONARY"));

        vectorTable = vectorTable.select("someStringArrayColumn", "nullStringArrayColumn");
        writeReadTableTest(vectorTable, dest, writeInstructions);

        // Make sure the column didn't use dictionary encoding
        metadata = new ParquetTableLocationKey(dest.toURI(), 0, null, ParquetInstructions.EMPTY).getMetadata();
        firstColumnMetadata = metadata.getBlocks().get(0).getColumns().get(0).toString();
        assertTrue(firstColumnMetadata.contains("someStringArrayColumn")
                && !firstColumnMetadata.contains("RLE_DICTIONARY"));
    }

    @Test
    public void stringDictionaryTest() {
        final int nullPos = -5;
        final int maxKeys = 10;
        final int maxDictSize = 100;
        final Statistics<?> stats = NullStatistics.INSTANCE;
        StringDictionary dict = new StringDictionary(maxKeys, maxDictSize, NullStatistics.INSTANCE, nullPos);
        assertEquals(0, dict.getKeyCount());
        assertEquals(nullPos, dict.add(null));

        final String[] keys = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J"};
        final Map<String, Integer> keyToPos = new HashMap<>();
        for (int ii = 0; ii <= 6 * keys.length; ii += 3) {
            final String key = keys[ii % keys.length];
            final int dictPos = dict.add(key);
            if (keyToPos.containsKey(key)) {
                assertEquals(keyToPos.get(key).intValue(), dictPos);
            } else {
                keyToPos.put(key, dictPos);
                assertEquals(dictPos, dict.getKeyCount() - 1);
            }
        }
        assertEquals(keys.length, dict.getKeyCount());
        assertEquals(keys.length, keyToPos.size());
        final Binary[] encodedKeys = dict.getEncodedKeys();
        for (int i = 0; i < keys.length; i++) {
            final String decodedKey = encodedKeys[i].toStringUsingUTF8();
            final int expectedPos = keyToPos.get(decodedKey);
            assertEquals(i, expectedPos);
        }
        assertEquals(nullPos, dict.add(null));
        try {
            dict.add("Never before seen key which should take us over the allowed dictionary size");
            TestCase.fail("Exception expected for exceeding dictionary size");
        } catch (DictionarySizeExceededException expected) {
        }
    }

    /**
     * Test if we can read/sort a parquet file with a null string column encoded as a dictionary.
     */
    @Test
    public void nullStringDictEncodingTest() {
        final int NUM_ROWS = 2048;
        {
            // The following file has a null string column encoded as a dictionary. We should be able to read/sort
            // it without any exceptions.
            final String path =
                    ParquetTableReadWriteTest.class.getResource("/ReferenceNullStringDictEncoded1.parquet").getFile();
            final Table expected = TableTools.emptyTable(NUM_ROWS).update("someLong = i",
                    "someString = (String)null");
            nullStringDictEncodingTestHelper(path, expected);
        }

        {
            // The following file has a string column with all values null except for the last one encoded as a
            // dictionary. We should be able to read/sort it without any exceptions.
            final String path =
                    ParquetTableReadWriteTest.class.getResource("/ReferenceNullStringDictEncoded2.parquet").getFile();
            final Table expected = TableTools.emptyTable(NUM_ROWS).update("someLong = i",
                    "someString = i == 2047 ? `Hello` : (String)null");
            nullStringDictEncodingTestHelper(path, expected);
        }
    }

    private static void nullStringDictEncodingTestHelper(final String path, final Table expected) {
        // Verify that the column uses dictionary encoding.
        final ParquetMetadata metadata =
                new ParquetTableLocationKey(new File(path).toURI(), 0, null, ParquetInstructions.EMPTY)
                        .getMetadata();
        final String strColumnMetadata = metadata.getBlocks().get(0).getColumns().get(1).toString();
        assertTrue(strColumnMetadata.contains("someString") && strColumnMetadata.contains("RLE_DICTIONARY"));

        // Sort and compare
        assertTableEquals(expected, readTable(path).sort("someString"));
        assertTableEquals(expected.sortDescending("someString"),
                readTable(path).sortDescending("someString"));
    }

    /**
     * Extension of {@link #nullStringDictEncodingTest()} test, for testing if we can read/sort a table with regions
     * having null string columns encoded as a dictionary.
     */
    @Test
    public void nullRegionDictionaryEncodingTest() throws IOException {
        // The following file has a null string column encoded as a dictionary.
        final File allNullsFile = new File(
                ParquetTableReadWriteTest.class.getResource("/ReferenceNullStringDictEncoded1.parquet").getFile());
        final Table allNullsTable = readTable(allNullsFile.toString()).select();
        final File allButOneNullFile = new File(
                ParquetTableReadWriteTest.class.getResource("/ReferenceNullStringDictEncoded2.parquet").getFile());
        final Table allButOneNullTable = readTable(allButOneNullFile.toString()).select();
        final File testDir = new File(rootFile, "nullRegionDictionaryEncodingTest");
        final File firstRegion = new File(testDir, "Region1.parquet");
        final File secondRegion = new File(testDir, "Region2.parquet");
        final File thirdRegion = new File(testDir, "Region3.parquet");
        final File fourthRegion = new File(testDir, "Region4.parquet");

        // The first test is for a table where all three regions have null values for the string column.
        nullRegionDictionaryEncodingTestHelper(
                allNullsFile, allNullsFile, allNullsFile,
                allNullsTable, allNullsTable, allNullsTable,
                firstRegion, secondRegion, thirdRegion);

        // The next test is for a table where the first-region has non-null values and the second and third have null
        // values for the string column.
        nullRegionDictionaryEncodingTestHelper(
                allButOneNullFile, allNullsFile, allNullsFile,
                allButOneNullTable, allNullsTable, allNullsTable,
                firstRegion, secondRegion, thirdRegion);

        // The next test is for a table where the second-region has non-null values, and the first and third have null
        // values for the string column.
        nullRegionDictionaryEncodingTestHelper(
                allNullsFile, allButOneNullFile, allNullsFile,
                allNullsTable, allButOneNullTable, allNullsTable,
                firstRegion, secondRegion, thirdRegion);

        // The next test is for a table where the third-region has non-null values, and the first two have null values
        // for the string column.
        nullRegionDictionaryEncodingTestHelper(
                allNullsFile, allNullsFile, allButOneNullFile,
                allNullsTable, allNullsTable, allButOneNullTable,
                firstRegion, secondRegion, thirdRegion);

        {
            // The next test is for a table where the first and region have null values and the second and fourth two
            // have null values for the string column.
            testDir.mkdir();
            Files.copy(allNullsFile.toPath(), firstRegion.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(allButOneNullFile.toPath(), secondRegion.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(allNullsFile.toPath(), thirdRegion.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(allButOneNullFile.toPath(), fourthRegion.toPath(), StandardCopyOption.REPLACE_EXISTING);
            final Table expected = merge(allNullsTable, allButOneNullTable, allNullsTable, allButOneNullTable);
            assertTableEquals(expected.sort("someString"),
                    readTable(testDir.toString()).sort("someString"));
            assertTableEquals(expected.sortDescending("someString"),
                    readTable(testDir.toString()).sortDescending("someString"));
            FileUtils.deleteRecursively(testDir);
        }
    }

    private static void nullRegionDictionaryEncodingTestHelper(
            final File firstSource, final File secondSource, final File thirdSource,
            final Table firstTable, final Table secondTable, final Table thirdTable,
            final File firstRegion, final File secondRegion, final File thirdRegion) throws IOException {
        final File testDir = new File(rootFile, "nullRegionDictionaryEncodingTest");
        testDir.mkdir();
        Files.copy(firstSource.toPath(), firstRegion.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(secondSource.toPath(), secondRegion.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(thirdSource.toPath(), thirdRegion.toPath(), StandardCopyOption.REPLACE_EXISTING);
        final Table expected = merge(firstTable, secondTable, thirdTable);
        assertTableEquals(expected.sort("someString"),
                readTable(testDir.toString()).sort("someString"));
        assertTableEquals(expected.sortDescending("someString"),
                readTable(testDir.toString()).sortDescending("someString"));
        FileUtils.deleteRecursively(testDir);
    }

    /**
     * Encoding bigDecimal is tricky -- the writer will try to pick the precision and scale automatically. Because of
     * that tableTools.assertTableEquals will fail because, even though the numbers are identical, the representation
     * may not be, so we have to coerce the expected values to the same precision and scale value. We know how it should
     * be doing it, so we can use the same pattern of encoding/decoding with the codec.
     *
     * @param toFix
     * @return
     */
    private Table maybeFixBigDecimal(Table toFix) {
        final BigDecimalUtils.PrecisionAndScale pas = BigDecimalUtils.computePrecisionAndScale(toFix, "bdColumn");
        final BigDecimalParquetBytesCodec codec = new BigDecimalParquetBytesCodec(pas.precision, pas.scale);

        ExecutionContext.getContext()
                .getQueryScope()
                .putParam("__codec", codec);
        return toFix
                .updateView("bdColE = __codec.encode(bdColumn)", "bdColumn=__codec.decode(bdColE, 0, bdColE.length)")
                .dropColumns("bdColE");
    }

    private static Table readParquetFileFromGitLFS(final File dest) {
        try {
            return readTable(dest.getPath(), EMPTY);
        } catch (final RuntimeException e) {
            if (e.getCause() instanceof InvalidParquetFileException) {
                final String InvalidParquetFileErrorMsgString = "Invalid parquet file detected, please ensure the " +
                        "file is fetched properly from Git LFS. Run commands 'git lfs install; git lfs pull' inside " +
                        "the repo to pull the files from LFS. Check cause of exception for more details.";
                throw new UncheckedDeephavenException(InvalidParquetFileErrorMsgString, e.getCause());
            }
            throw e;
        }
    }

    /**
     * Test if the current code can read the parquet data written by the old code. There is logic in
     * {@link ColumnChunkPageStore#create} that decides page store based on the version of the parquet file. The old
     * data is generated using following logic:
     *
     * <pre>
     * // Enforce a smaller page size to write multiple pages
     * final ParquetInstructions writeInstructions = new ParquetInstructions.Builder()
     *         .setTargetPageSize(ParquetInstructions.MIN_TARGET_PAGE_SIZE)
     *         .build();
     *
     * final Table table = getTableFlat(5000, true, false);
     * ParquetTools.writeTable(table, new File("ReferenceParquetData.parquet"), writeInstructions);
     *
     * Table vectorTable = table.groupBy().select();
     * vectorTable = vectorTable.join(TableTools.emptyTable(100)).select();
     * ParquetTools.writeTable(vectorTable, new File("ReferenceParquetVectorData.parquet"), writeInstructions);
     *
     * final Table arrayTable = vectorTable.updateView(vectorTable.getColumnSourceMap().keySet().stream()
     *         .map(name -> name + " = " + name + ".toArray()")
     *         .toArray(String[]::new));
     * ParquetTools.writeTable(arrayTable, new File("ReferenceParquetArrayData.parquet"), writeInstructions);
     * </pre>
     */
    @Test
    public void testReadOldParquetData() {
        String path = ParquetTableReadWriteTest.class.getResource("/ReferenceParquetData.parquet").getFile();
        readParquetFileFromGitLFS(new File(path)).select();
        final ParquetMetadata metadata =
                new ParquetTableLocationKey(new File(path).toURI(), 0, null, ParquetInstructions.EMPTY).getMetadata();
        assertTrue(metadata.getFileMetaData().getKeyValueMetaData().get("deephaven").contains("\"version\":\"0.4.0\""));

        path = ParquetTableReadWriteTest.class.getResource("/ReferenceParquetVectorData.parquet").getFile();
        readParquetFileFromGitLFS(new File(path)).select();

        path = ParquetTableReadWriteTest.class.getResource("/ReferenceParquetArrayData.parquet").getFile();
        readParquetFileFromGitLFS(new File(path)).select();
    }

    /**
     * The reference data is generated using:
     *
     * <pre>
     * df = pandas.DataFrame.from_records(
     *     data=[(-1, -1, -1), (2, 2, 2), (0, 0, 0), (5, 5, 5)],
     *     columns=['uint8Col', 'uint16Col',  'uint32Col']
     * )
     * df['uint8Col'] = df['uint8Col'].astype(np.uint8)
     * df['uint16Col'] = df['uint16Col'].astype(np.uint16)
     * df['uint32Col'] = df['uint32Col'].astype(np.uint32)
     *
     * # Add some nulls
     * df['uint8Col'][3] = df['uint16Col'][3] = df['uint32Col'][3] = None
     * schema = pyarrow.schema([
     *     pyarrow.field('uint8Col', pyarrow.uint8()),
     *     pyarrow.field('uint16Col', pyarrow.uint16()),
     *     pyarrow.field('uint32Col', pyarrow.uint32()),
     * ])
     * schema = schema.remove_metadata()
     * table = pyarrow.Table.from_pandas(df, schema).replace_schema_metadata()
     * writer = pyarrow.parquet.ParquetWriter('data_from_pyarrow.parquet', schema=schema)
     * writer.write_table(table)
     * </pre>
     */
    @Test
    public void testReadUintParquetData() {
        final String path = ParquetTableReadWriteTest.class.getResource("/ReferenceUintParquetData.parquet").getFile();
        final Table fromDisk = readParquetFileFromGitLFS(new File(path)).select();

        final ParquetMetadata metadata =
                new ParquetTableLocationKey(new File(path).toURI(), 0, null, ParquetInstructions.EMPTY).getMetadata();

        final MessageType expectedSchema = Types.buildMessage()
                .addFields(
                        optional(INT32).as(intType(8, false)).named("uint8Col"),
                        optional(INT32).as(intType(16, false)).named("uint16Col"),
                        optional(INT32).as(intType(32, false)).named("uint32Col"))
                .named("schema");
        assertEquals(expectedSchema, metadata.getFileMetaData().getSchema());

        final Table expected = newTable(
                charCol("uint8Col", (char) 255, (char) 2, (char) 0, NULL_CHAR),
                charCol("uint16Col", (char) 65535, (char) 2, (char) 0, NULL_CHAR),
                longCol("uint32Col", 4294967295L, 2L, 0L, NULL_LONG));
        assertTableEquals(expected, fromDisk);
    }

    @Test
    public void testVersionChecks() {
        assertFalse(ColumnChunkPageStore.hasCorrectVectorOffsetIndexes("0.0.0"));
        assertFalse(ColumnChunkPageStore.hasCorrectVectorOffsetIndexes("0.4.0"));
        assertTrue(ColumnChunkPageStore.hasCorrectVectorOffsetIndexes("0.3"));
        assertTrue(ColumnChunkPageStore.hasCorrectVectorOffsetIndexes("0.31.0"));
        assertTrue(ColumnChunkPageStore.hasCorrectVectorOffsetIndexes("0.31.1"));
        assertTrue(ColumnChunkPageStore.hasCorrectVectorOffsetIndexes("0.32.0"));
        assertTrue(ColumnChunkPageStore.hasCorrectVectorOffsetIndexes("1.3.0"));
        assertTrue(ColumnChunkPageStore.hasCorrectVectorOffsetIndexes("unknown"));
        assertTrue(ColumnChunkPageStore.hasCorrectVectorOffsetIndexes("0.31.0-SNAPSHOT"));
    }

    /**
     * Reference data is generated using the following code:
     *
     * <pre>
     *      num_rows = 100000
     *      dh_table = empty_table(num_rows).update(formulas=[
     *         "someStringColumn = i % 10 == 0?null:(`` + (i % 101))",
     *         ... # Same as in the test code
     *      ])
     *
     *      write(dh_table, "data_from_dh.parquet")
     *      pa_table = pyarrow.parquet.read_table("data_from_dh.parquet")
     *      pyarrow.parquet.write_table(pa_table, "ReferenceParquetV1PageData.parquet", data_page_version='1.0')
     *      pyarrow.parquet.write_table(pa_table, "ReferenceParquetV2PageData.parquet", data_page_version='2.0')
     * </pre>
     */
    @Test
    public void testReadParquetV2Pages() {
        final String pathV1 =
                ParquetTableReadWriteTest.class.getResource("/ReferenceParquetV1PageData.parquet").getFile();
        final Table fromDiskV1 = readParquetFileFromGitLFS(new File(pathV1));
        final String pathV2 =
                ParquetTableReadWriteTest.class.getResource("/ReferenceParquetV2PageData.parquet").getFile();
        final Table fromDiskV2 = readParquetFileFromGitLFS(new File(pathV2));
        assertTableEquals(fromDiskV1, fromDiskV2);

        final Table expected = emptyTable(100_000).update(
                "someStringColumn = i % 10 == 0?null:(`` + (i % 101))",
                "nonNullString = `` + (i % 60)",
                "nonNullPolyString = `` + (i % 600)",
                "someIntColumn = i",
                "someLongColumn = ii",
                "someDoubleColumn = i*1.1",
                "someFloatColumn = (float)(i*1.1)",
                "someBoolColumn = i % 3 == 0?true:i%3 == 1?false:null",
                "someShortColumn = (short)i",
                "someByteColumn = (byte)i",
                "someCharColumn = (char)i",
                "someKey = `` + (int)(i /100)",
                "nullKey = i < -1?`123`:null",
                "nullIntColumn = (int)null",
                "nullLongColumn = (long)null",
                "nullDoubleColumn = (double)null",
                "nullFloatColumn = (float)null",
                "nullBoolColumn = (Boolean)null",
                "nullShortColumn = (short)null",
                "nullByteColumn = (byte)null",
                "nullCharColumn = (char)null",
                "nullTime = (Instant)null",
                "nullString = (String)null",
                "someStringArrayColumn = new String[] {i % 10 == 0 ? null : (`` + (i % 101))}",
                "someIntArrayColumn = new int[] {i % 10 == 0 ? null : i}",
                "someLongArrayColumn = new long[] {i % 10 == 0 ? null : i}",
                "someDoubleArrayColumn = new double[] {i % 10 == 0 ? null : i*1.1}",
                "someFloatArrayColumn = new float[] {i % 10 == 0 ? null : (float)(i*1.1)}",
                "someBoolArrayColumn = new Boolean[] {i % 3 == 0 ? true :i % 3 == 1 ? false : null}",
                "someShorArrayColumn = new short[] {i % 10 == 0 ? null : (short)i}",
                "someByteArrayColumn = new byte[] {i % 10 == 0 ? null : (byte)i}",
                "someCharArrayColumn = new char[] {i % 10 == 0 ? null : (char)i}",
                "someTimeArrayColumn = new Instant[] {i % 10 == 0 ? null : java.time.Instant.ofEpochSecond(ii)}",
                "nullStringArrayColumn = new String[] {(String)null}",
                "nullIntArrayColumn = new int[] {(int)null}",
                "nullLongArrayColumn = new long[] {(long)null}",
                "nullDoubleArrayColumn = new double[] {(double)null}",
                "nullFloatArrayColumn = new float[] {(float)null}",
                "nullBoolArrayColumn = new Boolean[] {(Boolean)null}",
                "nullShorArrayColumn = new short[] {(short)null}",
                "nullByteArrayColumn = new byte[] {(byte)null}",
                "nullCharArrayColumn = new char[] {(char)null}",
                "nullTimeArrayColumn = new Instant[] {(Instant)null}");
        assertTableEquals(expected, fromDiskV2);
    }

    /**
     * Test if the parquet reading code can read pre-generated parquet files which have different number of rows in each
     * page. Following is how these files are generated.
     *
     * <pre>
     * Table arrayTable = TableTools.emptyTable(100).update(
     *         "intArrays = java.util.stream.IntStream.range(0, i).toArray()").reverse();
     * File dest = new File(rootFile, "ReferenceParquetFileWithDifferentPageSizes1.parquet");
     * final ParquetInstructions writeInstructions = new ParquetInstructions.Builder()
     *         .setTargetPageSize(ParquetInstructions.MIN_TARGET_PAGE_SIZE)
     *         .build();
     * ParquetTools.writeTable(arrayTable, dest, writeInstructions);
     *
     * arrayTable = TableTools.emptyTable(1000).update(
     *         "intArrays = (i <= 900) ? java.util.stream.IntStream.range(i, i+50).toArray() : " +
     *                 "java.util.stream.IntStream.range(i, i+2).toArray()");
     * dest = new File(rootFile, "ReferenceParquetFileWithDifferentPageSizes2.parquet");
     * ParquetTools.writeTable(arrayTable, dest, writeInstructions);
     * </pre>
     */
    @Test
    public void testReadingParquetFilesWithDifferentPageSizes() {
        Table expected = TableTools.emptyTable(100).update(
                "intArrays = java.util.stream.IntStream.range(0, i).toArray()").reverse();
        String path = ParquetTableReadWriteTest.class
                .getResource("/ReferenceParquetFileWithDifferentPageSizes1.parquet").getFile();
        Table fromDisk = readParquetFileFromGitLFS(new File(path));
        assertTableEquals(expected, fromDisk);

        path = ParquetTableReadWriteTest.class
                .getResource("/ReferenceParquetFileWithDifferentPageSizes2.parquet").getFile();
        fromDisk = readParquetFileFromGitLFS(new File(path));

        // Access something on the last page to make sure we can read it
        final int[] data = (int[]) fromDisk.getColumnSource("intArrays").get(998);
        assertNotNull(data);
        assertEquals(2, data.length);
        assertEquals(998, data[0]);
        assertEquals(999, data[1]);

        expected = TableTools.emptyTable(1000).update(
                "intArrays = (i <= 900) ? java.util.stream.IntStream.range(i, i+50).toArray() : " +
                        "java.util.stream.IntStream.range(i, i+2).toArray()");
        assertTableEquals(expected, fromDisk);
    }

    @Test
    public void testOnWriteCallback() {
        // Write a few tables to disk and check the sizes and number of rows in the files
        final Table table1 = TableTools.emptyTable(100_000).update(
                "someIntColumn = i * 200",
                "someLongColumn = ii * 500");
        final File dest1 = new File(rootFile, "table1.parquet");
        final Table table2 = TableTools.emptyTable(2000).update(
                "someIntColumn = i",
                "someLongColumn = ii");
        final File dest2 = new File(rootFile, "table2.parquet");

        final List<CompletedParquetWrite> parquetFilesWritten = new ArrayList<>();
        final ParquetInstructions.OnWriteCompleted onWriteCompleted = parquetFilesWritten::add;
        final ParquetInstructions writeInstructions = new ParquetInstructions.Builder()
                .setOnWriteCompleted(onWriteCompleted)
                .build();
        ParquetTools.writeTables(new Table[] {table1, table2},
                new String[] {dest1.getPath(), dest2.getPath()}, writeInstructions);

        assertEquals(2, parquetFilesWritten.size());
        // Check the destination URIs
        assertEquals(dest1.toURI(), parquetFilesWritten.get(0).destination());
        assertEquals(dest2.toURI(), parquetFilesWritten.get(1).destination());

        // Check the number of rows
        assertEquals(100_000, parquetFilesWritten.get(0).numRows());
        assertEquals(2000, parquetFilesWritten.get(1).numRows());

        // Check the size of the files
        assertEquals(dest1.length(), parquetFilesWritten.get(0).numBytes());
        assertEquals(dest2.length(), parquetFilesWritten.get(1).numBytes());
    }

    // Following is used for testing both writing APIs for parquet tables
    private interface TestParquetTableWriter {
        void writeTable(final Table table, final File destFile);
    }

    private static final TestParquetTableWriter SINGLE_WRITER =
            (sourceTable, destFile) -> writeTable(sourceTable, destFile.getPath());
    private static final TestParquetTableWriter MULTI_WRITER =
            (table, destFile) -> writeTables(new Table[] {table}, new String[] {destFile.getPath()},
                    ParquetInstructions.EMPTY);

    /**
     * Verify that the parent directory contains the expected parquet files and index files in the right directory
     * structure.
     */
    private static void verifyFilesInDir(final File parentDir, final String[] expectedDataFiles,
            @Nullable final Map<String, String[]> indexingColumnToFileMap) {
        final List<String> filesInParentDir = Arrays.asList(parentDir.list());
        for (String expectedFile : expectedDataFiles) {
            assertTrue(filesInParentDir.contains(expectedFile));
        }
        if (indexingColumnToFileMap == null) {
            assertEquals(filesInParentDir.size(), expectedDataFiles.length);
            return;
        }
        assertEquals(filesInParentDir.size(), expectedDataFiles.length + 1);
        final File metadataDir = new File(parentDir, ".dh_metadata");
        assertTrue(metadataDir.exists() && metadataDir.isDirectory() && metadataDir.list().length == 1);
        final File indexesDir = new File(metadataDir, "indexes");
        assertTrue(indexesDir.exists() && indexesDir.isDirectory()
                && indexesDir.list().length == indexingColumnToFileMap.size());
        for (Map.Entry<String, String[]> set : indexingColumnToFileMap.entrySet()) {
            final String indexColName = set.getKey();
            final String[] indexFilePaths = set.getValue();
            final File indexColDir = new File(indexesDir, indexColName);
            assertTrue(indexColDir.exists() && indexColDir.isDirectory()
                    && indexColDir.list().length == indexFilePaths.length);
            final List<String> filesInIndexColDir = Arrays.asList(indexColDir.list());
            for (final String indexFilePath : indexFilePaths) {
                final File indexFile = new File(parentDir, indexFilePath);
                assertTrue(indexFile.exists() && indexFile.isFile() && indexFile.length() > 0 &&
                        filesInIndexColDir.contains(indexFile.getName()));
            }
        }
    }

    @Test
    public void readFromDirTest() {
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();
        final Table someTable = TableTools.emptyTable(5).update("A=(int)i");
        final File firstPartition = new File(parentDir, "X=A");
        final File firstDataFile = new File(firstPartition, "data.parquet");
        writeTable(someTable, firstDataFile.getPath());
        final File secondPartition = new File(parentDir, "X=B");
        final File secondDataFile = new File(secondPartition, "data.parquet");
        writeTable(someTable, secondDataFile.getPath());

        final Table expected = readTable(parentDir.getPath(),
                ParquetInstructions.EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));

        String filePath = parentDir.getAbsolutePath();
        Table fromDisk = ParquetTools.readTable(filePath);
        assertTableEquals(expected, fromDisk);

        // Read with a trailing slash
        assertFalse(filePath.endsWith("/"));
        filePath = filePath + "/";
        fromDisk = ParquetTools.readTable(filePath);
        assertTableEquals(expected, fromDisk);
    }

    @Test
    public void readPartitionedDataGeneratedOnWindows() {
        final String path = ParquetTableReadWriteTest.class
                .getResource("/referencePartitionedDataFromWindows").getFile();
        final Table partitionedDataFromWindows = readParquetFileFromGitLFS(new File(path)).select();
        final Table expected = TableTools.newTable(
                longCol("year", 2019, 2020, 2021, 2021, 2022, 2022),
                longCol("n_legs", 5, 2, 4, 100, 2, 4),
                stringCol("animal", "Brittle stars", "Flamingo", "Dog", "Centipede", "Parrot", "Horse"));
        assertTableEquals(expected, partitionedDataFromWindows.sort("year"));
    }

    /**
     * These are tests for writing a table to a parquet file and making sure there are no unnecessary files left in the
     * directory after we finish writing.
     */
    @Test
    public void basicWriteTests() {
        basicWriteTestsImpl(SINGLE_WRITER);
        basicWriteTestsImpl(MULTI_WRITER);
    }

    private static void basicWriteTestsImpl(TestParquetTableWriter writer) {
        // Create an empty parent directory
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();
        assertTrue(parentDir.exists() && parentDir.isDirectory() && parentDir.list().length == 0);

        // There should be just one file in the directory on a successful write and no temporary files
        final Table tableToSave = TableTools.emptyTable(5).update("A=(int)i", "B=(long)i", "C=(double)i");
        final String filename = "basicWriteTests.parquet";
        final File destFile = new File(parentDir, filename);
        writer.writeTable(tableToSave, destFile);
        verifyFilesInDir(parentDir, new String[] {filename}, null);

        checkSingleTable(tableToSave, destFile);

        // This write should fail
        final Table badTable = TableTools.emptyTable(5)
                .updateView("InputString = ii % 2 == 0 ? Long.toString(ii) : null", "A=InputString.charAt(0)");
        DataIndexer.getOrCreateDataIndex(badTable, "InputString");
        try {
            writer.writeTable(badTable, destFile);
            TestCase.fail("Exception expected for invalid formula");
        } catch (UncheckedDeephavenException e) {
            assertTrue(e.getCause() instanceof FormulaEvaluationException);
        }

        // Make sure that original file is preserved and no temporary files
        verifyFilesInDir(parentDir, new String[] {filename}, null);
        checkSingleTable(tableToSave, destFile);

        // Write a new table successfully at the same path
        final Table newTableToSave = TableTools.emptyTable(5).update("A=(int)i");
        writer.writeTable(newTableToSave, destFile);
        verifyFilesInDir(parentDir, new String[] {filename}, null);
        checkSingleTable(newTableToSave, destFile);
        FileUtils.deleteRecursively(parentDir);
    }

    @Test
    public void basicWriteAndReadFromFileURITests() {
        final Table tableToSave = TableTools.emptyTable(5).update("A=(int)i", "B=(long)i", "C=(double)i");
        final String filename = "basicWriteTests.parquet";
        final File destFile = new File(rootFile, filename);
        final String absolutePath = destFile.getAbsolutePath();
        final URI fileURI = convertToURI(destFile, false);
        ParquetTools.writeTable(tableToSave, absolutePath);

        // Read from file URI
        final Table fromDisk = ParquetTools.readTable(fileURI.toString());
        assertTableEquals(tableToSave, fromDisk);

        // Read from "file://" + absolutePath
        final Table fromDisk2 = ParquetTools.readTable("file://" + absolutePath);
        assertTableEquals(tableToSave, fromDisk2);

        // Read from absolutePath
        final Table fromDisk3 = ParquetTools.readTable(absolutePath);
        assertTableEquals(tableToSave, fromDisk3);

        // Read from relative path
        final String relativePath = rootFile.getName() + "/" + filename;
        final Table fromDisk4 = ParquetTools.readTable(relativePath);
        assertTableEquals(tableToSave, fromDisk4);

        // Read from unsupported URI
        try {
            ParquetTools.readTable("https://" + absolutePath);
            TestCase.fail("Exception expected for invalid scheme");
        } catch (final RuntimeException e) {
            assertTrue(e instanceof UnsupportedOperationException);
        }

        // Read from absolute path with additional "/" in the path
        final String additionalSlashPath = rootFile.getAbsolutePath() + "/////" + filename;
        final Table fromDisk5 = ParquetTools.readTable(additionalSlashPath);
        assertTableEquals(tableToSave, fromDisk5);

        // Read from URI with additional "/" in the path
        final String additionalSlashURI = "file:////" + additionalSlashPath;
        final Table fromDisk6 = ParquetTools.readTable(additionalSlashURI);
        assertTableEquals(tableToSave, fromDisk6);
    }

    /**
     * These are tests for writing multiple parquet tables in a single call.
     */
    @Test
    public void writeMultiTableBasicTest() {
        // Create an empty parent directory
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();

        // Write two tables to parquet file and read them back
        final Table firstTable = TableTools.emptyTable(5)
                .updateView("InputString = Long.toString(ii)", "A=InputString.charAt(0)");
        final String firstFilename = "firstTable.parquet";
        final File firstDestFile = new File(parentDir, firstFilename);

        final Table secondTable = TableTools.emptyTable(5)
                .updateView("InputString = Long.toString(ii*5)", "A=InputString.charAt(0)");
        final String secondFilename = "secondTable.parquet";
        final File secondDestFile = new File(parentDir, secondFilename);

        final Table[] tablesToSave = new Table[] {firstTable, secondTable};
        final String[] destinations = new String[] {firstDestFile.getPath(), secondDestFile.getPath()};

        writeTables(tablesToSave, destinations,
                ParquetInstructions.EMPTY.withTableDefinition(firstTable.getDefinition()));

        verifyFilesInDir(parentDir, new String[] {firstFilename, secondFilename}, null);
        checkSingleTable(firstTable, firstDestFile);
        checkSingleTable(secondTable, secondDestFile);
    }

    /**
     * These are tests for writing multiple parquet tables such that there is an exception in the second write.
     */
    @Test
    public void writeMultiTableExceptionTest() {
        // Create an empty parent directory
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();

        // Write two tables to parquet file
        final Table firstTable = TableTools.emptyTable(5)
                .updateView("InputString = Long.toString(ii)", "A=InputString.charAt(0)");
        DataIndexer.getOrCreateDataIndex(firstTable, "InputString");
        final File firstDestFile = new File(parentDir, "firstTable.parquet");

        final Table secondTable = TableTools.emptyTable(5)
                .updateView("InputString = ii % 2 == 0 ? Long.toString(ii*5) : null", "A=InputString.charAt(0)");
        final File secondDestFile = new File(parentDir, "secondTable.parquet");

        final Table[] tablesToSave = new Table[] {firstTable, secondTable};
        final String[] destinations = new String[] {firstDestFile.getPath(), secondDestFile.getPath()};

        // This write should fail because of the null value in the second table
        try {
            writeTables(tablesToSave, destinations,
                    ParquetInstructions.EMPTY.withTableDefinition(firstTable.getDefinition()));
            TestCase.fail("Exception expected for invalid formula");
        } catch (UncheckedDeephavenException e) {
            assertTrue(e.getCause() instanceof FormulaEvaluationException);
        }

        // All files should be deleted even though first table would be written successfully
        assertEquals(0, parentDir.list().length);
    }

    @Test
    public void writeMultiTableDefinitionTest() {
        // Create an empty parent directory
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();

        final int numRows = 5;
        final Table firstTable = TableTools.emptyTable(numRows)
                .updateView("A = Long.toString(ii)", "B=(long)ii");
        final File firstDestFile = new File(parentDir, "firstTable.parquet");

        final Table secondTable = TableTools.emptyTable(numRows)
                .updateView("A = Long.toString(ii*5)", "B=(long)(ii*5)");
        final File secondDestFile = new File(parentDir, "secondTable.parquet");

        final Table[] tablesToSave = new Table[] {firstTable, secondTable};
        final String[] destinations = new String[] {firstDestFile.getPath(), secondDestFile.getPath()};

        try {
            writeTables(tablesToSave, new String[] {firstDestFile.getPath()},
                    ParquetInstructions.EMPTY.withTableDefinition(firstTable.getDefinition()));
            TestCase.fail("Exception expected becuase of mismatch in number of tables and destinations");
        } catch (final IllegalArgumentException expected) {
        }

        // Writing a single table without definition should work
        writeTables(new Table[] {firstTable}, new String[] {firstDestFile.getPath()}, ParquetInstructions.EMPTY);
        checkSingleTable(firstTable, firstDestFile);
        assertTrue(firstDestFile.delete());

        // Writing a single table with definition should work
        writeTables(new Table[] {firstTable}, new String[] {firstDestFile.getPath()},
                ParquetInstructions.EMPTY.withTableDefinition(firstTable.view("A").getDefinition()));
        checkSingleTable(firstTable.view("A"), firstDestFile);
        assertTrue(firstDestFile.delete());

        // Writing multiple tables which have the same definition should work
        writeTables(tablesToSave, destinations, ParquetInstructions.EMPTY);
        checkSingleTable(firstTable, firstDestFile);
        checkSingleTable(secondTable, secondDestFile);
        assertTrue(firstDestFile.delete());
        assertTrue(secondDestFile.delete());

        // Writing multiple tables which have the different definition should not work
        final Table thirdTable = TableTools.emptyTable(numRows)
                .updateView("A = Long.toString(ii*10)", "B=(int)(ii*10)");
        final File thirdDestFile = new File(parentDir, "thirdTable.parquet");
        try {
            writeTables(new Table[] {firstTable, thirdTable},
                    new String[] {firstDestFile.getPath(), thirdDestFile.getPath()},
                    ParquetInstructions.EMPTY);
            TestCase.fail("Exception expected becuase of mismatch in table definitions");
        } catch (final IllegalArgumentException expected) {
        }

        // Taking view with same definition should work
        writeTables(new Table[] {firstTable.view("A"), thirdTable.view("A")},
                new String[] {firstDestFile.getPath(), thirdDestFile.getPath()}, ParquetInstructions.EMPTY);
        checkSingleTable(firstTable.view("A"), firstDestFile);
        checkSingleTable(thirdTable.view("A"), thirdDestFile);
        assertTrue(firstDestFile.delete());
        assertTrue(thirdDestFile.delete());

        // Providing a definition should work
        writeTables(new Table[] {firstTable, thirdTable},
                new String[] {firstDestFile.getPath(), thirdDestFile.getPath()},
                ParquetInstructions.EMPTY.withTableDefinition(firstTable.view("A").getDefinition()));
        checkSingleTable(firstTable.view("A"), firstDestFile);
        checkSingleTable(thirdTable.view("A"), thirdDestFile);
    }

    @Test
    public void writingParquetFilesWithSpacesInName() {
        final String parentDirName = "tempDir";
        final String tableNameWithSpaces = "table name with spaces.parquet";
        final Table table = TableTools.emptyTable(5)
                .updateView("InputString = Long.toString(ii)", "A=InputString.charAt(0)");
        writingParquetFilesWithSpacesInNameHelper(table, parentDirName, tableNameWithSpaces);

        // Same test but for tables with index data
        final int[] data = new int[500 * 4];
        for (int i = 0; i < data.length; i++) {
            data[i] = i / 4;
        }
        final Table indexedTable = newTable(TableTools.intCol("vvv", data));
        DataIndexer.getOrCreateDataIndex(indexedTable, "vvv");
        writingParquetFilesWithSpacesInNameHelper(indexedTable, parentDirName, tableNameWithSpaces);
    }

    private void writingParquetFilesWithSpacesInNameHelper(final Table table, final String parentDirName,
            final String parquetFileName) {
        final File parentDir = new File(rootFile, parentDirName);
        parentDir.mkdir();
        final File dest = new File(parentDir, parquetFileName);

        writeTable(table, dest.getPath());
        Table fromDisk = readTable(dest.getPath(),
                ParquetInstructions.EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE));
        assertTableEquals(table, fromDisk);
        FileUtils.deleteRecursively(parentDir);

        final String destAbsolutePathStr = dest.getAbsolutePath();
        ParquetTools.writeTable(table, destAbsolutePathStr);
        fromDisk = readTable(destAbsolutePathStr,
                ParquetInstructions.EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE));
        assertTableEquals(table, fromDisk);
        FileUtils.deleteRecursively(parentDir);

        final String destRelativePathStr = rootFile.getName() + "/" + parentDirName + "/" + parquetFileName;
        ParquetTools.writeTable(table, destRelativePathStr);
        fromDisk = readTable(destRelativePathStr,
                ParquetInstructions.EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE));
        assertTableEquals(table, fromDisk);
        FileUtils.deleteRecursively(parentDir);
    }


    /**
     * These are tests for writing to a table with indexes to a parquet file and making sure there are no unnecessary
     * files left in the directory after we finish writing.
     */
    @Test
    public void indexedColumnsBasicWriteTests() {
        indexedColumnsBasicWriteTestsImpl(SINGLE_WRITER);
        indexedColumnsBasicWriteTestsImpl(MULTI_WRITER);
    }

    private void indexedColumnsBasicWriteTestsImpl(TestParquetTableWriter writer) {
        // Create an empty parent directory
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();
        assertTrue(parentDir.exists() && parentDir.isDirectory() && parentDir.list().length == 0);

        final int[] data = new int[500 * 4];
        for (int i = 0; i < data.length; i++) {
            data[i] = i / 4;
        }
        final Table tableToSave = TableTools.newTable(TableTools.intCol("vvv", data));
        DataIndexer.getOrCreateDataIndex(tableToSave, "vvv");

        // For a completed write, there should be two parquet files in the directory, the table data and the index
        // data
        final String destFilename = "indexedWriteTests.parquet";
        final File destFile = new File(parentDir, destFilename);
        writer.writeTable(tableToSave, destFile);
        String vvvIndexFilePath = ".dh_metadata/indexes/vvv/index_vvv_indexedWriteTests.parquet";
        verifyFilesInDir(parentDir, new String[] {destFilename}, Map.of("vvv", new String[] {vvvIndexFilePath}));

        checkSingleTable(tableToSave, destFile);

        // Verify that the key-value metadata in the file has the correct name
        ParquetTableLocationKey tableLocationKey =
                new ParquetTableLocationKey(destFile.toURI(), 0, null, ParquetInstructions.EMPTY);
        String metadataString = tableLocationKey.getMetadata().getFileMetaData().toString();
        assertTrue(metadataString.contains(vvvIndexFilePath));

        // Write another table but this write should fail
        final Table badTable = TableTools.newTable(TableTools.intCol("www", data))
                .updateView("InputString = ii % 2 == 0 ? Long.toString(ii) : null", "A=InputString.charAt(0)");
        try {
            writer.writeTable(badTable, destFile);
            TestCase.fail("Exception expected for invalid formula");
        } catch (UncheckedDeephavenException e) {
            assertTrue(e.getCause() instanceof FormulaEvaluationException);
        }

        // Make sure that original file is preserved and no temporary files
        verifyFilesInDir(parentDir, new String[] {destFilename}, Map.of("vvv", new String[] {vvvIndexFilePath}));
        checkSingleTable(tableToSave, destFile);
        FileUtils.deleteRecursively(parentDir);
    }

    @Test
    public void legacyGroupingFileReadTest() {
        final String path =
                ParquetTableReadWriteTest.class.getResource("/ParquetDataWithLegacyGroupingInfo.parquet").getFile();
        final File destFile = new File(path);

        // Read the legacy file and verify that grouping column is read correctly
        final Table fromDisk = readParquetFileFromGitLFS(destFile);
        final String groupingColName = "gcol";
        assertTrue(DataIndexer.hasDataIndex(fromDisk, groupingColName));

        // Verify that the key-value metadata in the file has the correct legacy grouping file name
        final ParquetTableLocationKey tableLocationKey =
                new ParquetTableLocationKey(destFile.toURI(), 0, null, ParquetInstructions.EMPTY);
        final String metadataString = tableLocationKey.getMetadata().getFileMetaData().toString();
        String groupingFileName = ParquetTools.legacyGroupingFileName(destFile, groupingColName);
        assertTrue(metadataString.contains(groupingFileName));

        // Following is how this file was generated, so verify the table read from disk against this
        final int[] data = new int[500 * 4];
        for (int i = 0; i < data.length; i++) {
            data[i] = i / 4;
        }
        final Table table = newTable(TableTools.intCol(groupingColName, data));
        assertTableEquals(fromDisk, table);

        // Read the legacy grouping table.
        final DataIndex fromDiskIndex = DataIndexer.getDataIndex(fromDisk, groupingColName);
        final Table fromDiskIndexTable = Objects.requireNonNull(fromDiskIndex).table();

        // Create a dynamic index from the table.
        final DataIndex tableIndex = DataIndexer.getOrCreateDataIndex(table, groupingColName);
        final Table tableIndexTable = Objects.requireNonNull(tableIndex).table();

        // Validate the loaded and created index match.
        assertTableEquals(fromDiskIndexTable, tableIndexTable);
    }

    @Test
    public void parquetDirectoryWithDotFilesTest() throws IOException {
        // Create an empty parent directory
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();
        assertTrue(parentDir.exists() && parentDir.isDirectory() && parentDir.list().length == 0);

        int[] data = new int[500 * 4];
        for (int i = 0; i < data.length; i++) {
            data[i] = i / 4;
        }
        final Table tableToSave = newTable(TableTools.intCol("vvv", data));
        DataIndexer.getOrCreateDataIndex(tableToSave, "vvv");

        final String destFilename = "data.parquet";
        final File destFile = new File(parentDir, destFilename);
        writeTable(tableToSave, destFile.getPath());
        String vvvIndexFilePath = ".dh_metadata/indexes/vvv/index_vvv_data.parquet";
        verifyFilesInDir(parentDir, new String[] {destFilename}, Map.of("vvv", new String[] {vvvIndexFilePath}));

        // Call readTable on parent directory
        Table fromDisk = readTable(parentDir.getPath(),
                EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.FLAT_PARTITIONED));
        assertTableEquals(fromDisk, tableToSave);

        // Add an empty dot file and dot directory (with valid parquet files) in the parent directory
        final File dotFile = new File(parentDir, ".dotFile");
        assertTrue(dotFile.createNewFile());
        final File dotDir = new File(parentDir, ".dotDir");
        assertTrue(dotDir.mkdir());
        final Table someTable = TableTools.emptyTable(5).update("A=(int)i");
        writeTable(someTable, new File(dotDir, "data.parquet").getPath());
        fromDisk = readTable(parentDir.getPath(),
                EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.FLAT_PARTITIONED));
        assertTableEquals(fromDisk, tableToSave);

        // Add a dot parquet in parent directory
        final Table anotherTable = TableTools.emptyTable(5).update("A=(int)i");
        final File pqDotFile = new File(parentDir, ".dotFile.parquet");
        writeTable(anotherTable, pqDotFile.getPath());
        fromDisk = readTable(parentDir.getPath(),
                EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.FLAT_PARTITIONED));
        assertTableEquals(fromDisk, tableToSave);
    }

    @Test
    public void partitionedParquetWithDotFilesTest() throws IOException {
        // Create an empty parent directory
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();
        assertTrue(parentDir.exists() && parentDir.isDirectory() && parentDir.list().length == 0);

        final Table someTable = TableTools.emptyTable(5).update("A=(int)i");
        final File firstPartition = new File(parentDir, "X=A");
        final File firstDataFile = new File(firstPartition, "data.parquet");
        final File secondPartition = new File(parentDir, "X=B");
        final File secondDataFile = new File(secondPartition, "data.parquet");

        writeTable(someTable, firstDataFile.getPath());
        writeTable(someTable, secondDataFile.getPath());

        final URI parentURI = convertToURI(parentDir, true);
        final Table partitionedTable = readTable(parentURI.toString()).select();
        final Set<String> columnsSet = partitionedTable.getDefinition().getColumnNameSet();
        assertTrue(columnsSet.size() == 2 && columnsSet.contains("A") && columnsSet.contains("X"));

        // Add an empty dot file and dot directory (with valid parquet files) in one of the partitions
        final File dotFile = new File(firstPartition, ".dotFile");
        assertTrue(dotFile.createNewFile());
        final File dotDir = new File(firstPartition, ".dotDir");
        assertTrue(dotDir.mkdir());
        writeTable(someTable, new File(dotDir, "data.parquet").getPath());
        Table fromDisk = readTable(parentURI.toString());
        assertTableEquals(fromDisk, partitionedTable);

        // Add a dot parquet file in one of the partitions directory
        final Table anotherTable = TableTools.emptyTable(5).update("B=(int)i");
        final File pqDotFile = new File(secondPartition, ".dotFile.parquet");
        writeTable(anotherTable, pqDotFile.getPath());
        fromDisk = readTable(parentURI.toString());
        assertTableEquals(fromDisk, partitionedTable);
    }

    @Test
    public void readParquetFilesWithCodec() {
        final Table table = TableTools.emptyTable(10000)
                .update("bdColumn = java.math.BigDecimal.valueOf(ii).stripTrailingZeros()",
                        "biColumn = i % 10 == 0 ? null : java.math.BigInteger.valueOf(ii*512)")
                .select();

        // Set codecs for each column
        final ParquetInstructions instructions = ParquetInstructions.builder()
                .addColumnCodec("bdColumn", "io.deephaven.util.codec.BigDecimalCodec", "20,1,allowrounding")
                .addColumnCodec("biColumn", "io.deephaven.util.codec.BigIntegerCodec")
                .build();

        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();
        final File dest = new File(parentDir, "dataWithCodecInfo.parquet");
        ParquetTools.writeTable(table, dest.getPath(), instructions);
        checkSingleTable(table, dest);
    }

    @Test
    public void partitionedParquetWithDuplicateDataTest() throws IOException {
        // Create an empty parent directory
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();
        assertTrue(parentDir.exists() && parentDir.isDirectory() && parentDir.list().length == 0);

        // Writing the partitioning column "X" in the file itself
        final Table firstTable = TableTools.emptyTable(5).update("X='A'", "Y=(int)i");
        final File firstPartition = new File(parentDir, "X=A");
        final File firstDataFile = new File(firstPartition, "data.parquet");

        final File secondPartition = new File(parentDir, "X=B");
        final File secondDataFile = new File(secondPartition, "data.parquet");
        final Table secondTable = TableTools.emptyTable(5).update("X='B'", "Y=(int)i");

        writeTable(firstTable, firstDataFile.getPath());
        writeTable(secondTable, secondDataFile.getPath());

        final Table partitionedTable = readTable(parentDir.getPath(),
                EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED)).select();

        final Table combinedTable = merge(firstTable, secondTable);
        assertTableEquals(partitionedTable, combinedTable);
    }

    /**
     * These are tests for writing multiple parquet tables with indexes.
     */
    @Test
    public void writeMultiTableIndexTest() {
        // Create an empty parent directory
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();

        int[] data = new int[500 * 4];
        for (int i = 0; i < data.length; i++) {
            data[i] = i / 4;
        }
        final Table firstTable = TableTools.newTable(TableTools.intCol("vvv", data));
        final String firstFilename = "firstTable.parquet";
        final File firstDestFile = new File(parentDir, firstFilename);
        DataIndexer.getOrCreateDataIndex(firstTable, "vvv");

        final Table secondTable = newTable(TableTools.intCol("vvv", data));
        final String secondFilename = "secondTable.parquet";
        final File secondDestFile = new File(parentDir, secondFilename);
        DataIndexer.getOrCreateDataIndex(secondTable, "vvv");

        Table[] tablesToSave = new Table[] {firstTable, secondTable};
        final String[] destinations = new String[] {firstDestFile.getPath(), secondDestFile.getPath()};
        writeTables(tablesToSave, destinations, ParquetInstructions.EMPTY);

        String firstIndexFilePath = ".dh_metadata/indexes/vvv/index_vvv_firstTable.parquet";
        String secondIndexFilePath = ".dh_metadata/indexes/vvv/index_vvv_secondTable.parquet";
        verifyFilesInDir(parentDir, new String[] {firstFilename, secondFilename},
                Map.of("vvv", new String[] {firstIndexFilePath, secondIndexFilePath}));

        // Verify that the key-value metadata in the file has the correct name
        ParquetTableLocationKey tableLocationKey =
                new ParquetTableLocationKey(firstDestFile.toURI(), 0, null, ParquetInstructions.EMPTY);
        String metadataString = tableLocationKey.getMetadata().getFileMetaData().toString();
        assertTrue(metadataString.contains(firstIndexFilePath));
        tableLocationKey = new ParquetTableLocationKey(secondDestFile.toURI(), 0, null, ParquetInstructions.EMPTY);
        metadataString = tableLocationKey.getMetadata().getFileMetaData().toString();
        assertTrue(metadataString.contains(secondIndexFilePath));

        // Read back the files and verify contents match
        checkSingleTable(firstTable, firstDestFile);
        checkSingleTable(secondTable, secondDestFile);
    }

    @Test
    public void indexOverwritingTests() {
        indexOverwritingTestsImpl(SINGLE_WRITER);
        indexOverwritingTestsImpl(MULTI_WRITER);
    }

    private static File getBackupFile(final File destFile) {
        return new File(destFile.getParent(), ".OLD_" + destFile.getName());
    }

    private void indexOverwritingTestsImpl(TestParquetTableWriter writer) {
        // Create an empty parent directory
        final File parentDir = new File(rootFile, "tempDir");
        parentDir.mkdir();
        assertTrue(parentDir.exists() && parentDir.isDirectory() && parentDir.list().length == 0);

        int[] data = new int[500 * 4];
        for (int i = 0; i < data.length; i++) {
            data[i] = i / 4;
        }
        final Table tableToSave = newTable(TableTools.intCol("vvv", data));
        DataIndexer.getOrCreateDataIndex(tableToSave, "vvv");

        final String destFilename = "indexWriteTests.parquet";
        final File destFile = new File(parentDir, destFilename);
        writer.writeTable(tableToSave, destFile);
        String vvvIndexFilePath = ".dh_metadata/indexes/vvv/index_vvv_indexWriteTests.parquet";

        // Write a new table successfully at the same position with different indexes
        Table anotherTableToSave = newTable(TableTools.intCol("xxx", data));

        DataIndexer.getOrCreateDataIndex(anotherTableToSave, "xxx");

        writer.writeTable(anotherTableToSave, destFile);
        final String xxxIndexFilePath = ".dh_metadata/indexes/xxx/index_xxx_indexWriteTests.parquet";

        // The directory now should contain the updated table, its index file for column xxx, and old index file
        // for column vvv
        verifyFilesInDir(parentDir, new String[] {destFilename},
                Map.of("vvv", new String[] {vvvIndexFilePath},
                        "xxx", new String[] {xxxIndexFilePath}));

        checkSingleTable(anotherTableToSave, destFile);

        ParquetTableLocationKey tableLocationKey =
                new ParquetTableLocationKey(destFile.toURI(), 0, null, ParquetInstructions.EMPTY);
        String metadataString = tableLocationKey.getMetadata().getFileMetaData().toString();
        assertTrue(metadataString.contains(xxxIndexFilePath) && !metadataString.contains(vvvIndexFilePath));

        // Overwrite the table
        writer.writeTable(anotherTableToSave, destFile);

        // The directory should still contain the updated table, its index file for column xxx, and old index file
        // for column vvv
        final File xxxIndexFile = new File(parentDir, xxxIndexFilePath);
        final File backupXXXIndexFile = getBackupFile(xxxIndexFile);
        final String backupXXXIndexFileName = backupXXXIndexFile.getName();
        verifyFilesInDir(parentDir, new String[] {destFilename},
                Map.of("vvv", new String[] {vvvIndexFilePath},
                        "xxx", new String[] {xxxIndexFilePath}));

        tableLocationKey = new ParquetTableLocationKey(destFile.toURI(), 0, null, ParquetInstructions.EMPTY);
        metadataString = tableLocationKey.getMetadata().getFileMetaData().toString();
        assertTrue(metadataString.contains(xxxIndexFilePath) && !metadataString.contains(vvvIndexFilePath)
                && !metadataString.contains(backupXXXIndexFileName));
        FileUtils.deleteRecursively(parentDir);
    }

    @Test
    public void readChangedUnderlyingFileTests() {
        readChangedUnderlyingFileTestsImpl(SINGLE_WRITER);
        readChangedUnderlyingFileTestsImpl(MULTI_WRITER);
    }

    private void readChangedUnderlyingFileTestsImpl(TestParquetTableWriter writer) {
        // Write a table to parquet file and read it back
        final Table tableToSave = TableTools.emptyTable(5).update("A=(int)i", "B=(long)i", "C=(double)i");
        final String filename = "readChangedUnderlyingFileTests.parquet";
        final File destFile = new File(rootFile, filename);
        writer.writeTable(tableToSave, destFile);
        Table fromDisk =
                readTable(destFile.getPath(), EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE));
        // At this point, fromDisk is not fully materialized in the memory and would be read from the file on demand

        // Change the underlying file
        final Table stringTable = TableTools.emptyTable(5).update("InputString = Long.toString(ii)");
        writer.writeTable(stringTable, destFile);
        Table stringFromDisk =
                readTable(destFile.getPath(), EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE))
                        .select();
        assertTableEquals(stringTable, stringFromDisk);

        // Close all the file handles so that next time when fromDisk is accessed, we need to reopen the file handle
        TrackedFileHandleFactory.getInstance().closeAll();

        // Read back fromDisk. Since the underlying file has changed, we expect this to fail.
        try {
            fromDisk.where("A % 2 == 0");
            TestCase.fail("Expected exception");
        } catch (RuntimeException ignored) {
            // expected
        }
    }

    @Test
    public void readModifyWriteTests() {
        readModifyWriteTestsImpl(SINGLE_WRITER);
        readModifyWriteTestsImpl(MULTI_WRITER);
    }

    private void readModifyWriteTestsImpl(TestParquetTableWriter writer) {
        // Write a table to parquet file and read it back
        final Table tableToSave = TableTools.emptyTable(5).update("A=(int)i", "B=(long)i", "C=(double)i");
        final String filename = "readModifyWriteTests.parquet";
        final File destFile = new File(rootFile, filename);
        writer.writeTable(tableToSave, destFile);
        Table fromDisk =
                readTable(destFile.getPath(), EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE));
        // At this point, fromDisk is not fully materialized in the memory and would be read from the file on demand

        // Create a view table on fromDisk which should fail on writing, and try to write at the same location
        // Since we are doing a view() operation and adding a new column and overwriting an existing column, the table
        // won't be materialized in memory or cache.
        final Table badTable =
                fromDisk.view("InputString = ii % 2 == 0 ? Long.toString(ii) : null", "A=InputString.charAt(0)");
        try {
            writer.writeTable(badTable, destFile);
            TestCase.fail();
        } catch (UncheckedDeephavenException e) {
            assertTrue(e.getCause() instanceof FormulaEvaluationException);
        }

        // Close all old file handles so that we read the file path fresh instead of using any old handles
        TrackedFileHandleFactory.getInstance().closeAll();

        // Read back fromDisk and compare it with original table. If the underlying file has not been corrupted or
        // swapped out, then we would not be able to read from the file
        assertTableEquals(tableToSave, fromDisk);
    }

    @Test
    public void dictionaryEncodingTest() {
        Collection<String> columns = new ArrayList<>(Arrays.asList(
                "shortStringColumn = `Row ` + i",
                "longStringColumn = `This is row ` + i",
                "someIntColumn = i"));
        final int numRows = 10;
        final ParquetInstructions writeInstructions = new ParquetInstructions.Builder()
                .setMaximumDictionarySize(100) // Force "longStringColumn" to use non-dictionary encoding
                .build();
        final Table stringTable = TableTools.emptyTable(numRows).select(Selectable.from(columns));
        final File dest = new File(rootFile + File.separator + "dictEncoding.parquet");
        writeTable(stringTable, dest.getPath(), writeInstructions);
        checkSingleTable(stringTable, dest);

        // Verify that string columns are properly dictionary encoded
        final ParquetMetadata metadata =
                new ParquetTableLocationKey(dest.toURI(), 0, null, ParquetInstructions.EMPTY).getMetadata();
        final String firstColumnMetadata = metadata.getBlocks().get(0).getColumns().get(0).toString();
        assertTrue(firstColumnMetadata.contains("shortStringColumn") && firstColumnMetadata.contains("RLE_DICTIONARY"));
        final String secondColumnMetadata = metadata.getBlocks().get(0).getColumns().get(1).toString();
        assertTrue(
                secondColumnMetadata.contains("longStringColumn") && !secondColumnMetadata.contains("RLE_DICTIONARY"));
        final String thirdColumnMetadata = metadata.getBlocks().get(0).getColumns().get(2).toString();
        assertTrue(thirdColumnMetadata.contains("someIntColumn") && !thirdColumnMetadata.contains("RLE_DICTIONARY"));
    }

    @Test
    public void mixedDictionaryEncodingTest() {
        // Test the behavior of writing parquet files with some pages dictionary encoded and some not
        String path = ParquetTableReadWriteTest.class
                .getResource("/ParquetDataWithMixedEncodingWithoutOffsetIndex.parquet").getFile();
        Table fromDisk = readParquetFileFromGitLFS(new File(path)).select();
        Table expected =
                emptyTable(2_000_000).update("Broken=String.format(`%015d`,  ii < 1200000 ? (ii % 30000) : ii)");
        assertTableEquals(expected, fromDisk);

        path = ParquetTableReadWriteTest.class.getResource("/ParquetDataWithMixedEncodingWithOffsetIndex.parquet")
                .getFile();
        fromDisk = readParquetFileFromGitLFS(new File(path)).select();
        final Collection<String> columns = new ArrayList<>(Arrays.asList("shortStringColumn = `Some data`"));
        final int numRows = 20;
        expected = TableTools.emptyTable(numRows).select(Selectable.from(columns));
        assertTableEquals(expected, fromDisk);
    }

    @Test
    public void overflowingStringsTest() {
        // Test the behavior of writing parquet files if entries exceed the page size limit
        final int pageSize = ParquetInstructions.MIN_TARGET_PAGE_SIZE;
        final char[] data = new char[pageSize / 4];
        String someString = new String(data);
        Collection<String> columns = new ArrayList<>(Arrays.asList(
                "someStringColumn = `" + someString + "` + i%10"));
        final long numRows = 10;
        ColumnChunkMetaData columnMetadata = overflowingStringsTestHelper(columns, numRows, pageSize);
        String metadataStr = columnMetadata.toString();
        assertTrue(metadataStr.contains("someStringColumn") && metadataStr.contains("PLAIN")
                && !metadataStr.contains("RLE_DICTIONARY"));

        // We exceed page size on hitting 4 rows, and we have 10 total rows.
        // Therefore, we should have total 4 pages containing 3, 3, 3, 1 rows respectively.
        assertEquals(columnMetadata.getEncodingStats().getNumDataPagesEncodedAs(Encoding.PLAIN), 4);

        final char[] veryLongData = new char[pageSize];
        someString = new String(veryLongData);
        columns = new ArrayList<>(
                Arrays.asList("someStringColumn =  ii % 2 == 0 ? Long.toString(ii) : `" + someString + "` + ii"));
        columnMetadata = overflowingStringsTestHelper(columns, numRows, pageSize);
        // We will have 10 pages each containing 1 row.
        assertEquals(columnMetadata.getEncodingStats().getNumDataPagesEncodedAs(Encoding.PLAIN), 10);

        // Table with rows of null alternating with strings exceeding the page size
        columns = new ArrayList<>(Arrays.asList("someStringColumn =  ii % 2 == 0 ? null : `" + someString + "` + ii"));
        columnMetadata = overflowingStringsTestHelper(columns, numRows, pageSize);
        // We will have 6 pages containing 1, 2, 2, 2, 2, 1 rows.
        assertEquals(columnMetadata.getEncodingStats().getNumDataPagesEncodedAs(Encoding.PLAIN), 6);
    }

    private static ColumnChunkMetaData overflowingStringsTestHelper(final Collection<String> columns,
            final long numRows, final int pageSize) {
        final ParquetInstructions writeInstructions = new ParquetInstructions.Builder()
                .setTargetPageSize(pageSize) // Force a small page size to cause splitting across pages
                .setMaximumDictionarySize(50) // Force "someStringColumn" to use non-dictionary encoding
                .build();
        Table stringTable = TableTools.emptyTable(numRows).select(Selectable.from(columns));
        final File dest = new File(rootFile + File.separator + "overflowingStringsTest.parquet");
        writeTable(stringTable, dest.getPath(), writeInstructions);
        checkSingleTable(stringTable, dest);

        ParquetMetadata metadata =
                new ParquetTableLocationKey(dest.toURI(), 0, null, ParquetInstructions.EMPTY).getMetadata();
        return metadata.getBlocks().get(0).getColumns().get(0);
    }

    @Test
    public void overflowingCodecsTest() {
        final int pageSize = ParquetInstructions.MIN_TARGET_PAGE_SIZE;
        final ParquetInstructions writeInstructions = new ParquetInstructions.Builder()
                .setTargetPageSize(pageSize) // Force a small page size to cause splitting across pages
                .addColumnCodec("VariableWidthByteArrayColumn", SimpleByteArrayCodec.class.getName())
                .build();

        final ColumnDefinition<byte[]> columnDefinition =
                ColumnDefinition.fromGenericType("VariableWidthByteArrayColumn", byte[].class, byte.class);
        final TableDefinition tableDefinition = TableDefinition.of(columnDefinition);
        final byte[] byteArray = new byte[pageSize / 2];
        final Table table = newTable(tableDefinition,
                TableTools.col("VariableWidthByteArrayColumn", byteArray, byteArray, byteArray));

        final File dest = new File(rootFile + File.separator + "overflowingCodecsTest.parquet");
        writeTable(table, dest.getPath(), writeInstructions);
        checkSingleTable(table, dest);

        final ParquetMetadata metadata =
                new ParquetTableLocationKey(dest.toURI(), 0, null, ParquetInstructions.EMPTY).getMetadata();
        final String metadataStr = metadata.getFileMetaData().getKeyValueMetaData().get("deephaven");
        assertTrue(
                metadataStr.contains("VariableWidthByteArrayColumn") && metadataStr.contains("SimpleByteArrayCodec"));
        final ColumnChunkMetaData columnMetadata = metadata.getBlocks().get(0).getColumns().get(0);
        final String columnMetadataStr = columnMetadata.toString();
        assertTrue(columnMetadataStr.contains("VariableWidthByteArrayColumn") && columnMetadataStr.contains("PLAIN"));
        // Each byte array is of half the page size. So we exceed page size on hitting 3 byteArrays.
        // Therefore, we should have total 2 pages containing 2, 1 rows respectively.
        assertEquals(columnMetadata.getEncodingStats().getNumDataPagesEncodedAs(Encoding.PLAIN), 2);
    }

    private static void verifyMakeHandleException(final Runnable throwingRunnable) {
        try {
            throwingRunnable.run();
            fail("Expected UncheckedIOException");
        } catch (final UncheckedIOException e) {
            assertTrue(e.getMessage().contains("makeHandle encountered exception"));
        }
    }

    private static void makeNewTableLocationAndVerifyNoException(
            final Consumer<ParquetTableLocation> parquetTableLocationConsumer) {
        final File dest = new File(rootFile, "real.parquet");
        final Table table = TableTools.emptyTable(5).update("A=(int)i", "B=(long)i", "C=(double)i");
        DataIndexer.getOrCreateDataIndex(table, "A");
        writeTable(table, dest.getPath());

        final ParquetTableLocationKey newTableLocationKey =
                new ParquetTableLocationKey(dest.toURI(), 0, null, ParquetInstructions.EMPTY);
        final ParquetTableLocation newTableLocation =
                new ParquetTableLocation(StandaloneTableKey.getInstance(), newTableLocationKey, EMPTY);

        // The following operations should not throw exceptions
        parquetTableLocationConsumer.accept(newTableLocation);
        dest.delete();
    }

    @Test
    public void testTableLocationReading() {
        // Make a new ParquetTableLocation for a non-existent parquet file
        final File nonExistentParquetFile = new File(rootFile, "non-existent.parquet");
        assertFalse(nonExistentParquetFile.exists());
        final ParquetTableLocationKey nonExistentTableLocationKey =
                new ParquetTableLocationKey(nonExistentParquetFile.toURI(), 0, null, ParquetInstructions.EMPTY);
        final ParquetTableLocation nonExistentTableLocation =
                new ParquetTableLocation(StandaloneTableKey.getInstance(), nonExistentTableLocationKey, EMPTY);

        // Ensure operations don't touch the file or throw exceptions
        assertEquals(nonExistentTableLocation.getTableKey(), StandaloneTableKey.getInstance());
        assertEquals(nonExistentTableLocation.getKey(), nonExistentTableLocationKey);
        assertNotNull(nonExistentTableLocation.toString());
        assertNotNull(nonExistentTableLocation.asLivenessReferent());
        assertNotNull(nonExistentTableLocation.getStateLock());
        nonExistentTableLocation.refresh();

        // Verify that we can get a column location for a non-existent column
        final ColumnLocation nonExistentColumnLocation = nonExistentTableLocation.getColumnLocation("A");
        assertNotNull(nonExistentColumnLocation);
        assertEquals("A", nonExistentColumnLocation.getName());
        assertEquals(nonExistentTableLocation, nonExistentColumnLocation.getTableLocation());
        assertNotNull(nonExistentColumnLocation.toString());
        assertNotNull(nonExistentColumnLocation.getImplementationName());

        // Verify that all the following operations will fail when the file does not exist and pass when it does
        // APIs from TableLocation
        verifyMakeHandleException(nonExistentTableLocation::getDataIndexColumns);
        makeNewTableLocationAndVerifyNoException(ParquetTableLocation::getDataIndexColumns);

        verifyMakeHandleException(nonExistentTableLocation::getSortedColumns);
        makeNewTableLocationAndVerifyNoException(ParquetTableLocation::getSortedColumns);

        verifyMakeHandleException(nonExistentTableLocation::getColumnTypes);
        makeNewTableLocationAndVerifyNoException(ParquetTableLocation::getColumnTypes);

        verifyMakeHandleException(nonExistentTableLocation::hasDataIndex);
        makeNewTableLocationAndVerifyNoException(ParquetTableLocation::hasDataIndex);

        // Assuming here there will be an index on column "A"
        verifyMakeHandleException(nonExistentTableLocation::getDataIndex);
        makeNewTableLocationAndVerifyNoException(tableLocation -> tableLocation.getDataIndex("A"));

        verifyMakeHandleException(nonExistentTableLocation::loadDataIndex);
        makeNewTableLocationAndVerifyNoException(tableLocation -> tableLocation.loadDataIndex("A"));

        // APIs from TableLocationState
        verifyMakeHandleException(nonExistentTableLocation::getRowSet);
        makeNewTableLocationAndVerifyNoException(ParquetTableLocation::getRowSet);

        verifyMakeHandleException(nonExistentTableLocation::getSize);
        makeNewTableLocationAndVerifyNoException(ParquetTableLocation::getSize);

        verifyMakeHandleException(nonExistentTableLocation::getLastModifiedTimeMillis);
        makeNewTableLocationAndVerifyNoException(ParquetTableLocation::getLastModifiedTimeMillis);

        verifyMakeHandleException(() -> nonExistentTableLocation.handleUpdate(new TrackingWritableRowSetImpl(), 0));

        // APIs from ColumnLocation
        verifyMakeHandleException(nonExistentColumnLocation::exists);
        verifyMakeHandleException(() -> nonExistentColumnLocation.makeColumnRegionChar(
                ColumnDefinition.fromGenericType("A", char.class, Character.class)));
        verifyMakeHandleException(() -> nonExistentColumnLocation.makeColumnRegionByte(
                ColumnDefinition.fromGenericType("A", byte.class, Byte.class)));
        verifyMakeHandleException(() -> nonExistentColumnLocation.makeColumnRegionShort(
                ColumnDefinition.fromGenericType("A", short.class, Short.class)));
        verifyMakeHandleException(() -> nonExistentColumnLocation.makeColumnRegionInt(
                ColumnDefinition.fromGenericType("A", int.class, Integer.class)));
        verifyMakeHandleException(() -> nonExistentColumnLocation.makeColumnRegionLong(
                ColumnDefinition.fromGenericType("A", long.class, Long.class)));
        verifyMakeHandleException(() -> nonExistentColumnLocation.makeColumnRegionFloat(
                ColumnDefinition.fromGenericType("A", float.class, Float.class)));
        verifyMakeHandleException(() -> nonExistentColumnLocation.makeColumnRegionDouble(
                ColumnDefinition.fromGenericType("A", double.class, Double.class)));
        verifyMakeHandleException(() -> nonExistentColumnLocation.makeColumnRegionObject(
                ColumnDefinition.fromGenericType("A", String.class, String.class)));
    }

    @Test
    public void readWriteStatisticsTest() {
        // Test simple structured table.
        final ColumnDefinition<byte[]> columnDefinition =
                ColumnDefinition.fromGenericType("VariableWidthByteArrayColumn", byte[].class, byte.class);
        final TableDefinition tableDefinition = TableDefinition.of(columnDefinition);
        final byte[] byteArray = new byte[] {1, 2, 3, 4, NULL_BYTE, 6, 7, 8, 9, NULL_BYTE, 11, 12, 13};
        final Table simpleTable = newTable(tableDefinition,
                TableTools.col("VariableWidthByteArrayColumn", null, byteArray, byteArray, byteArray, byteArray,
                        byteArray));
        final File simpleTableDest = new File(rootFile, "ParquetTest_simple_statistics_test.parquet");
        writeTable(simpleTable, simpleTableDest.getPath());

        checkSingleTable(simpleTable, simpleTableDest);

        assertTableStatistics(simpleTable, simpleTableDest);

        // Test flat columns.
        final Table flatTableToSave = getTableFlat(10_000, true, true);
        final File flatTableDest = new File(rootFile, "ParquetTest_flat_statistics_test.parquet");
        writeTable(flatTableToSave, flatTableDest.getPath());

        checkSingleTable(maybeFixBigDecimal(flatTableToSave), flatTableDest);

        assertTableStatistics(flatTableToSave, flatTableDest);

        // Test nested columns.
        final Table groupedTableToSave = getGroupedTable(10_000, true);
        final File groupedTableDest = new File(rootFile, "ParquetTest_grouped_statistics_test.parquet");
        writeTable(groupedTableToSave, groupedTableDest.getPath(),
                EMPTY.withTableDefinition(groupedTableToSave.getDefinition()));

        checkSingleTable(groupedTableToSave, groupedTableDest);

        assertTableStatistics(groupedTableToSave, groupedTableDest);
    }

    @Test
    public void readWriteDateTimeTest() {
        final int NUM_ROWS = 1000;
        final Table table = TableTools.emptyTable(NUM_ROWS).view(
                "someDateColumn = java.time.LocalDate.ofEpochDay(i)",
                "someTimeColumn = java.time.LocalTime.of(i%24, i%60, (i+10)%60)",
                "someLocalDateTimeColumn = java.time.LocalDateTime.of(2000+i%10, i%12+1, i%30+1, (i+4)%24, (i+5)%60, (i+6)%60, i)",
                "someInstantColumn = DateTimeUtils.now() + i").select();
        final File dest = new File(rootFile, "readWriteDateTimeTest.parquet");
        writeReadTableTest(table, dest);

        // Verify that the types are correct in the schema
        final ParquetMetadata metadata =
                new ParquetTableLocationKey(dest.toURI(), 0, null, ParquetInstructions.EMPTY).getMetadata();
        final ColumnChunkMetaData dateColMetadata = metadata.getBlocks().get(0).getColumns().get(0);
        assertTrue(dateColMetadata.toString().contains("someDateColumn"));
        assertEquals(INT32, dateColMetadata.getPrimitiveType().getPrimitiveTypeName());
        assertEquals(LogicalTypeAnnotation.dateType(), dateColMetadata.getPrimitiveType().getLogicalTypeAnnotation());

        final ColumnChunkMetaData timeColMetadata = metadata.getBlocks().get(0).getColumns().get(1);
        assertTrue(timeColMetadata.toString().contains("someTimeColumn"));
        assertEquals(INT64, timeColMetadata.getPrimitiveType().getPrimitiveTypeName());
        assertEquals(LogicalTypeAnnotation.timeType(true, LogicalTypeAnnotation.TimeUnit.NANOS),
                timeColMetadata.getPrimitiveType().getLogicalTypeAnnotation());

        final ColumnChunkMetaData localDateTimeColMetadata = metadata.getBlocks().get(0).getColumns().get(2);
        assertTrue(localDateTimeColMetadata.toString().contains("someLocalDateTimeColumn"));
        assertEquals(INT64,
                localDateTimeColMetadata.getPrimitiveType().getPrimitiveTypeName());
        assertEquals(LogicalTypeAnnotation.timestampType(false, LogicalTypeAnnotation.TimeUnit.NANOS),
                localDateTimeColMetadata.getPrimitiveType().getLogicalTypeAnnotation());

        final ColumnChunkMetaData instantColMetadata = metadata.getBlocks().get(0).getColumns().get(3);
        assertTrue(instantColMetadata.toString().contains("someInstantColumn"));
        assertEquals(INT64,
                instantColMetadata.getPrimitiveType().getPrimitiveTypeName());
        assertEquals(LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.NANOS),
                instantColMetadata.getPrimitiveType().getLogicalTypeAnnotation());
    }

    /**
     * Test our manual verification techniques against a file generated by pyarrow. Here is the code to produce the file
     * when/if this file needs to be re-generated or changed.
     *
     * <pre>
     * ###############################################################################
     * import pyarrow.parquet
     *
     * pa_table = pyarrow.table({
     *     'int': [0, None, 100, -100],
     *     'float': [0.0, None, 100.0, -100.0],
     *     'string': ["aaa", None, "111", "ZZZ"],
     *     'intList': [
     *         [0, None, 2],
     *         None,
     *         [3, 4, 6, 7, 8, 9, 10, 100, -100],
     *         [5]
     *     ],
     *     'floatList': [
     *         [0.0, None, 2.0],
     *         None,
     *         [3.0, 4.0, 6.0, 7.0, 8.0, 9.0, 10.0, 100.0, -100.0],
     *         [5.0]
     *     ],
     *     'stringList': [
     *         ["aaa", None, None],
     *         None,
     *         ["111", "zzz", "ZZZ", "AAA"],
     *         ["ccc"]
     *     ]})
     * pyarrow.parquet.write_table(pa_table, './extensions/parquet/table/src/test/resources/e0/pyarrow_stats.parquet')
     * ###############################################################################
     * </pre>
     */
    @Test
    public void verifyPyArrowStatistics() {
        final String path = ParquetTableReadWriteTest.class.getResource("/e0/pyarrow_stats.parquet").getFile();
        final File pyarrowDest = new File(path);
        final Table pyarrowFromDisk = readParquetFileFromGitLFS(pyarrowDest);

        // Verify that our verification code works for a pyarrow generated table.
        assertTableStatistics(pyarrowFromDisk, pyarrowDest);

        // Write the table to disk using our code.
        final File dhDest = new File(rootFile, "ParquetTest_statistics_test.parquet");
        writeTable(pyarrowFromDisk, dhDest.getPath());

        final Table dhFromDisk = checkSingleTable(pyarrowFromDisk, dhDest);

        // Run the verification code against DHC writer stats.
        assertTableStatistics(pyarrowFromDisk, dhDest);
        assertTableStatistics(dhFromDisk, dhDest);
    }

    @Test
    public void singleTable() {
        final File fooSource = new File(rootFile, "singleTable/foo.parquet");
        final File fooBarSource = new File(rootFile, "singleTable/fooBar.parquet");
        final File barSource = new File(rootFile, "singleTable/bar.parquet");

        final Table foo;
        final Table fooBar;
        final Table bar;
        final Table fooBarNullFoo;
        final Table fooBarNullBar;

        final TableDefinition fooDefinition;
        final TableDefinition fooBarDefinition;
        final TableDefinition barDefinition;
        {
            final ColumnHolder<Integer> fooCol = intCol("Foo", 1, 2, 3);
            final ColumnHolder<String> barCol = stringCol("Bar", "Zip", "Zap", "Zoom");

            final ColumnHolder<Integer> nullFooCol =
                    intCol("Foo", QueryConstants.NULL_INT, QueryConstants.NULL_INT, QueryConstants.NULL_INT);
            final ColumnHolder<String> nullBarCol = stringCol("Bar", null, null, null);

            final ColumnDefinition<Integer> fooColDef = ColumnDefinition.ofInt("Foo");
            final ColumnDefinition<String> barColDef = ColumnDefinition.ofString("Bar");

            fooDefinition = TableDefinition.of(fooColDef);
            fooBarDefinition = TableDefinition.of(fooColDef, barColDef);
            barDefinition = TableDefinition.of(barColDef);

            foo = newTable(fooDefinition, fooCol);
            fooBar = newTable(fooBarDefinition, fooCol, barCol);
            bar = newTable(barDefinition, barCol);

            fooBarNullFoo = newTable(fooBarDefinition, nullFooCol, barCol);
            fooBarNullBar = newTable(fooBarDefinition, fooCol, nullBarCol);

            writeTable(foo, fooSource.getPath());
            writeTable(fooBar, fooBarSource.getPath());
            writeTable(bar, barSource.getPath());
        }

        // Infer
        {
            checkSingleTable(foo, fooSource);
            checkSingleTable(fooBar, fooBarSource);
            checkSingleTable(bar, barSource);
        }

        // readTable inference to readSingleTable
        {
            assertTableEquals(foo, readTable(fooSource.getPath()));
            assertTableEquals(fooBar, readTable(fooBarSource.getPath()));
            assertTableEquals(bar, readTable(barSource.getPath()));
        }

        // Explicit
        {
            assertTableEquals(foo, readTable(fooSource.getPath(), EMPTY.withTableDefinitionAndLayout(fooDefinition,
                    ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
            assertTableEquals(fooBar,
                    readTable(fooBarSource.getPath(), EMPTY.withTableDefinitionAndLayout(fooBarDefinition,
                            ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
            assertTableEquals(bar, readTable(barSource.getPath(), EMPTY.withTableDefinitionAndLayout(barDefinition,
                    ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        }

        // Explicit subset
        {
            // fooBar as foo
            assertTableEquals(foo, readTable(fooBarSource.getPath(), EMPTY.withTableDefinitionAndLayout(fooDefinition,
                    ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
            // fooBar as bar
            assertTableEquals(bar, readTable(fooBarSource.getPath(), EMPTY.withTableDefinitionAndLayout(barDefinition,
                    ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        }

        // Explicit superset
        {
            // foo as fooBar
            assertTableEquals(fooBarNullBar,
                    readTable(fooSource.getPath(), EMPTY.withTableDefinitionAndLayout(fooBarDefinition,
                            ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
            // bar as fooBar
            assertTableEquals(fooBarNullFoo,
                    readTable(barSource.getPath(), EMPTY.withTableDefinitionAndLayout(fooBarDefinition,
                            ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        }

        // No refreshing single table support
        {
            try {
                readTable(fooSource.getPath(),
                        REFRESHING.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE));
                fail("Expected IllegalArgumentException");
            } catch (IllegalArgumentException e) {
                assertEquals("Unable to have a refreshing single parquet file", e.getMessage());
            }

            try {
                readTable(fooSource.getPath(), REFRESHING.withTableDefinitionAndLayout(fooDefinition,
                        ParquetInstructions.ParquetFileLayout.SINGLE_FILE));
                fail("Expected IllegalArgumentException");
            } catch (IllegalArgumentException e) {
                assertEquals("Unable to have a refreshing single parquet file", e.getMessage());
            }
        }
    }

    @Test
    public void flatPartitionedTable() {
        // Create an empty parent directory
        final File source = new File(rootFile, "flatPartitionedTable/source");
        final File emptySource = new File(rootFile, "flatPartitionedTable/emptySource");

        final Table formerData;
        final Table latterData;
        final TableDefinition formerDefinition;
        final TableDefinition latterDefinition;
        final Runnable writeIntoEmptySource;
        {
            final File p1File = new File(source, "01.parquet");
            final File p2File = new File(source, "02.parquet");

            final File p1FileEmpty = new File(emptySource, "01.parquet");
            final File p2FileEmpty = new File(emptySource, "02.parquet");

            emptySource.mkdirs();

            final ColumnHolder<Integer> foo1 = intCol("Foo", 1, 2, 3);
            final ColumnHolder<Integer> foo2 = intCol("Foo", 4, 5);

            final ColumnHolder<String> bar1 = stringCol("Bar", null, null, null);
            final ColumnHolder<String> bar2 = stringCol("Bar", "Zip", "Zap");

            final Table p1 = newTable(foo1);
            final Table p2 = newTable(foo2, bar2);
            writeTable(p1, p1File.getPath());
            writeTable(p2, p2File.getPath());
            writeIntoEmptySource = () -> {
                writeTable(p1, p1FileEmpty.getPath());
                writeTable(p2, p2FileEmpty.getPath());
            };

            final ColumnDefinition<Integer> foo = ColumnDefinition.ofInt("Foo");
            final ColumnDefinition<String> bar = ColumnDefinition.ofString("Bar");

            formerDefinition = TableDefinition.of(foo);
            latterDefinition = TableDefinition.of(foo, bar);

            formerData = merge(
                    newTable(formerDefinition, foo1),
                    newTable(formerDefinition, foo2));
            latterData = merge(
                    newTable(latterDefinition, foo1, bar1),
                    newTable(latterDefinition, foo2, bar2));
        }

        // Infer from last key
        {
            final Table table = readTable(source.getPath(),
                    EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.FLAT_PARTITIONED));
            assertTableEquals(latterData, table);
        }
        // Infer from last key, refreshing
        {
            final Table table = readTable(source.getPath(),
                    REFRESHING.withLayout(ParquetInstructions.ParquetFileLayout.FLAT_PARTITIONED));
            assertTableEquals(latterData, table);
        }
        // readTable inference to readFlatPartitionedTable
        {
            assertTableEquals(latterData, readTable(source.getPath()));
        }

        // Explicit latter definition
        {
            final Table table = readTable(source.getPath(), EMPTY.withTableDefinitionAndLayout(latterDefinition,
                    ParquetInstructions.ParquetFileLayout.FLAT_PARTITIONED));
            assertTableEquals(latterData, table);
        }
        // Explicit latter definition, refreshing
        {
            final Table table = readTable(source.getPath(), REFRESHING.withTableDefinitionAndLayout(latterDefinition,
                    ParquetInstructions.ParquetFileLayout.FLAT_PARTITIONED));
            assertTableEquals(latterData, table);
        }

        // Explicit former definition
        {
            final Table table = readTable(source.getPath(), EMPTY.withTableDefinitionAndLayout(formerDefinition,
                    ParquetInstructions.ParquetFileLayout.FLAT_PARTITIONED));
            assertTableEquals(formerData, table);
        }
        // Explicit former definition, refreshing
        {
            final Table table = readTable(source.getPath(), REFRESHING.withTableDefinitionAndLayout(formerDefinition,
                    ParquetInstructions.ParquetFileLayout.FLAT_PARTITIONED));
            assertTableEquals(formerData, table);
        }

        // Explicit definition, empty directory
        {
            final Table table = readTable(emptySource.getPath(), EMPTY.withTableDefinitionAndLayout(latterDefinition,
                    ParquetInstructions.ParquetFileLayout.FLAT_PARTITIONED));
            assertTableEquals(TableTools.newTable(latterDefinition), table);
        }
        // Explicit definition, empty directory, refreshing with new data added
        {
            final Table table =
                    readTable(emptySource.getPath(), REFRESHING.withTableDefinitionAndLayout(latterDefinition,
                            ParquetInstructions.ParquetFileLayout.FLAT_PARTITIONED));
            assertTableEquals(TableTools.newTable(latterDefinition), table);

            writeIntoEmptySource.run();
            ExecutionContext.getContext().getUpdateGraph().<ControlledUpdateGraph>cast().runWithinUnitTestCycle(() -> {
                // This is not generally a good way to do this sort of testing. Ideally, we'd be a bit smarter and use
                // a test-driven TableDataRefreshService.getSharedRefreshService.
                ((SourceTable<?>) table).tableLocationProvider().refresh();
                ((SourceTable<?>) table).refresh();
                assertTableEquals(latterData, table);
            });
        }
    }

    @Test
    public void keyValuePartitionedTable() {
        final File source = new File(rootFile, "keyValuePartitionedTable/source");
        final File emptySource = new File(rootFile, "keyValuePartitionedTable/emptySource");

        final Table formerData;
        final Table latterData;
        final TableDefinition formerDefinition;
        final TableDefinition latterDefinition;
        final Runnable writeIntoEmptySource;
        {
            final File p1File = new File(source, "Partition=1/z.parquet");
            final File p2File = new File(source, "Partition=2/a.parquet");

            final File p1FileEmpty = new File(emptySource, "Partition=1/z.parquet");
            final File p2FileEmpty = new File(emptySource, "Partition=2/a.parquet");

            emptySource.mkdirs();

            final ColumnHolder<Integer> part1 = intCol("Partition", 1, 1, 1);
            final ColumnHolder<Integer> part2 = intCol("Partition", 2, 2);

            final ColumnHolder<Integer> foo1 = intCol("Foo", 1, 2, 3);
            final ColumnHolder<Integer> foo2 = intCol("Foo", 4, 5);

            final ColumnHolder<String> bar1 = stringCol("Bar", null, null, null);
            final ColumnHolder<String> bar2 = stringCol("Bar", "Zip", "Zap");

            final Table p1 = newTable(foo1);
            final Table p2 = newTable(foo2, bar2);
            writeTable(p1, p1File.getPath());
            writeTable(p2, p2File.getPath());
            writeIntoEmptySource = () -> {
                writeTable(p1, p1FileEmpty.getPath());
                writeTable(p2, p2FileEmpty.getPath());
            };

            // Need to be explicit w/ definition so partitioning column applied to expected tables
            final ColumnDefinition<Integer> partition = ColumnDefinition.ofInt("Partition").withPartitioning();
            final ColumnDefinition<Integer> foo = ColumnDefinition.ofInt("Foo");
            final ColumnDefinition<String> bar = ColumnDefinition.ofString("Bar");

            // Note: merge does not preserve partition column designation, so we need to explicitly create them
            formerDefinition = TableDefinition.of(partition, foo);
            latterDefinition = TableDefinition.of(partition, foo, bar);

            formerData = merge(
                    newTable(formerDefinition, part1, foo1),
                    newTable(formerDefinition, part2, foo2));
            latterData = merge(
                    newTable(latterDefinition, part1, foo1, bar1),
                    newTable(latterDefinition, part2, foo2, bar2));
        }

        // Infer from last key
        {
            final Table table =
                    readTable(source.getPath(), EMPTY.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            assertTableEquals(latterData, table);
        }
        // Infer from last key, refreshing
        {
            final Table table = readTable(source.getPath(),
                    REFRESHING.withLayout(ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            assertTableEquals(latterData, table);
        }
        // readTable inference readKeyValuePartitionedTable
        {
            assertTableEquals(latterData, readTable(source.getPath()));
        }

        // Explicit latter definition
        {
            final Table table = readTable(source.getPath(), EMPTY.withTableDefinitionAndLayout(latterDefinition,
                    ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            assertTableEquals(latterData, table);
        }
        // Explicit latter definition, refreshing
        {
            final Table table = readTable(source.getPath(), REFRESHING.withTableDefinitionAndLayout(latterDefinition,
                    ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            assertTableEquals(latterData, table);
        }

        // Explicit former definition
        {
            final Table table = readTable(source.getPath(), EMPTY.withTableDefinitionAndLayout(formerDefinition,
                    ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            assertTableEquals(formerData, table);
        }
        // Explicit former definition, refreshing
        {
            final Table table = readTable(source.getPath(), REFRESHING.withTableDefinitionAndLayout(formerDefinition,
                    ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            assertTableEquals(formerData, table);
        }

        // Explicit definition, empty directory
        {
            final Table table = readTable(emptySource.getPath(), EMPTY.withTableDefinitionAndLayout(latterDefinition,
                    ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            assertTableEquals(TableTools.newTable(latterDefinition), table);
        }
        // Explicit definition, empty directory, refreshing with new data added
        {
            final Table table =
                    readTable(emptySource.getPath(), REFRESHING.withTableDefinitionAndLayout(latterDefinition,
                            ParquetInstructions.ParquetFileLayout.KV_PARTITIONED));
            assertTableEquals(TableTools.newTable(latterDefinition), table);

            writeIntoEmptySource.run();
            ExecutionContext.getContext().getUpdateGraph().<ControlledUpdateGraph>cast().runWithinUnitTestCycle(() -> {
                // This is not generally a good way to do this sort of testing. Ideally, we'd be a bit smarter and use
                // a test-driven TableDataRefreshService.getSharedRefreshService.
                ((SourceTable<?>) table).tableLocationProvider().refresh();
                ((SourceTable<?>) table).refresh();
                assertTableEquals(latterData, table);
            });
        }
    }

    @Test
    public void readSingleColumn() {
        final File file = new File(rootFile, "readSingleColumn.parquet");
        final Table primitives = newTable(
                booleanCol("Bool", null, true),
                charCol("Char", NULL_CHAR, (char) 42),
                byteCol("Byte", NULL_BYTE, (byte) 42),
                shortCol("Short", NULL_SHORT, (short) 42),
                intCol("Int", NULL_INT, 42),
                longCol("Long", NULL_LONG, 42L),
                floatCol("Float", NULL_FLOAT, 42.0f),
                doubleCol("Double", NULL_DOUBLE, 42.0),
                stringCol("String", null, "42"),
                instantCol("Instant", null, Instant.ofEpochMilli(42)));
        {
            writeTable(primitives, file.getPath());
        }
        assertTableEquals(
                primitives.view("Bool"),
                readTable(file.getPath(),
                        EMPTY.withTableDefinitionAndLayout(TableDefinition.of(ColumnDefinition.ofBoolean("Bool")),
                                ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        assertTableEquals(
                primitives.view("Char"),
                readTable(file.getPath(),
                        EMPTY.withTableDefinitionAndLayout(TableDefinition.of(ColumnDefinition.ofChar("Char")),
                                ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        assertTableEquals(
                primitives.view("Byte"),
                readTable(file.getPath(),
                        EMPTY.withTableDefinitionAndLayout(TableDefinition.of(ColumnDefinition.ofByte("Byte")),
                                ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        assertTableEquals(
                primitives.view("Short"),
                readTable(file.getPath(),
                        EMPTY.withTableDefinitionAndLayout(TableDefinition.of(ColumnDefinition.ofShort("Short")),
                                ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        assertTableEquals(
                primitives.view("Int"),
                readTable(file.getPath(),
                        EMPTY.withTableDefinitionAndLayout(TableDefinition.of(ColumnDefinition.ofInt("Int")),
                                ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        assertTableEquals(
                primitives.view("Long"),
                readTable(file.getPath(),
                        EMPTY.withTableDefinitionAndLayout(TableDefinition.of(ColumnDefinition.ofLong("Long")),
                                ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        assertTableEquals(
                primitives.view("Float"),
                readTable(file.getPath(),
                        EMPTY.withTableDefinitionAndLayout(TableDefinition.of(ColumnDefinition.ofFloat("Float")),
                                ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        assertTableEquals(
                primitives.view("Double"),
                readTable(file.getPath(),
                        EMPTY.withTableDefinitionAndLayout(TableDefinition.of(ColumnDefinition.ofDouble("Double")),
                                ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        assertTableEquals(
                primitives.view("String"),
                readTable(file.getPath(),
                        EMPTY.withTableDefinitionAndLayout(TableDefinition.of(ColumnDefinition.ofString("String")),
                                ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
        assertTableEquals(
                primitives.view("Instant"),
                readTable(file.getPath(),
                        EMPTY.withTableDefinitionAndLayout(TableDefinition.of(ColumnDefinition.ofTime("Instant")),
                                ParquetInstructions.ParquetFileLayout.SINGLE_FILE)));
    }

    private void assertTableStatistics(Table inputTable, File dest) {
        // Verify that the columns have the correct statistics.
        final ParquetMetadata metadata =
                new ParquetTableLocationKey(dest.toURI(), 0, null, ParquetInstructions.EMPTY).getMetadata();

        final String[] colNames = inputTable.getDefinition().getColumnNamesArray();
        for (int colIdx = 0; colIdx < inputTable.numColumns(); ++colIdx) {
            final String colName = colNames[colIdx];

            final ColumnSource<?> columnSource = inputTable.getColumnSource(colName);
            final ColumnChunkMetaData columnChunkMetaData = metadata.getBlocks().get(0).getColumns().get(colIdx);
            final Statistics<?> statistics = columnChunkMetaData.getStatistics();

            final Class<?> csType = columnSource.getType();

            if (csType == boolean.class || csType == Boolean.class) {
                assertBooleanColumnStatistics(
                        new SerialByteColumnIterator(
                                ReinterpretUtils.booleanToByteSource((ColumnSource<Boolean>) columnSource),
                                inputTable.getRowSet()),
                        (Statistics<Boolean>) statistics);
            } else if (csType == Boolean[].class) {
                assertBooleanArrayColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<Boolean[]>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Boolean>) statistics);
            } else if (csType == byte.class || csType == Byte.class) {
                assertByteColumnStatistics(
                        new SerialByteColumnIterator(
                                (ColumnSource<Byte>) columnSource, inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == byte[].class) {
                assertByteArrayColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<byte[]>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == ByteVector.class) {
                assertByteVectorColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<ByteVector>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == char.class || csType == Character.class) {
                assertCharColumnStatistics(
                        new SerialCharacterColumnIterator(
                                (ColumnSource<Character>) columnSource, inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == char[].class) {
                assertCharArrayColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<char[]>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == CharVector.class) {
                assertCharVectorColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<CharVector>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == short.class || csType == Short.class) {
                assertShortColumnStatistics(
                        new SerialShortColumnIterator(
                                (ColumnSource<Short>) columnSource, inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == short[].class) {
                assertShortArrayColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<short[]>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == ShortVector.class) {
                assertShortVectorColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<ShortVector>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == int.class || csType == Integer.class) {
                assertIntColumnStatistics(
                        new SerialIntegerColumnIterator(
                                (ColumnSource<Integer>) columnSource, inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == int[].class) {
                assertIntArrayColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<int[]>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == IntVector.class) {
                assertIntVectorColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<IntVector>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Integer>) statistics);
            } else if (csType == long.class || csType == Long.class) {
                assertLongColumnStatistics(
                        new SerialLongColumnIterator(
                                (ColumnSource<Long>) columnSource, inputTable.getRowSet()),
                        (Statistics<Long>) statistics);
            } else if (csType == long[].class) {
                assertLongArrayColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<long[]>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Long>) statistics);
            } else if (csType == LongVector.class) {
                assertLongVectorColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<LongVector>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Long>) statistics);
            } else if (csType == float.class || csType == Float.class) {
                assertFloatColumnStatistics(
                        new SerialFloatColumnIterator(
                                (ColumnSource<Float>) columnSource, inputTable.getRowSet()),
                        (Statistics<Float>) statistics);
            } else if (csType == float[].class) {
                assertFloatArrayColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<float[]>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Float>) statistics);
            } else if (csType == FloatVector.class) {
                assertFloatVectorColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<FloatVector>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Float>) statistics);
            } else if (csType == double.class || csType == Double.class) {
                assertDoubleColumnStatistics(
                        new SerialDoubleColumnIterator(
                                (ColumnSource<Double>) columnSource, inputTable.getRowSet()),
                        (Statistics<Double>) statistics);
            } else if (csType == double[].class) {
                assertDoubleArrayColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<double[]>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Double>) statistics);
            } else if (csType == DoubleVector.class) {
                assertDoubleVectorColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<DoubleVector>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Double>) statistics);
            } else if (csType == String.class) {
                assertStringColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<String>) columnSource, inputTable.getRowSet()),
                        (Statistics<Binary>) statistics);
            } else if (csType == String[].class) {
                assertStringArrayColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<String[]>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Binary>) statistics);
            } else if (csType == ObjectVector.class && columnSource.getComponentType() == String.class) {
                assertStringVectorColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<ObjectVector<String>>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Binary>) statistics);
            } else if (csType == BigInteger.class) {
                assertBigIntegerColumnStatistics(
                        new SerialObjectColumnIterator(
                                (ColumnSource<BigInteger>) columnSource, inputTable.getRowSet()),
                        (Statistics<Binary>) statistics);
            } else if (csType == BigDecimal.class) {
                assertBigDecimalColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<BigDecimal>) columnSource, inputTable.getRowSet()),
                        (Statistics<Binary>) statistics);
            } else if (csType == Instant.class) {
                assertInstantColumnStatistic(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<Instant>) columnSource, inputTable.getRowSet()),
                        (Statistics<Long>) statistics);
            } else if (csType == Instant[].class) {
                assertInstantArrayColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<Instant[]>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Long>) statistics);
            } else if (csType == ObjectVector.class && columnSource.getComponentType() == Instant.class) {
                assertInstantVectorColumnStatistics(
                        new SerialObjectColumnIterator<>(
                                (ColumnSource<ObjectVector<Instant>>) columnSource,
                                inputTable.getRowSet()),
                        (Statistics<Long>) statistics);
            } else {
                // We can't verify statistics for this column type, so just skip it.
                System.out.println("Ignoring column " + colName + " of type " + csType.getName());
            }
        }
    }

    // region Column Statistics Assertions
    private void assertBooleanColumnStatistics(SerialByteColumnIterator iterator, Statistics<Boolean> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_BYTE);
        MutableInt max = new MutableInt(NULL_BYTE);

        iterator.forEachRemaining((ByteConsumer) value -> {
            itemCount.increment();
            if (value == NULL_BYTE) {
                nullCount.increment();
            } else {
                if (min.get() == NULL_BYTE || value < min.get()) {
                    min.set(value);
                }
                if (max.get() == NULL_BYTE || value > max.get()) {
                    max.set(value);
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get() == 1, statistics.genericGetMin());
            assertEquals(max.get() == 1, statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertBooleanArrayColumnStatistics(SerialObjectColumnIterator<Boolean[]> iterator,
            Statistics<Boolean> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_BYTE);
        MutableInt max = new MutableInt(NULL_BYTE);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            for (final Boolean value : values) {
                itemCount.increment();
                if (value == null) {
                    nullCount.increment();
                } else {
                    if (min.get() == NULL_BYTE || (value ? 1 : 0) < min.get()) {
                        min.set(value ? 1 : 0);
                    }
                    if (max.get() == NULL_BYTE || (value ? 1 : 0) > max.get()) {
                        max.set(value ? 1 : 0);
                    }
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get() == 1, statistics.genericGetMin());
            assertEquals(max.get() == 1, statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertByteColumnStatistics(SerialByteColumnIterator iterator, Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_BYTE);
        MutableInt max = new MutableInt(NULL_BYTE);

        iterator.forEachRemaining((ByteConsumer) value -> {
            itemCount.increment();
            if (value == NULL_BYTE) {
                nullCount.increment();
            } else {
                if (min.get() == NULL_BYTE || value < min.get()) {
                    min.set(value);
                }
                if (max.get() == NULL_BYTE || value > max.get()) {
                    max.set(value);
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertByteArrayColumnStatistics(SerialObjectColumnIterator<byte[]> iterator,
            Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_BYTE);
        MutableInt max = new MutableInt(NULL_BYTE);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            for (final byte value : values) {
                itemCount.increment();
                if (value == NULL_BYTE) {
                    nullCount.increment();
                } else {
                    if (min.get() == NULL_BYTE || value < min.get()) {
                        min.set(value);
                    }
                    if (max.get() == NULL_BYTE || value > max.get()) {
                        max.set(value);
                    }
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertByteVectorColumnStatistics(SerialObjectColumnIterator<ByteVector> iterator,
            Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_BYTE);
        MutableInt max = new MutableInt(NULL_BYTE);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            try (final CloseablePrimitiveIteratorOfByte valuesIterator = values.iterator()) {
                valuesIterator.forEachRemaining((final byte value) -> {
                    itemCount.increment();
                    if (value == NULL_BYTE) {
                        nullCount.increment();
                    } else {
                        if (min.get() == NULL_BYTE || value < min.get()) {
                            min.set(value);
                        }
                        if (max.get() == NULL_BYTE || value > max.get()) {
                            max.set(value);
                        }
                    }
                });
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertCharColumnStatistics(SerialCharacterColumnIterator iterator, Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_CHAR);
        MutableInt max = new MutableInt(NULL_CHAR);

        iterator.forEachRemaining((CharConsumer) value -> {
            itemCount.increment();
            if (value == NULL_CHAR) {
                nullCount.increment();
            } else {
                if (min.get() == NULL_CHAR || value < min.get()) {
                    min.set(value);
                }
                if (max.get() == NULL_CHAR || value > max.get()) {
                    max.set(value);
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertCharArrayColumnStatistics(SerialObjectColumnIterator<char[]> iterator,
            Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_CHAR);
        MutableInt max = new MutableInt(NULL_CHAR);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            for (final char value : values) {
                itemCount.increment();
                if (value == NULL_CHAR) {
                    nullCount.increment();
                } else {
                    if (min.get() == NULL_CHAR || value < min.get()) {
                        min.set(value);
                    }
                    if (max.get() == NULL_CHAR || value > max.get()) {
                        max.set(value);
                    }
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertCharVectorColumnStatistics(SerialObjectColumnIterator<CharVector> iterator,
            Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_CHAR);
        MutableInt max = new MutableInt(NULL_CHAR);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            try (final CloseablePrimitiveIteratorOfChar valuesIterator = values.iterator()) {
                valuesIterator.forEachRemaining((final char value) -> {
                    itemCount.increment();
                    if (value == NULL_CHAR) {
                        nullCount.increment();
                    } else {
                        if (min.get() == NULL_CHAR || value < min.get()) {
                            min.set(value);
                        }
                        if (max.get() == NULL_CHAR || value > max.get()) {
                            max.set(value);
                        }
                    }
                });
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertShortColumnStatistics(SerialShortColumnIterator iterator, Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_SHORT);
        MutableInt max = new MutableInt(NULL_SHORT);

        iterator.forEachRemaining((ShortConsumer) value -> {
            itemCount.increment();
            if (value == NULL_SHORT) {
                nullCount.increment();
            } else {
                if (min.get() == NULL_SHORT || value < min.get()) {
                    min.set(value);
                }
                if (max.get() == NULL_SHORT || value > max.get()) {
                    max.set(value);
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertShortArrayColumnStatistics(SerialObjectColumnIterator<short[]> iterator,
            Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_SHORT);
        MutableInt max = new MutableInt(NULL_SHORT);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            for (final short value : values) {
                itemCount.increment();
                if (value == NULL_SHORT) {
                    nullCount.increment();
                } else {
                    if (min.get() == NULL_SHORT || value < min.get()) {
                        min.set(value);
                    }
                    if (max.get() == NULL_SHORT || value > max.get()) {
                        max.set(value);
                    }
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertShortVectorColumnStatistics(SerialObjectColumnIterator<ShortVector> iterator,
            Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_SHORT);
        MutableInt max = new MutableInt(NULL_SHORT);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            try (final CloseablePrimitiveIteratorOfShort valuesIterator = values.iterator()) {
                valuesIterator.forEachRemaining((final short value) -> {
                    itemCount.increment();
                    if (value == NULL_SHORT) {
                        nullCount.increment();
                    } else {
                        if (min.get() == NULL_SHORT || value < min.get()) {
                            min.set(value);
                        }
                        if (max.get() == NULL_SHORT || value > max.get()) {
                            max.set(value);
                        }
                    }
                });
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertIntColumnStatistics(SerialIntegerColumnIterator iterator, Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_INT);
        MutableInt max = new MutableInt(NULL_INT);

        iterator.forEachRemaining((IntConsumer) value -> {
            itemCount.increment();
            if (value == NULL_INT) {
                nullCount.increment();
            } else {
                if (min.get() == NULL_INT || value < min.get()) {
                    min.set(value);
                }
                if (max.get() == NULL_INT || value > max.get()) {
                    max.set(value);
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertIntArrayColumnStatistics(SerialObjectColumnIterator<int[]> iterator,
            Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_INT);
        MutableInt max = new MutableInt(NULL_INT);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            for (final int value : values) {
                itemCount.increment();
                if (value == NULL_INT) {
                    nullCount.increment();
                } else {
                    if (min.get() == NULL_INT || value < min.get()) {
                        min.set(value);
                    }
                    if (max.get() == NULL_INT || value > max.get()) {
                        max.set(value);
                    }
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertIntVectorColumnStatistics(SerialObjectColumnIterator<IntVector> iterator,
            Statistics<Integer> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableInt min = new MutableInt(NULL_INT);
        MutableInt max = new MutableInt(NULL_INT);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            try (final CloseablePrimitiveIteratorOfInt valuesIterator = values.iterator()) {
                valuesIterator.forEachRemaining((final int value) -> {
                    itemCount.increment();
                    if (value == NULL_INT) {
                        nullCount.increment();
                    } else {
                        if (min.get() == NULL_INT || value < min.get()) {
                            min.set(value);
                        }
                        if (max.get() == NULL_INT || value > max.get()) {
                            max.set(value);
                        }
                    }
                });
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (int) statistics.genericGetMin());
            assertEquals(max.get(), (int) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertLongColumnStatistics(SerialLongColumnIterator iterator, Statistics<Long> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableLong min = new MutableLong(NULL_LONG);
        MutableLong max = new MutableLong(NULL_LONG);

        iterator.forEachRemaining((LongConsumer) value -> {
            itemCount.increment();
            if (value == NULL_LONG) {
                nullCount.increment();
            } else {
                if (min.get() == NULL_LONG || value < min.get()) {
                    min.set(value);
                }
                if (max.get() == NULL_LONG || value > max.get()) {
                    max.set(value);
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (long) statistics.genericGetMin());
            assertEquals(max.get(), (long) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertLongArrayColumnStatistics(SerialObjectColumnIterator<long[]> iterator,
            Statistics<Long> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableLong min = new MutableLong(NULL_LONG);
        MutableLong max = new MutableLong(NULL_LONG);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            for (final long value : values) {
                itemCount.increment();
                if (value == NULL_LONG) {
                    nullCount.increment();
                } else {
                    if (min.get() == NULL_LONG || value < min.get()) {
                        min.set(value);
                    }
                    if (max.get() == NULL_LONG || value > max.get()) {
                        max.set(value);
                    }
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (long) statistics.genericGetMin());
            assertEquals(max.get(), (long) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertLongVectorColumnStatistics(SerialObjectColumnIterator<LongVector> iterator,
            Statistics<Long> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableLong min = new MutableLong(NULL_LONG);
        MutableLong max = new MutableLong(NULL_LONG);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            try (final CloseablePrimitiveIteratorOfLong valuesIterator = values.iterator()) {
                valuesIterator.forEachRemaining((final long value) -> {
                    itemCount.increment();
                    if (value == NULL_LONG) {
                        nullCount.increment();
                    } else {
                        if (min.get() == NULL_LONG || value < min.get()) {
                            min.set(value);
                        }
                        if (max.get() == NULL_LONG || value > max.get()) {
                            max.set(value);
                        }
                    }
                });
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (long) statistics.genericGetMin());
            assertEquals(max.get(), (long) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertFloatColumnStatistics(SerialFloatColumnIterator iterator, Statistics<Float> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableFloat min = new MutableFloat(NULL_FLOAT);
        MutableFloat max = new MutableFloat(NULL_FLOAT);

        iterator.forEachRemaining((FloatConsumer) value -> {
            itemCount.increment();
            if (value == NULL_FLOAT) {
                nullCount.increment();
            } else {
                if (min.floatValue() == NULL_FLOAT || value < min.floatValue()) {
                    min.setValue(value);
                }
                if (max.floatValue() == NULL_FLOAT || value > max.floatValue()) {
                    max.setValue(value);
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            // Use FloatComparisons.compare() to handle -0.0f == 0.0f properly
            assertEquals(FloatComparisons.compare(min.floatValue(), statistics.genericGetMin()), 0);
            assertEquals(FloatComparisons.compare(max.floatValue(), statistics.genericGetMax()), 0);
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertFloatArrayColumnStatistics(SerialObjectColumnIterator<float[]> iterator,
            Statistics<Float> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableFloat min = new MutableFloat(NULL_FLOAT);
        MutableFloat max = new MutableFloat(NULL_FLOAT);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            for (final float value : values) {
                itemCount.increment();
                if (value == NULL_FLOAT) {
                    nullCount.increment();
                } else {
                    if (min.floatValue() == NULL_FLOAT || value < min.floatValue()) {
                        min.setValue(value);
                    }
                    if (max.floatValue() == NULL_FLOAT || value > max.floatValue()) {
                        max.setValue(value);
                    }
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            // Use FloatComparisons.compare() to handle -0.0f == 0.0f properly
            assertEquals(FloatComparisons.compare(min.floatValue(), statistics.genericGetMin()), 0);
            assertEquals(FloatComparisons.compare(max.floatValue(), statistics.genericGetMax()), 0);
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertFloatVectorColumnStatistics(SerialObjectColumnIterator<FloatVector> iterator,
            Statistics<Float> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableFloat min = new MutableFloat(NULL_FLOAT);
        MutableFloat max = new MutableFloat(NULL_FLOAT);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            try (final CloseablePrimitiveIteratorOfFloat valuesIterator = values.iterator()) {
                valuesIterator.forEachRemaining((final float value) -> {
                    itemCount.increment();
                    if (value == NULL_FLOAT) {
                        nullCount.increment();
                    } else {
                        if (min.floatValue() == NULL_FLOAT || value < min.floatValue()) {
                            min.setValue(value);
                        }
                        if (max.floatValue() == NULL_FLOAT || value > max.floatValue()) {
                            max.setValue(value);
                        }
                    }
                });
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            // Use FloatComparisons.compare() to handle -0.0f == 0.0f properly
            assertEquals(FloatComparisons.compare(min.floatValue(), statistics.genericGetMin()), 0);
            assertEquals(FloatComparisons.compare(max.floatValue(), statistics.genericGetMax()), 0);
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertDoubleColumnStatistics(SerialDoubleColumnIterator iterator, Statistics<Double> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableDouble min = new MutableDouble(NULL_DOUBLE);
        MutableDouble max = new MutableDouble(NULL_DOUBLE);

        iterator.forEachRemaining((DoubleConsumer) value -> {
            itemCount.increment();
            if (value == NULL_DOUBLE) {
                nullCount.increment();
            } else {
                if (min.doubleValue() == NULL_DOUBLE || value < min.doubleValue()) {
                    min.setValue(value);
                }
                if (max.doubleValue() == NULL_DOUBLE || value > max.doubleValue()) {
                    max.setValue(value);
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            // Use DoubleComparisons.compare() to handle -0.0f == 0.0f properly
            assertEquals(DoubleComparisons.compare(min.doubleValue(), statistics.genericGetMin()), 0);
            assertEquals(DoubleComparisons.compare(max.doubleValue(), statistics.genericGetMax()), 0);
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertDoubleArrayColumnStatistics(SerialObjectColumnIterator<double[]> iterator,
            Statistics<Double> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableDouble min = new MutableDouble(NULL_DOUBLE);
        MutableDouble max = new MutableDouble(NULL_DOUBLE);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            for (final double value : values) {
                itemCount.increment();
                if (value == NULL_DOUBLE) {
                    nullCount.increment();
                } else {
                    if (min.doubleValue() == NULL_DOUBLE || value < min.doubleValue()) {
                        min.setValue(value);
                    }
                    if (max.doubleValue() == NULL_DOUBLE || value > max.doubleValue()) {
                        max.setValue(value);
                    }
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            // Use DoubleComparisons.compare() to handle -0.0f == 0.0f properly
            assertEquals(DoubleComparisons.compare(min.doubleValue(), statistics.genericGetMin()), 0);
            assertEquals(DoubleComparisons.compare(max.doubleValue(), statistics.genericGetMax()), 0);
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertDoubleVectorColumnStatistics(SerialObjectColumnIterator<DoubleVector> iterator,
            Statistics<Double> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableDouble min = new MutableDouble(NULL_DOUBLE);
        MutableDouble max = new MutableDouble(NULL_DOUBLE);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            try (final CloseablePrimitiveIteratorOfDouble valuesIterator = values.iterator()) {
                valuesIterator.forEachRemaining((final double value) -> {
                    itemCount.increment();
                    if (value == NULL_DOUBLE) {
                        nullCount.increment();
                    } else {
                        if (min.doubleValue() == NULL_DOUBLE || value < min.doubleValue()) {
                            min.setValue(value);
                        }
                        if (max.doubleValue() == NULL_DOUBLE || value > max.doubleValue()) {
                            max.setValue(value);
                        }
                    }
                });
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            // Use DoubleComparisons.compare() to handle -0.0f == 0.0f properly
            assertEquals(DoubleComparisons.compare(min.doubleValue(), statistics.genericGetMin()), 0);
            assertEquals(DoubleComparisons.compare(max.doubleValue(), statistics.genericGetMax()), 0);
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertStringColumnStatistics(SerialObjectColumnIterator<String> iterator,
            Statistics<Binary> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableObject<String> min = new MutableObject<>(null);
        MutableObject<String> max = new MutableObject<>(null);

        iterator.forEachRemaining((value) -> {
            itemCount.increment();
            if (value == null) {
                nullCount.increment();
            } else {
                if (min.getValue() == null || value.compareTo(min.getValue()) < 0) {
                    min.setValue(value);
                }
                if (max.getValue() == null || value.compareTo(max.getValue()) > 0) {
                    max.setValue(value);
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(Binary.fromString(min.getValue()), statistics.genericGetMin());
            assertEquals(Binary.fromString(max.getValue()), statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertStringArrayColumnStatistics(SerialObjectColumnIterator<String[]> iterator,
            Statistics<Binary> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableObject<String> min = new MutableObject<>(null);
        MutableObject<String> max = new MutableObject<>(null);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            for (final String value : values) {
                itemCount.increment();
                if (value == null) {
                    nullCount.increment();
                } else {
                    if (min.getValue() == null || value.compareTo(min.getValue()) < 0) {
                        min.setValue(value);
                    }
                    if (max.getValue() == null || value.compareTo(max.getValue()) > 0) {
                        max.setValue(value);
                    }
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(Binary.fromString(min.getValue()), statistics.genericGetMin());
            assertEquals(Binary.fromString(max.getValue()), statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertStringVectorColumnStatistics(SerialObjectColumnIterator<ObjectVector<String>> iterator,
            Statistics<Binary> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableObject<String> min = new MutableObject<>(null);
        MutableObject<String> max = new MutableObject<>(null);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            try (final CloseableIterator<String> valuesIterator = values.iterator()) {
                valuesIterator.forEachRemaining((final String value) -> {
                    itemCount.increment();
                    if (value == null) {
                        nullCount.increment();
                    } else {
                        if (min.getValue() == null || value.compareTo(min.getValue()) < 0) {
                            min.setValue(value);
                        }
                        if (max.getValue() == null || value.compareTo(max.getValue()) > 0) {
                            max.setValue(value);
                        }
                    }
                });
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(Binary.fromString(min.getValue()), statistics.genericGetMin());
            assertEquals(Binary.fromString(max.getValue()), statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertInstantColumnStatistic(SerialObjectColumnIterator<Instant> iterator,
            Statistics<Long> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableLong min = new MutableLong(NULL_LONG);
        MutableLong max = new MutableLong(NULL_LONG);

        iterator.forEachRemaining((value) -> {
            itemCount.increment();
            if (value == null) {
                nullCount.increment();
            } else {
                // DateTimeUtils.epochNanos() is the correct conversion for Instant to long.
                if (min.get() == NULL_LONG || DateTimeUtils.epochNanos(value) < min.get()) {
                    min.set(DateTimeUtils.epochNanos(value));
                }
                if (max.get() == NULL_LONG || DateTimeUtils.epochNanos(value) > max.get()) {
                    max.set(DateTimeUtils.epochNanos(value));
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (long) statistics.genericGetMin());
            assertEquals(max.get(), (long) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertInstantArrayColumnStatistics(SerialObjectColumnIterator<Instant[]> iterator,
            Statistics<Long> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableLong min = new MutableLong(NULL_LONG);
        MutableLong max = new MutableLong(NULL_LONG);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            for (final Instant value : values) {
                itemCount.increment();
                if (value == null) {
                    nullCount.increment();
                } else {
                    // DateTimeUtils.epochNanos() is the correct conversion for Instant to long.
                    if (min.get() == NULL_LONG || DateTimeUtils.epochNanos(value) < min.get()) {
                        min.set(DateTimeUtils.epochNanos(value));
                    }
                    if (max.get() == NULL_LONG || DateTimeUtils.epochNanos(value) > max.get()) {
                        max.set(DateTimeUtils.epochNanos(value));
                    }
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(min.get(), (long) statistics.genericGetMin());
            assertEquals(max.get(), (long) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertInstantVectorColumnStatistics(SerialObjectColumnIterator<ObjectVector<Instant>> iterator,
            Statistics<Long> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableLong min = new MutableLong(NULL_LONG);
        MutableLong max = new MutableLong(NULL_LONG);

        iterator.forEachRemaining(values -> {
            if (values == null) {
                itemCount.increment();
                nullCount.increment();
                return;
            }
            try (final CloseableIterator<Instant> valuesIterator = values.iterator()) {
                valuesIterator.forEachRemaining((final Instant value) -> {
                    itemCount.increment();
                    if (value == null) {
                        nullCount.increment();
                    } else {
                        // DateTimeUtils.epochNanos() is the correct conversion for Instant to long.
                        if (min.get() == NULL_LONG || DateTimeUtils.epochNanos(value) < min.get()) {
                            min.set(DateTimeUtils.epochNanos(value));
                        }
                        if (max.get() == NULL_LONG || DateTimeUtils.epochNanos(value) > max.get()) {
                            max.set(DateTimeUtils.epochNanos(value));
                        }
                    }
                });
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed
            // values.
            assertEquals(min.get(), (long) statistics.genericGetMin());
            assertEquals(max.get(), (long) statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertBigDecimalColumnStatistics(SerialObjectColumnIterator<BigDecimal> iterator,
            Statistics<Binary> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableObject<BigDecimal> min = new MutableObject<>(null);
        MutableObject<BigDecimal> max = new MutableObject<>(null);

        iterator.forEachRemaining((value) -> {
            itemCount.increment();
            if (value == null) {
                nullCount.increment();
            } else {
                if (min.getValue() == null || value.compareTo(min.getValue()) < 0) {
                    min.setValue(value);
                }
                if (max.getValue() == null || value.compareTo(max.getValue()) > 0) {
                    max.setValue(value);
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(Binary.fromConstantByteArray(min.getValue().unscaledValue().toByteArray()),
                    statistics.genericGetMin());
            assertEquals(Binary.fromConstantByteArray(max.getValue().unscaledValue().toByteArray()),
                    statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }

    private void assertBigIntegerColumnStatistics(SerialObjectColumnIterator<BigInteger> iterator,
            Statistics<Binary> statistics) {
        MutableLong itemCount = new MutableLong(0);
        MutableLong nullCount = new MutableLong(0);
        MutableObject<BigInteger> min = new MutableObject<>(null);
        MutableObject<BigInteger> max = new MutableObject<>(null);

        iterator.forEachRemaining((value) -> {
            itemCount.increment();
            if (value == null) {
                nullCount.increment();
            } else {
                if (min.getValue() == null || value.compareTo(min.getValue()) < 0) {
                    min.setValue(value);
                }
                if (max.getValue() == null || value.compareTo(max.getValue()) > 0) {
                    max.setValue(value);
                }
            }
        });

        assertEquals(nullCount.get(), statistics.getNumNulls());
        if (itemCount.get() != nullCount.get()) {
            // There are some non-null values, so min and max should be non-null and equal to observed values.
            assertEquals(Binary.fromConstantByteArray(min.getValue().toByteArray()), statistics.genericGetMin());
            assertEquals(Binary.fromConstantByteArray(max.getValue().toByteArray()), statistics.genericGetMax());
        } else {
            // Everything is null, statistics should be empty.
            assertFalse(statistics.hasNonNullValue());
        }
    }
    // endregion Column Statistics Assertions

    private static Table checkSingleTable(Table expected, File source) {
        return checkSingleTable(expected, source, EMPTY);
    }

    private static Table checkSingleTable(Table expected, File source, ParquetInstructions instructions) {
        final Table singleTable =
                readTable(source.getPath(), instructions.withLayout(ParquetInstructions.ParquetFileLayout.SINGLE_FILE));
        assertTableEquals(expected, singleTable);
        // Note: we can uncomment out the below lines for extra testing of readTable inference and readSingleTable via
        // definition, but it's ultimately extra work that we've already explicitly tested.
        // TstUtils.assertTableEquals(expected, readTable(source, instructions));
        // TstUtils.assertTableEquals(expected, readSingleTable(source, instructions, expected.getDefinition()));
        return singleTable;
    }
}
