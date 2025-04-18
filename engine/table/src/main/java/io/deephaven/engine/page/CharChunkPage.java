//
// Copyright (c) 2016-2025 Deephaven Data Labs and Patent Pending
//
package io.deephaven.engine.page;

import io.deephaven.base.verify.Require;
import io.deephaven.chunk.*;
import io.deephaven.chunk.attributes.Any;
import io.deephaven.engine.rowset.RowSequence;
import org.jetbrains.annotations.NotNull;

public class CharChunkPage<ATTR extends Any> extends CharChunk<ATTR> implements ChunkPage<ATTR> {

    private final long mask;
    private final long firstRow;

    public static <ATTR extends Any> CharChunkPage<ATTR> pageWrap(
            final long firstRow,
            @NotNull final char[] data,
            final int offset,
            final int capacity,
            final long mask) {
        return new CharChunkPage<>(firstRow, data, offset, capacity, mask);
    }

    public static <ATTR extends Any> CharChunkPage<ATTR> pageWrap(
            final long firstRow,
            @NotNull final char[] data,
            final long mask) {
        return new CharChunkPage<>(firstRow, data, 0, data.length, mask);
    }

    private CharChunkPage(
            final long firstRow,
            @NotNull final char[] data,
            final int offset,
            final int capacity,
            final long mask) {
        super(data, offset, capacity);
        this.mask = mask;
        this.firstRow = Require.inRange(firstRow, "firstRow", mask, "mask");
    }

    @Override
    public final void fillChunkAppend(
            @NotNull final FillContext context,
            @NotNull final WritableChunk<? super ATTR> destination,
            @NotNull final RowSequence rowSequence) {
        WritableCharChunk<? super ATTR> to = destination.asWritableCharChunk();

        if (rowSequence.getAverageRunLengthEstimate() >= Chunk.SYSTEM_ARRAYCOPY_THRESHOLD) {
            rowSequence.forAllRowKeyRanges((final long rangeStartKey, final long rangeEndKey) -> to.appendTypedChunk(
                    this, getChunkOffset(rangeStartKey), (int) (rangeEndKey - rangeStartKey + 1)));
        } else {
            rowSequence.forAllRowKeys((final long key) -> to.add(get(getChunkOffset(key))));
        }
    }

    @Override
    public final long firstRowOffset() {
        return firstRow;
    }

    @Override
    public final long mask() {
        return mask;
    }
}
