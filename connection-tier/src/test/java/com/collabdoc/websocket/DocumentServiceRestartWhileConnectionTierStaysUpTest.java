package com.collabdoc.websocket;

import com.collabdoc.document.DocumentSequencer;
import com.collabdoc.document.PostgresDataSources;
import com.collabdoc.document.PostgresOperationLog;
import com.collabdoc.document.grpc.DocumentServiceGrpcImpl;
import com.collabdoc.ot.InsertOperation;
import com.collabdoc.websocket.grpc.DocumentServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CP 6.3's genuinely new scenario: before Phase 6, document-service didn't
 * exist as an independently-restartable thing -- connection-tier and the
 * sequencer always lived (and died) in the same process. Now that they're
 * split, document-service can restart on its own while connection-tier (and
 * its already-open WebSocket connections) keep running. This proves two
 * things at once: gRPC's ManagedChannel transparently reconnects once
 * document-service comes back on the same port, and Phase 5's durability
 * guarantee survives -- the document's content isn't lost or corrupted by a
 * document-service-only restart, even though connection-tier never restarted
 * itself and still thinks the same WebSocket session is live.
 */
class DocumentServiceRestartWhileConnectionTierStaysUpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HikariDataSource dataSource;

    @BeforeAll
    static void connectOrSkip() {
        HikariDataSource candidate = PostgresDataSources.create(
                "jdbc:postgresql://localhost:5432/concord", "concord", "concord");
        boolean reachable;
        try (Connection conn = candidate.getConnection()) {
            reachable = conn.isValid(2);
        } catch (SQLException ex) {
            reachable = false;
        }
        assumeTrue(reachable, "Postgres not reachable at localhost:5432 -- run `docker compose up -d` first");
        dataSource = candidate;
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private Server documentServiceServer;
    private DocumentServiceClient documentServiceClient;
    private ConnectionTierServer server;
    private int documentServicePort;
    private int connectionTierPort;

    private Server startDocumentService(int port) throws Exception {
        DocumentSequencer sequencer = new DocumentSequencer(new PostgresOperationLog(dataSource));
        return ServerBuilder.forPort(port)
                .addService(new DocumentServiceGrpcImpl(sequencer))
                .build()
                .start();
    }

    @AfterEach
    void stopServers() throws InterruptedException {
        server.stop();
        documentServiceClient.shutdown();
        documentServiceServer.shutdownNow();
    }

    @Test
    void documentServiceCanRestartWithoutConnectionTierRestartingOrLosingData() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            documentServicePort = probe.getLocalPort();
        }
        documentServiceServer = startDocumentService(documentServicePort);
        documentServiceClient = new DocumentServiceClient("localhost", documentServicePort);

        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        server = new ConnectionTierServer(0, documentServiceClient) {
            @Override
            public void onStart() {
                super.onStart();
                started.countDown();
            }
        };
        server.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "connection-tier did not start in time");
        connectionTierPort = server.getPort();

        String documentId = "doc-" + UUID.randomUUID();
        TestClient client = connect(documentId);
        client.nextMessage(5, TimeUnit.SECONDS); // initial empty history

        client.send(MAPPER.writeValueAsString(new InsertOperation(0, "alice", 0, "Hello")));
        assertEquals("ack", client.nextMessage(5, TimeUnit.SECONDS).type());

        // Restart ONLY document-service. connection-tier (and this client's
        // already-open WebSocket connection) never restarts.
        documentServiceServer.shutdown();
        documentServiceServer.awaitTermination(5, TimeUnit.SECONDS);
        documentServiceServer = startDocumentService(documentServicePort);

        // The SAME ConnectionTierServer, SAME DocumentServiceClient, SAME
        // WebSocket connection -- but document-service underneath it is now a
        // completely different process-equivalent instance, with a fresh
        // in-memory cache, talking to the same Postgres data. Editing this
        // document now must build on the real prior content ("Hello"), not
        // corrupt it -- the same CP 5.3 guarantee, now proven to survive a
        // document-service-only restart rather than a same-process restart.
        client.send(MAPPER.writeValueAsString(new InsertOperation(1, "alice", 5, " world")));
        ServerMessage secondAck = client.nextMessage(10, TimeUnit.SECONDS);
        assertEquals("ack", secondAck.type());
        InsertOperation committedInsert = (InsertOperation) secondAck.committed().get(0).operation();
        assertEquals(5, committedInsert.position(),
                "the second edit must apply against the real prior text (\"Hello\", length 5), "
                        + "not an empty string from document-service's fresh cache after restart");

        client.closeBlocking();
    }

    private TestClient connect(String documentId) throws Exception {
        TestClient client = new TestClient(new URI("ws://localhost:" + connectionTierPort + "/" + documentId));
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), "client failed to connect");
        return client;
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
