//
// Copyright (c) 2016-2025 Deephaven Data Labs and Patent Pending
//
// ****** AUTO-GENERATED CLASS - DO NOT EDIT MANUALLY
// ****** Edit CharChunkWriter and run "./gradlew replicateBarrageUtils" to regenerate
//
// @formatter:off
package io.deephaven.extensions.barrage.chunk;

import io.deephaven.chunk.Chunk;
import io.deephaven.chunk.ObjectChunk;
import io.deephaven.chunk.WritableIntChunk;
import io.deephaven.chunk.attributes.Values;
import io.deephaven.engine.rowset.RowSequence;
import io.deephaven.engine.rowset.RowSet;
import com.google.common.io.LittleEndianDataOutputStream;
import io.deephaven.UncheckedDeephavenException;
import io.deephaven.extensions.barrage.BarrageOptions;
import io.deephaven.util.datastructures.LongSizedDataStructure;
import io.deephaven.chunk.IntChunk;
import io.deephaven.util.mutable.MutableInt;
import io.deephaven.util.type.TypeUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Supplier;

public class IntChunkWriter<SOURCE_CHUNK_TYPE extends Chunk<Values>> extends BaseChunkWriter<SOURCE_CHUNK_TYPE> {
    private static final String DEBUG_NAME = "IntChunkWriter";
    private static final IntChunkWriter<IntChunk<Values>> NULLABLE_IDENTITY_INSTANCE = new IntChunkWriter<>(
            null, IntChunk::getEmptyChunk, true);
    private static final IntChunkWriter<IntChunk<Values>> NON_NULLABLE_IDENTITY_INSTANCE = new IntChunkWriter<>(
            null, IntChunk::getEmptyChunk, false);

    public static IntChunkWriter<IntChunk<Values>> getIdentity(boolean isNullable) {
        return isNullable ? NULLABLE_IDENTITY_INSTANCE : NON_NULLABLE_IDENTITY_INSTANCE;
    }

    public static WritableIntChunk<Values> chunkUnboxer(
            @NotNull final ObjectChunk<Integer, Values> sourceValues) {
        final WritableIntChunk<Values> output = WritableIntChunk.makeWritableChunk(sourceValues.size());
        for (int ii = 0; ii < sourceValues.size(); ++ii) {
            output.set(ii, TypeUtils.unbox(sourceValues.get(ii)));
        }
        return output;
    }

    public IntChunkWriter(
            @Nullable final ChunkTransformer<SOURCE_CHUNK_TYPE> transformer,
            @NotNull final Supplier<SOURCE_CHUNK_TYPE> emptyChunkSupplier,
            final boolean fieldNullable) {
        super(transformer, emptyChunkSupplier, Integer.BYTES, true, fieldNullable);
    }

    public static ChunkWriter<ObjectChunk<Integer, Values>> makeBoxed(
            @NotNull final ChunkWriter<IntChunk<Values>> innerWriter) {
        return new ChunkWriter<>() {
            @Override
            public Context makeContext(@NotNull final ObjectChunk<Integer, Values> chunk, final long rowOffset) {
                return innerWriter.makeContext(chunkUnboxer(chunk), rowOffset);
            }

            @Override
            public DrainableColumn getInputStream(@NotNull Context context, @Nullable RowSet subset,
                    @NotNull BarrageOptions options) throws IOException {
                return innerWriter.getInputStream(context, subset, options);
            }

            @Override
            public DrainableColumn getEmptyInputStream(@NotNull BarrageOptions options) throws IOException {
                return innerWriter.getEmptyInputStream(options);
            }

            @Override
            public boolean isFieldNullable() {
                return innerWriter.isFieldNullable();
            }
        };
    }

    @Override
    public DrainableColumn getInputStream(
            @NotNull final Context context,
            @Nullable final RowSet subset,
            @NotNull final BarrageOptions options) throws IOException {
        return new IntChunkInputStream(context, subset, options);
    }

    @Override
    protected int computeNullCount(
            @NotNull final Context context,
            @NotNull final RowSequence subset) {
        final MutableInt nullCount = new MutableInt(0);
        final IntChunk<Values> intChunk = context.getChunk().asIntChunk();
        subset.forAllRowKeys(row -> {
            if (intChunk.isNull((int) row)) {
                nullCount.increment();
            }
        });
        return nullCount.get();
    }

    @Override
    protected void writeValidityBufferInternal(
            @NotNull final Context context,
            @NotNull final RowSequence subset,
            @NotNull final SerContext serContext) {
        final IntChunk<Values> intChunk = context.getChunk().asIntChunk();
        subset.forAllRowKeys(row -> serContext.setNextIsNull(intChunk.isNull((int) row)));
    }

    private class IntChunkInputStream extends BaseChunkInputStream<Context> {
        private IntChunkInputStream(
                @NotNull final Context context,
                @Nullable final RowSet subset,
                @NotNull final BarrageOptions options) {
            super(context, subset, options);
        }

        @Override
        public void visitFieldNodes(final FieldNodeListener listener) {
            listener.noteLogicalFieldNode(subset.intSize(DEBUG_NAME), nullCount());
        }

        @Override
        public void visitBuffers(final BufferListener listener) {
            // validity
            listener.noteLogicalBuffer(sendValidityBuffer() ? getValidityMapSerializationSizeFor(subset.intSize()) : 0);
            // payload
            listener.noteLogicalBuffer(padBufferSize(elementSize * subset.size()));
        }

        @Override
        public int drainTo(final OutputStream outputStream) throws IOException {
            if (hasBeenRead || subset.isEmpty()) {
                return 0;
            }

            long bytesWritten = 0;
            hasBeenRead = true;
            final LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(outputStream);

            // write the validity buffer
            bytesWritten += writeValidityBuffer(dos);

            // write the payload buffer
            final IntChunk<Values> intChunk = context.getChunk().asIntChunk();
            subset.forAllRowKeys(row -> {
                try {
                    dos.writeInt(intChunk.get((int) row));
                } catch (final IOException e) {
                    throw new UncheckedDeephavenException(
                            "Unexpected exception while draining data to OutputStream: ", e);
                }
            });

            bytesWritten += elementSize * subset.size();
            bytesWritten += writePadBuffer(dos, bytesWritten);
            return LongSizedDataStructure.intSize(DEBUG_NAME, bytesWritten);
        }
    }
}
