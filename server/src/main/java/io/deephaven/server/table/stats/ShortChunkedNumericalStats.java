//
// Copyright (c) 2016-2025 Deephaven Data Labs and Patent Pending
//
package io.deephaven.server.table.stats;

import io.deephaven.chunk.ShortChunk;
import io.deephaven.chunk.attributes.Values;
import io.deephaven.engine.rowset.RowSequence;
import io.deephaven.engine.rowset.RowSet;
import io.deephaven.engine.table.ChunkSource;
import io.deephaven.engine.table.ColumnSource;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.util.TableTools;
import io.deephaven.util.QueryConstants;

public class ShortChunkedNumericalStats implements ChunkedNumericalStatsKernel {
    private long count = 0;

    private long sum = 0;
    private boolean useFloatingSum = false;
    private double floatingSum = .0;

    private long absSum = 0;
    private boolean useFloatingAbsSum = false;
    private double floatingAbsSum = .0;

    private long sumOfSquares = 0;
    private boolean useFloatingSumOfSquares = false;
    private double floatingSumOfSquares = .0;

    private short min = QueryConstants.NULL_SHORT;
    private short max = QueryConstants.NULL_SHORT;
    private short absMin = QueryConstants.NULL_SHORT;
    private short absMax = QueryConstants.NULL_SHORT;

    @Override
    public Table processChunks(final RowSet rowSet, final ColumnSource<?> columnSource, boolean usePrev) {

        try (final ChunkSource.GetContext getContext = columnSource.makeGetContext(CHUNK_SIZE)) {
            final RowSequence.Iterator rsIt = rowSet.getRowSequenceIterator();

            while (rsIt.hasMore()) {
                final RowSequence nextKeys = rsIt.getNextRowSequenceWithLength(CHUNK_SIZE);
                final ShortChunk<? extends Values> chunk = (usePrev ? columnSource.getPrevChunk(getContext, nextKeys)
                        : columnSource.getChunk(getContext, nextKeys)).asShortChunk();

                /*
                 * we'll use these to get as big as we can before adding into a potentially MUCH larger "total" in an
                 * attempt to reduce cumulative loss-of-precision error brought on by floating-point math; - but ONLY if
                 * we've overflowed our non-floating-point (long)
                 */
                double chunkedOverflowSum = .0;
                double chunkedOverflowAbsSum = .0;
                double chunkedOverflowSumOfSquares = .0;

                final int chunkSize = chunk.size();
                for (int ii = 0; ii < chunkSize; ii++) {
                    final short val = chunk.get(ii);

                    if (val == QueryConstants.NULL_SHORT) {
                        continue;
                    }

                    final short absVal = (short) Math.abs(val);

                    if (count == 0) {
                        min = max = val;
                        absMax = absMin = absVal;
                    } else {
                        if (val < min) {
                            min = val;
                        }

                        if (val > max) {
                            max = val;
                        }

                        if (absVal < absMin) {
                            absMin = absVal;
                        }

                        if (absVal > absMax) {
                            absMax = absVal;
                        }
                    }

                    count++;

                    if (!useFloatingSum) {
                        try {
                            sum = Math.addExact(sum, val);
                        } catch (final ArithmeticException ae) {
                            useFloatingSum = true;
                            floatingSum = sum;
                            chunkedOverflowSum = val;
                        }
                    } else {
                        chunkedOverflowSum += val;
                    }

                    if (!useFloatingAbsSum) {
                        try {
                            absSum = Math.addExact(absSum, absVal);
                        } catch (final ArithmeticException ae) {
                            useFloatingAbsSum = true;
                            floatingAbsSum = absSum;
                            chunkedOverflowAbsSum = absVal;
                        }
                    } else {
                        chunkedOverflowAbsSum += absVal;
                    }

                    if (!useFloatingSumOfSquares) {
                        try {
                            sumOfSquares = Math.addExact(sumOfSquares, Math.multiplyExact(val, val));
                        } catch (final ArithmeticException ae) {
                            useFloatingSumOfSquares = true;
                            floatingSumOfSquares = sumOfSquares;
                            chunkedOverflowSumOfSquares = Math.pow(val, 2);
                        }

                    } else {
                        chunkedOverflowSumOfSquares += Math.pow(val, 2);
                    }
                }

                if (useFloatingSum) {
                    floatingSum += chunkedOverflowSum;
                }

                if (useFloatingAbsSum) {
                    floatingAbsSum += chunkedOverflowAbsSum;
                }

                if (useFloatingSumOfSquares) {
                    floatingSumOfSquares += chunkedOverflowSumOfSquares;
                }
            }
        }

        double avg = avg(count, useFloatingSum ? floatingSum : sum);
        return TableTools.newTable(
                TableTools.longCol("COUNT", count),
                TableTools.longCol("SIZE", rowSet.size()),
                useFloatingSum ? TableTools.doubleCol("SUM", floatingSum) : TableTools.longCol("SUM", sum),
                useFloatingAbsSum ? TableTools.doubleCol("SUM_ABS", floatingAbsSum)
                        : TableTools.longCol("SUM_ABS", absSum),
                useFloatingSumOfSquares ? TableTools.doubleCol("SQRD_SUM", floatingSumOfSquares)
                        : TableTools.longCol("SUM_SQRD", sumOfSquares),
                TableTools.shortCol("MIN", min),
                TableTools.shortCol("MAX", max),
                TableTools.shortCol("MIN_ABS", absMin),
                TableTools.shortCol("MAX_ABS", absMax),
                TableTools.doubleCol("AVG", avg),
                TableTools.doubleCol("AVG_ABS", avg(count, absSum)),
                TableTools.doubleCol("STD_DEV", stdDev(count, useFloatingSum ? floatingSum : sum,
                        useFloatingSumOfSquares ? floatingSumOfSquares : sumOfSquares)));
    }
}
