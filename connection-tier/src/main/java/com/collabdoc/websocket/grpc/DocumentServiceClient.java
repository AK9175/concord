package com.collabdoc.websocket.grpc;

import com.collabdoc.documentservice.proto.DocumentServiceGrpc;
import com.collabdoc.documentservice.proto.GetHistoryRequest;
import com.collabdoc.documentservice.proto.GetHistoryResponse;
import com.collabdoc.documentservice.proto.SubmitOperationRequest;
import com.collabdoc.documentservice.proto.SubmitOperationResponse;
import com.collabdoc.ot.CommittedOperation;
import com.collabdoc.ot.Operation;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * connection-tier's gRPC client side of Phase 6's split: everywhere
 * ConnectionTierServer used to call DocumentSequencer directly (an
 * in-process Java method call), it now calls this instead, which makes the
 * exact same logical request over the network. Returns CompletableFuture,
 * same as DocumentSequencer did, so ConnectionTierServer's
 * thenAccept/exceptionally chains didn't need to change shape -- only what's
 * on the other end of the call did.
 */
public class DocumentServiceClient {

    private final ManagedChannel channel;
    private final DocumentServiceGrpc.DocumentServiceStub stub;

    public DocumentServiceClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build());
    }

    /** Package-visible for tests, which build a channel over an in-process transport rather than a real socket. */
    DocumentServiceClient(ManagedChannel channel) {
        this.channel = channel;
        this.stub = DocumentServiceGrpc.newStub(channel);
    }

    public CompletableFuture<List<CommittedOperation>> submit(String documentId, Operation operation) {
        SubmitOperationRequest request = SubmitOperationRequest.newBuilder()
                .setDocumentId(documentId)
                .setOperation(OperationProtoMapper.toProto(operation))
                .build();

        CompletableFuture<SubmitOperationResponse> future = new CompletableFuture<>();
        stub.submitOperation(request, toObserver(future));
        return future.thenApply(response -> response.getCommittedList().stream()
                .map(OperationProtoMapper::toDomain)
                .toList());
    }

    public CompletableFuture<List<CommittedOperation>> history(String documentId) {
        GetHistoryRequest request = GetHistoryRequest.newBuilder()
                .setDocumentId(documentId)
                .setFromRevision(0)
                .build();

        CompletableFuture<GetHistoryResponse> future = new CompletableFuture<>();
        stub.getHistory(request, toObserver(future));
        return future.thenApply(response -> response.getCommittedList().stream()
                .map(OperationProtoMapper::toDomain)
                .toList());
    }

    public void shutdown() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> StreamObserver<T> toObserver(CompletableFuture<T> future) {
        return new StreamObserver<>() {
            @Override
            public void onNext(T value) {
                future.complete(value);
            }

            @Override
            public void onError(Throwable t) {
                future.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
            }
        };
    }
}
