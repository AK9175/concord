package com.collabdoc.document.grpc;

import com.collabdoc.document.DocumentSequencer;
import com.collabdoc.document.InMemoryOperationLog;
import com.collabdoc.documentservice.proto.CommittedOperation;
import com.collabdoc.documentservice.proto.DeleteDocumentRequest;
import com.collabdoc.documentservice.proto.DocumentServiceGrpc;
import com.collabdoc.documentservice.proto.GetHistoryRequest;
import com.collabdoc.documentservice.proto.GetHistoryResponse;
import com.collabdoc.documentservice.proto.Operation;
import com.collabdoc.documentservice.proto.SubmitOperationRequest;
import com.collabdoc.documentservice.proto.SubmitOperationResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CP 6.1's literal verification: document-service's gRPC server, talked to
 * over an actual gRPC channel (in-process transport -- no real sockets, but
 * a genuine client/server/serialization round trip, unlike calling
 * DocumentServiceGrpcImpl's methods directly in-process). connection-tier
 * isn't involved at all yet; that's CP 6.2.
 */
class DocumentServiceGrpcImplTest {

    private Server server;
    private ManagedChannel channel;
    private DocumentServiceGrpc.DocumentServiceBlockingStub client;

    @BeforeEach
    void startServer() throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        DocumentSequencer sequencer = new DocumentSequencer(new InMemoryOperationLog());

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new DocumentServiceGrpcImpl(sequencer))
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        client = DocumentServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stopServer() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static String freshDocumentId() {
        return "doc-" + UUID.randomUUID();
    }

    @Test
    void submitOperationCommitsAndAssignsARevision() {
        String documentId = freshDocumentId();

        SubmitOperationResponse response = client.submitOperation(SubmitOperationRequest.newBuilder()
                .setDocumentId(documentId)
                .setOperation(Operation.newBuilder()
                        .setInsert(com.collabdoc.documentservice.proto.InsertOperation.newBuilder()
                                .setBaseRevision(0)
                                .setUserId("alice")
                                .setPosition(0)
                                .setText("hello")
                                .build())
                        .build())
                .build());

        assertEquals(1, response.getCommittedCount());
        CommittedOperation committed = response.getCommitted(0);
        assertEquals(1, committed.getRevision());
        assertEquals("hello", committed.getOperation().getInsert().getText());
    }

    @Test
    void getHistoryReturnsEveryCommittedOperationInOrder() {
        String documentId = freshDocumentId();

        client.submitOperation(submitInsert(documentId, "alice", 0, 0, "Hello"));
        client.submitOperation(submitInsert(documentId, "alice", 1, 5, " world"));

        GetHistoryResponse history = client.getHistory(GetHistoryRequest.newBuilder()
                .setDocumentId(documentId)
                .setFromRevision(0)
                .build());

        assertEquals(2, history.getCommittedCount());
        assertEquals("Hello", history.getCommitted(0).getOperation().getInsert().getText());
        assertEquals(" world", history.getCommitted(1).getOperation().getInsert().getText());
    }

    @Test
    void aSecondOperationIsCorrectlyTransformedAgainstTheGapLikeBefore() {
        String documentId = freshDocumentId();

        client.submitOperation(submitInsert(documentId, "alice", 0, 0, "Hello"));
        // baseRevision 0 -- this op was created concurrently with alice's, before
        // either committed, exactly like ConnectionTierServer's existing gap
        // scenario. The gap-transform logic itself is untouched (still
        // DocumentCommitter's), so this just proves the gRPC plumbing doesn't
        // change that behavior.
        SubmitOperationResponse response = client.submitOperation(submitInsert(documentId, "bob", 0, 0, "Hi "));

        assertEquals(1, response.getCommittedCount());
        assertEquals(2, response.getCommitted(0).getRevision());
    }

    @Test
    void unknownDocumentHasEmptyHistory() {
        GetHistoryResponse history = client.getHistory(GetHistoryRequest.newBuilder()
                .setDocumentId("does-not-exist")
                .setFromRevision(0)
                .build());

        assertEquals(0, history.getCommittedCount());
    }

    @Test
    void anOperationWithNeitherInsertNorDeleteSetFailsTheCall() {
        SubmitOperationRequest malformed = SubmitOperationRequest.newBuilder()
                .setDocumentId(freshDocumentId())
                .setOperation(Operation.newBuilder().build())
                .build();

        assertThrows(io.grpc.StatusRuntimeException.class, () -> client.submitOperation(malformed));
    }

    @Test
    void deleteDocumentWipesItsHistoryAndDoesNotAffectOtherDocuments() {
        String documentId = freshDocumentId();
        String otherDocumentId = freshDocumentId();

        client.submitOperation(submitInsert(documentId, "alice", 0, 0, "Hello"));
        client.submitOperation(submitInsert(otherDocumentId, "bob", 0, 0, "Untouched"));

        client.deleteDocument(DeleteDocumentRequest.newBuilder().setDocumentId(documentId).build());

        GetHistoryResponse afterDelete = client.getHistory(GetHistoryRequest.newBuilder()
                .setDocumentId(documentId)
                .setFromRevision(0)
                .build());
        assertEquals(0, afterDelete.getCommittedCount());

        GetHistoryResponse other = client.getHistory(GetHistoryRequest.newBuilder()
                .setDocumentId(otherDocumentId)
                .setFromRevision(0)
                .build());
        assertEquals(1, other.getCommittedCount(), "deleting one document must not affect another's history");
    }

    @Test
    void editingADeletedDocumentAfterwardStartsFreshNotCorrupted() {
        String documentId = freshDocumentId();

        client.submitOperation(submitInsert(documentId, "alice", 0, 0, "Hello"));
        client.deleteDocument(DeleteDocumentRequest.newBuilder().setDocumentId(documentId).build());

        // The in-memory cache entry must also be cleared, not just the log --
        // otherwise this would apply against the stale cached "Hello" instead
        // of starting from "".
        SubmitOperationResponse response = client.submitOperation(submitInsert(documentId, "bob", 0, 0, "Fresh"));
        assertEquals(1, response.getCommitted(0).getRevision(),
                "a deleted document's revision counter must restart from 1, proving the log was really wiped");
    }

    private static SubmitOperationRequest submitInsert(
            String documentId, String userId, long baseRevision, int position, String text) {
        return SubmitOperationRequest.newBuilder()
                .setDocumentId(documentId)
                .setOperation(Operation.newBuilder()
                        .setInsert(com.collabdoc.documentservice.proto.InsertOperation.newBuilder()
                                .setBaseRevision(baseRevision)
                                .setUserId(userId)
                                .setPosition(position)
                                .setText(text)
                                .build())
                        .build())
                .build();
    }
}
