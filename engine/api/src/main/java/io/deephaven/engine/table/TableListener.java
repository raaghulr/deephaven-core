//
// Copyright (c) 2016-2025 Deephaven Data Labs and Patent Pending
//
package io.deephaven.engine.table;

import io.deephaven.base.log.LogOutputAppendable;
import io.deephaven.engine.updategraph.NotificationQueue;
import io.deephaven.engine.liveness.LivenessNode;
import org.jetbrains.annotations.Nullable;

/**
 * Listener implementation for {@link Table} changes.
 */
public interface TableListener extends LivenessNode {

    /**
     * Notification of exceptions.
     *
     * @param originalException exception
     * @param sourceEntry performance tracking
     */
    void onFailure(Throwable originalException, @Nullable Entry sourceEntry);

    /**
     * Creates a notification for the exception.
     *
     * @param originalException exception
     * @param sourceEntry performance tracking
     * @return exception notification
     */
    NotificationQueue.ErrorNotification getErrorNotification(Throwable originalException, @Nullable Entry sourceEntry);

    /**
     * Interface for instrumentation entries used by update graph nodes.
     */
    interface Entry extends LogOutputAppendable {
    }
}
