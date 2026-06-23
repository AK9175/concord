package com.collabdoc.websocket;

import com.collabdoc.document.CommittedOperation;
import com.collabdoc.document.OperationLog;
import com.collabdoc.ot.Operation;

import java.util.List;

/** Test double simulating a durable write that fails -- e.g. the database is unreachable. */
class FailingOperationLog implements OperationLog {

    @Override
    public CommittedOperation append(String documentId, Operation operation) {
        throw new RuntimeException("simulated durable-write failure");
    }

    @Override
    public List<CommittedOperation> readFrom(String documentId, long fromRevision) {
        return List.of();
    }

    @Override
    public long currentRevision(String documentId) {
        return 0;
    }
}
