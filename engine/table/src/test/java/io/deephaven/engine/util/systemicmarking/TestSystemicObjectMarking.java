//
// Copyright (c) 2016-2025 Deephaven Data Labs and Patent Pending
//
package io.deephaven.engine.util.systemicmarking;

import io.deephaven.engine.context.ExecutionContext;
import io.deephaven.engine.exceptions.TableAlreadyFailedException;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.impl.ErrorListener;
import io.deephaven.engine.table.impl.QueryTable;
import io.deephaven.engine.table.impl.select.FormulaEvaluationException;
import io.deephaven.engine.testutil.ControlledUpdateGraph;
import io.deephaven.engine.testutil.TstUtils;
import io.deephaven.engine.testutil.testcase.RefreshingTableTestCase;
import io.deephaven.engine.updategraph.UpdateGraph;
import io.deephaven.engine.util.TableTools;
import junit.framework.TestCase;

import java.util.List;

import static io.deephaven.engine.testutil.TstUtils.i;
import static io.deephaven.engine.util.TableTools.col;

public class TestSystemicObjectMarking extends RefreshingTableTestCase {
    public void testSystemicObjectMarking() {
        final QueryTable source = TstUtils.testRefreshingTable(col("Str", "a", "b"), col("Str2", "A", "B"));
        final Table updated = ExecutionContext.getContext().getUpdateGraph().sharedLock().computeLocked(
                () -> source.update("UC=Str.toUpperCase()"));
        final Table updated2 = SystemicObjectTracker.executeSystemically(false,
                () -> {
                    return ExecutionContext.getContext().getUpdateGraph().sharedLock()
                            .computeLocked(() -> source.update("LC=Str2.toLowerCase()"));
                });

        TableTools.showWithRowSet(updated);

        UpdateGraph updateGraph12 = ExecutionContext.getContext().getUpdateGraph();
        UpdateGraph updateGraph3 = updateGraph12.<ControlledUpdateGraph>cast();
        updateGraph3.<ControlledUpdateGraph>cast().runWithinUnitTestCycle(() -> {
            TstUtils.addToTable(source, i(2, 3), col("Str", "c", "d"), col("Str2", "C", "D"));
            source.notifyListeners(i(2, 3), i(), i());
        });

        assertFalse(updated.isFailed());
        assertFalse(updated2.isFailed());

        final ErrorListener errorListener2 = new ErrorListener(updated2);
        updated2.addUpdateListener(errorListener2);

        UpdateGraph updateGraph11 = ExecutionContext.getContext().getUpdateGraph();
        UpdateGraph updateGraph2 = updateGraph11.<ControlledUpdateGraph>cast();
        updateGraph2.<ControlledUpdateGraph>cast().runWithinUnitTestCycle(() -> {
            TstUtils.addToTable(source, i(4, 5), col("Str", "e", "f"), col("Str2", "E", null));
            source.notifyListeners(i(4, 5), i(), i());
        });

        assertFalse(updated.isFailed());
        assertTrue(updated2.isFailed());
        assertNotNull(errorListener2.originalException());
        assertEquals("In formula: LC = Str2.toLowerCase()", errorListener2.originalException().getMessage());

        try {
            updated2.addUpdateListener(new ErrorListener(updated2));
            TestCase.fail("Should not be allowed to listen to failed table");
        } catch (TableAlreadyFailedException tafe) {
            assertEquals("Can not listen to failed table QueryTable", tafe.getMessage());
        }

        final ErrorListener errorListener = new ErrorListener(updated);
        updated.addUpdateListener(errorListener);

        allowingError(() -> {
            UpdateGraph updateGraph1 = ExecutionContext.getContext().getUpdateGraph();
            UpdateGraph updateGraph = updateGraph1.<ControlledUpdateGraph>cast();
            updateGraph.<ControlledUpdateGraph>cast().runWithinUnitTestCycle(() -> {
                TstUtils.addToTable(source, i(7, 8), col("Str", "g", null), col("Str2", "G", "H"));
                source.notifyListeners(i(7, 8), i(), i());
            });
        }, TestSystemicObjectMarking::isNpe);

        assertTrue(updated.isFailed());
        assertTrue(updated2.isFailed());
        assertNotNull(errorListener.originalException());
        assertEquals("In formula: UC = Str.toUpperCase()", errorListener.originalException().getMessage());

    }


    private static boolean isNpe(List<Throwable> throwables) {
        if (1 != throwables.size()) {
            return false;
        }
        if (!throwables.get(0).getClass().equals(FormulaEvaluationException.class)) {
            return false;
        }
        if (!throwables.get(0).getMessage().equals("In formula: UC = Str.toUpperCase()")) {
            return false;
        }
        return throwables.get(0).getCause().getClass().equals(NullPointerException.class);
    }
}
