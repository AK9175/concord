package com.collabdoc.metadata.grpc;

import com.collabdoc.connectiontier.proto.ConnectionTierAdminGrpc;
import com.collabdoc.connectiontier.proto.EvictDocumentRequest;
import com.collabdoc.connectiontier.proto.EvictDocumentResponse;
import com.collabdoc.documentservice.proto.DeleteDocumentRequest;
import com.collabdoc.documentservice.proto.DeleteDocumentResponse;
import com.collabdoc.documentservice.proto.DocumentServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only stand-ins for the real document-service and connection-tier
 * admin gRPC servers DocumentMetadataServer's delete flow calls. Unlike
 * InProcessDocumentService (connection-tier's test helper, which wraps a
 * REAL DocumentSequencer), these are minimal hand-rolled fakes -- the actual
 * behavior of DeleteDocument and EvictDocument is already covered by
 * DocumentServiceGrpcImplTest and ConnectionTierAdminServerTest in their own
 * modules. What THIS test needs to verify is document-metadata-service's
 * own orchestration logic (call order, what happens when one call fails),
 * which only needs to observe that the right calls happened, not re-execute
 * their real implementations.
 */
public class InProcessDeleteBackends implements AutoCloseable {

    private final Server documentServiceServer;
    private final Server connectionTierServer;
    private final ManagedChannel documentServiceChannel;
    private final ManagedChannel connectionTierChannel;
    private final FakeDocumentService fakeDocumentService = new FakeDocumentService();
    private final FakeConnectionTierAdmin fakeConnectionTierAdmin = new FakeConnectionTierAdmin();

    public InProcessDeleteBackends() {
        try {
            String documentServiceName = InProcessServerBuilder.generateName();
            documentServiceServer = InProcessServerBuilder.forName(documentServiceName)
                    .directExecutor()
                    .addService(fakeDocumentService)
                    .build()
                    .start();
            documentServiceChannel = InProcessChannelBuilder.forName(documentServiceName).directExecutor().build();

            String connectionTierName = InProcessServerBuilder.generateName();
            connectionTierServer = InProcessServerBuilder.forName(connectionTierName)
                    .directExecutor()
                    .addService(fakeConnectionTierAdmin)
                    .build()
                    .start();
            connectionTierChannel = InProcessChannelBuilder.forName(connectionTierName).directExecutor().build();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("failed to start in-process fake backends", ex);
        }
    }

    public DocumentServiceDeleteClient documentServiceClient() {
        return new DocumentServiceDeleteClient(documentServiceChannel);
    }

    public ConnectionTierEvictClient connectionTierClient() {
        return new ConnectionTierEvictClient(connectionTierChannel);
    }

    public List<String> deletedDocumentIds() {
        return fakeDocumentService.deletedDocumentIds;
    }

    public List<String> evictedDocumentIds() {
        return fakeConnectionTierAdmin.evictedDocumentIds;
    }

    public void failNextDelete() {
        fakeDocumentService.failNext = true;
    }

    @Override
    public void close() {
        documentServiceChannel.shutdownNow();
        connectionTierChannel.shutdownNow();
        documentServiceServer.shutdownNow();
        connectionTierServer.shutdownNow();
    }

    private static final class FakeDocumentService extends DocumentServiceGrpc.DocumentServiceImplBase {
        final List<String> deletedDocumentIds = new CopyOnWriteArrayList<>();
        volatile boolean failNext = false;

        @Override
        public void deleteDocument(DeleteDocumentRequest request, StreamObserver<DeleteDocumentResponse> responseObserver) {
            if (failNext) {
                failNext = false;
                responseObserver.onError(new RuntimeException("simulated document-service failure"));
                return;
            }
            deletedDocumentIds.add(request.getDocumentId());
            responseObserver.onNext(DeleteDocumentResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    private static final class FakeConnectionTierAdmin extends ConnectionTierAdminGrpc.ConnectionTierAdminImplBase {
        final List<String> evictedDocumentIds = new CopyOnWriteArrayList<>();

        @Override
        public void evictDocument(EvictDocumentRequest request, StreamObserver<EvictDocumentResponse> responseObserver) {
            evictedDocumentIds.add(request.getDocumentId());
            responseObserver.onNext(EvictDocumentResponse.newBuilder().setConnectionsClosed(0).build());
            responseObserver.onCompleted();
        }
    }
}
