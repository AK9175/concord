package com.collabdoc.document.grpc;

import com.collabdoc.ot.CommittedOperation;
import com.collabdoc.document.DocumentSequencer;
import com.collabdoc.documentservice.proto.DeleteDocumentRequest;
import com.collabdoc.documentservice.proto.DeleteDocumentResponse;
import com.collabdoc.documentservice.proto.DocumentServiceGrpc;
import com.collabdoc.documentservice.proto.GetHistoryRequest;
import com.collabdoc.documentservice.proto.GetHistoryResponse;
import com.collabdoc.documentservice.proto.SubmitOperationRequest;
import com.collabdoc.documentservice.proto.SubmitOperationResponse;
import io.grpc.stub.StreamObserver;

import java.util.List;

/**
 * The gRPC server side of Phase 6's split: a thin network-facing wrapper
 * around DocumentSequencer, which is completely unaware that gRPC exists.
 * Every actual correctness property (sequencing, gap-transform, durability)
 * still lives in DocumentSequencer/DocumentCommitter exactly as before --
 * this class only translates proto requests into the same calls
 * ConnectionTierServer used to make in-process, and translates the
 * CompletableFuture result back into gRPC's StreamObserver callback style.
 *
 * Deliberately does NOT expose DocumentSequencer.connect() (the
 * register-as-peer + atomic history read) -- "registerAsPeer" is
 * connection-tier's own local WebSocket broadcast bookkeeping, which
 * document-service has no reason to know about over the network. GetHistory
 * here is connect()'s actual cross-process need, history-only.
 */
public class DocumentServiceGrpcImpl extends DocumentServiceGrpc.DocumentServiceImplBase {

    private final DocumentSequencer sequencer;

    public DocumentServiceGrpcImpl(DocumentSequencer sequencer) {
        this.sequencer = sequencer;
    }

    @Override
    public void submitOperation(SubmitOperationRequest request, StreamObserver<SubmitOperationResponse> responseObserver) {
        com.collabdoc.ot.Operation operation = OperationProtoMapper.toDomain(request.getOperation());
        sequencer.submit(request.getDocumentId(), operation)
                .thenAccept(committed -> {
                    responseObserver.onNext(toSubmitResponse(committed));
                    responseObserver.onCompleted();
                })
                .exceptionally(ex -> {
                    responseObserver.onError(ex);
                    return null;
                });
    }

    @Override
    public void getHistory(GetHistoryRequest request, StreamObserver<GetHistoryResponse> responseObserver) {
        // fromRevision is accepted for symmetry with OperationLog.readFrom, but
        // DocumentSequencer.history() (unlike readFrom) always returns the full
        // log from revision 0 -- callers needing a partial slice (none today)
        // would filter client-side. This matches the one caller this RPC has:
        // ConnectionTierServer's connect/resync, which always wants full history.
        sequencer.history(request.getDocumentId())
                .thenAccept(committed -> {
                    responseObserver.onNext(toHistoryResponse(committed));
                    responseObserver.onCompleted();
                })
                .exceptionally(ex -> {
                    responseObserver.onError(ex);
                    return null;
                });
    }

    @Override
    public void deleteDocument(DeleteDocumentRequest request, StreamObserver<DeleteDocumentResponse> responseObserver) {
        sequencer.delete(request.getDocumentId())
                .thenAccept(ignored -> {
                    responseObserver.onNext(DeleteDocumentResponse.getDefaultInstance());
                    responseObserver.onCompleted();
                })
                .exceptionally(ex -> {
                    responseObserver.onError(ex);
                    return null;
                });
    }

    private static SubmitOperationResponse toSubmitResponse(List<CommittedOperation> committed) {
        SubmitOperationResponse.Builder builder = SubmitOperationResponse.newBuilder();
        for (CommittedOperation op : committed) {
            builder.addCommitted(OperationProtoMapper.toProto(op));
        }
        return builder.build();
    }

    private static GetHistoryResponse toHistoryResponse(List<CommittedOperation> committed) {
        GetHistoryResponse.Builder builder = GetHistoryResponse.newBuilder();
        for (CommittedOperation op : committed) {
            builder.addCommitted(OperationProtoMapper.toProto(op));
        }
        return builder.build();
    }
}
