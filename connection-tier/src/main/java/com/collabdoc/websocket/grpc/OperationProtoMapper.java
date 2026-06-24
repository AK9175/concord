package com.collabdoc.websocket.grpc;

import com.collabdoc.documentservice.proto.DeleteOperation;
import com.collabdoc.documentservice.proto.InsertOperation;
import com.collabdoc.documentservice.proto.Operation;
import com.collabdoc.ot.CommittedOperation;

/**
 * Converts between ot-core's Operation/CommittedOperation (what
 * ConnectionTierServer and the browser-facing JSON protocol both use) and
 * their proto-generated wire equivalents. Deliberately a separate class
 * from document-service's identical-looking mapper, not a shared one --
 * connection-tier and document-service are genuinely independent processes
 * now, coupled only by the proto contract, not by Java code.
 */
public final class OperationProtoMapper {

    private OperationProtoMapper() {
    }

    public static com.collabdoc.ot.Operation toDomain(Operation proto) {
        return switch (proto.getKindCase()) {
            case INSERT -> {
                InsertOperation insert = proto.getInsert();
                yield new com.collabdoc.ot.InsertOperation(
                        insert.getBaseRevision(), insert.getUserId(), insert.getPosition(), insert.getText());
            }
            case DELETE -> {
                DeleteOperation delete = proto.getDelete();
                yield new com.collabdoc.ot.DeleteOperation(
                        delete.getBaseRevision(), delete.getUserId(), delete.getPosition(), delete.getLength());
            }
            case KIND_NOT_SET -> throw new IllegalArgumentException("Operation proto has neither insert nor delete set");
        };
    }

    public static Operation toProto(com.collabdoc.ot.Operation domain) {
        return switch (domain) {
            case com.collabdoc.ot.InsertOperation insert -> Operation.newBuilder()
                    .setInsert(InsertOperation.newBuilder()
                            .setBaseRevision(insert.baseRevision())
                            .setUserId(insert.userId())
                            .setPosition(insert.position())
                            .setText(insert.text())
                            .build())
                    .build();
            case com.collabdoc.ot.DeleteOperation delete -> Operation.newBuilder()
                    .setDelete(DeleteOperation.newBuilder()
                            .setBaseRevision(delete.baseRevision())
                            .setUserId(delete.userId())
                            .setPosition(delete.position())
                            .setLength(delete.length())
                            .build())
                    .build();
        };
    }

    public static CommittedOperation toDomain(com.collabdoc.documentservice.proto.CommittedOperation proto) {
        return new CommittedOperation(proto.getRevision(), toDomain(proto.getOperation()));
    }
}
