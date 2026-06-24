package com.collabdoc.websocket;

import com.collabdoc.document.DocumentSequencer;
import com.collabdoc.document.InMemoryOperationLog;
import com.collabdoc.document.grpc.DocumentServiceGrpcImpl;
import com.collabdoc.ot.InsertOperation;
import com.collabdoc.websocket.grpc.DocumentServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CP 6.3: every other connection-tier test exercises document-service over
 * grpc-java's IN-PROCESS transport (InProcessDocumentService) -- fast, but
 * it never actually serializes a request onto a real socket. This test uses
 * a REAL TCP port for the gRPC server, exactly like the real two-process
 * deployment, to close that gap: ack-then-broadcast ordering and multi-
 * document isolation still hold once an actual network stack (loopback, but
 * a real one) is involved, not just the in-memory pipe in-process tests use.
 */
class RealSocketGrpcTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Server documentServiceServer;
    private DocumentServiceClient documentServiceClient;
    private ConnectionTierServer server;
    private int port;

    @BeforeEach
    void startServers() throws Exception {
        int documentServicePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            documentServicePort = probe.getLocalPort();
        }

        DocumentSequencer sequencer = new DocumentSequencer(new InMemoryOperationLog());
        documentServiceServer = ServerBuilder.forPort(documentServicePort)
                .addService(new DocumentServiceGrpcImpl(sequencer))
                .build()
                .start();

        documentServiceClient = new DocumentServiceClient("localhost", documentServicePort);

        CountDownLatch started = new CountDownLatch(1);
        server = new ConnectionTierServer(0, documentServiceClient) {
            @Override
            public void onStart() {
                super.onStart();
                started.countDown();
            }
        };
        server.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "connection-tier did not start in time");
        port = server.getPort();
    }

    @AfterEach
    void stopServers() throws InterruptedException {
        server.stop();
        documentServiceClient.shutdown();
        documentServiceServer.shutdownNow();
    }

    private TestClient connect(String documentId) throws Exception {
        TestClient client = new TestClient(new URI("ws://localhost:" + port + "/" + documentId));
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), "client failed to connect");
        assertEquals("history", client.nextMessage(5, TimeUnit.SECONDS).type());
        return client;
    }

    @Test
    void ackThenBroadcastOrderingHoldsOverARealSocket() throws Exception {
        TestClient alice = connect("doc-1");
        TestClient bob = connect("doc-1");

        alice.send(MAPPER.writeValueAsString(new InsertOperation(0, "alice", 0, "hello")));

        ServerMessage aliceAck = alice.nextMessage(5, TimeUnit.SECONDS);
        ServerMessage bobUpdate = bob.nextMessage(5, TimeUnit.SECONDS);

        assertEquals("ack", aliceAck.type());
        assertEquals(1, aliceAck.committed().get(0).revision());
        assertEquals("update", bobUpdate.type());
        assertEquals(aliceAck.committed(), bobUpdate.committed());
        assertNull(alice.pollMessage(500, TimeUnit.MILLISECONDS), "sender must not see her own op echoed back");

        alice.closeBlocking();
        bob.closeBlocking();
    }

    @Test
    void multiDocumentIsolationHoldsOverARealSocket() throws Exception {
        TestClient aliceOnDocA = connect("doc-A");
        TestClient bobOnDocB = connect("doc-B");

        aliceOnDocA.send(MAPPER.writeValueAsString(new InsertOperation(0, "alice", 0, "hello")));
        assertEquals("ack", aliceOnDocA.nextMessage(5, TimeUnit.SECONDS).type());

        assertNull(bobOnDocB.pollMessage(500, TimeUnit.MILLISECONDS),
                "a client on a different document must never see another document's edit");

        aliceOnDocA.closeBlocking();
        bobOnDocB.closeBlocking();
    }

    private static final class TestClient extends WebSocketClient {

        private final BlockingQueue<ServerMessage> messages = new ArrayBlockingQueue<>(16);

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
        }

        @Override
        public void onError(Exception ex) {
        }
    }
}
