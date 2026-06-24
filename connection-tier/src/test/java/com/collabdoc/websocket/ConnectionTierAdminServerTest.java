package com.collabdoc.websocket;

import com.collabdoc.connectiontier.proto.ConnectionTierAdminGrpc;
import com.collabdoc.connectiontier.proto.EvictDocumentRequest;
import com.collabdoc.connectiontier.proto.EvictDocumentResponse;
import com.collabdoc.document.InMemoryOperationLog;
import com.collabdoc.websocket.grpc.InProcessDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delete-document feature's eviction half: anyone actively editing a
 * document that gets deleted must be told ("deleted" message) and
 * disconnected, not left talking to a document that no longer exists
 * anywhere. document-metadata-service is the real caller of EvictDocument in
 * production; this test calls it directly, the same way
 * DocumentServiceGrpcImplTest calls document-service's RPCs directly without
 * involving connection-tier.
 */
class ConnectionTierAdminServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InProcessDocumentService documentService;
    private ConnectionTierServer server;
    private int port;
    private Server adminServer;
    private ManagedChannel adminChannel;
    private ConnectionTierAdminGrpc.ConnectionTierAdminBlockingStub adminClient;

    @BeforeEach
    void startServers() throws Exception {
        documentService = new InProcessDocumentService(new InMemoryOperationLog());

        CountDownLatch started = new CountDownLatch(1);
        server = new ConnectionTierServer(0, documentService.client()) {
            @Override
            public void onStart() {
                super.onStart();
                started.countDown();
            }
        };
        server.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "connection-tier did not start in time");
        port = server.getPort();

        String adminServerName = InProcessServerBuilder.generateName();
        adminServer = InProcessServerBuilder.forName(adminServerName)
                .directExecutor()
                .addService(new ConnectionTierAdminServer(server.sessions()))
                .build()
                .start();
        adminChannel = InProcessChannelBuilder.forName(adminServerName).directExecutor().build();
        adminClient = ConnectionTierAdminGrpc.newBlockingStub(adminChannel);
    }

    @AfterEach
    void stopServers() throws InterruptedException {
        adminChannel.shutdownNow();
        adminServer.shutdownNow();
        server.stop();
        documentService.close();
    }

    private TestClient connect(String documentId) throws Exception {
        TestClient client = new TestClient(new URI("ws://localhost:" + port + "/" + documentId));
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), "client failed to connect");
        assertEquals("history", client.nextMessage(5, TimeUnit.SECONDS).type());
        return client;
    }

    @Test
    void evictingADocumentNotifiesAndDisconnectsEveryoneEditingIt() throws Exception {
        TestClient alice = connect("doc-1");
        TestClient bob = connect("doc-1");
        TestClient unrelated = connect("doc-2");

        EvictDocumentResponse response = adminClient.evictDocument(
                EvictDocumentRequest.newBuilder().setDocumentId("doc-1").build());
        assertEquals(2, response.getConnectionsClosed());

        ServerMessage aliceDeleted = alice.nextMessage(5, TimeUnit.SECONDS);
        ServerMessage bobDeleted = bob.nextMessage(5, TimeUnit.SECONDS);
        assertEquals("deleted", aliceDeleted.type());
        assertEquals("doc-1", aliceDeleted.documentId());
        assertEquals("deleted", bobDeleted.type());

        assertTrue(alice.awaitClose(5, TimeUnit.SECONDS), "alice's connection must actually close, not just get a message");
        assertTrue(bob.awaitClose(5, TimeUnit.SECONDS), "bob's connection must actually close, not just get a message");

        assertNull(unrelated.pollMessage(500, TimeUnit.MILLISECONDS),
                "a client on a different document must never be evicted or notified");

        unrelated.closeBlocking();
    }

    @Test
    void evictingADocumentWithNoActiveConnectionsIsANoOp() {
        EvictDocumentResponse response = adminClient.evictDocument(
                EvictDocumentRequest.newBuilder().setDocumentId("doc-never-opened").build());
        assertEquals(0, response.getConnectionsClosed());
    }

    private static final class TestClient extends WebSocketClient {

        private final BlockingQueue<ServerMessage> messages = new ArrayBlockingQueue<>(16);
        private final CountDownLatch closed = new CountDownLatch(1);

        TestClient(URI uri) {
            super(uri);
        }

        ServerMessage nextMessage(long timeout, TimeUnit unit) throws InterruptedException {
            ServerMessage message = messages.poll(timeout, unit);
            assertTrue(message != null, "did not receive a message in time");
            return message;
        }

        ServerMessage pollMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return messages.poll(timeout, unit);
        }

        boolean awaitClose(long timeout, TimeUnit unit) throws InterruptedException {
            return closed.await(timeout, unit);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
        }

        @Override
        public void onMessage(String message) {
            try {
                messages.add(MAPPER.readValue(message, ServerMessage.class));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closed.countDown();
        }

        @Override
        public void onError(Exception ex) {
        }
    }
}
