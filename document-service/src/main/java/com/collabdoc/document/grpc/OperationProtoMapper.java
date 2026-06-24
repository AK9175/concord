package com.collabdoc.document.grpc;

import com.collabdoc.ot.CommittedOperation;
import com.collabdoc.documentservice.proto.DeleteOperation;
import com.collabdoc.documentservice.proto.InsertOperation;
import com.collabdoc.documentservice.proto.Operation;

/**
 * Converts between ot-core's Operation/CommittedOperation (used by
 * DocumentSequencer/DocumentCommitter, unaware of gRPC entirely) and their
 * proto-generated wire equivalents. Kept as a small, isolated mapping layer
 * so neither side has to know about the other's representation.
 */
final class OperationProtoMapper {

    private OperationProtoMapper() {
    }

    static com.collabdoc.ot.Operation toDomain(Operation proto) {
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

    static Operation toProto(com.collabdoc.ot.Operation domain) {
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

    static com.collabdoc.documentservice.proto.CommittedOperation toProto(CommittedOperation domain) {
        return com.collabdoc.documentservice.proto.CommittedOperation.newBuilder()
                .setRevision(domain.revision())
                .setOperation(toProto(domain.operation()))
                .build();
    }
}
