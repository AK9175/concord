package com.collabdoc.websocket;

import com.collabdoc.document.PostgresDataSources;
import com.collabdoc.document.PostgresOperationLog;
import com.collabdoc.ot.CommittedOperation;
import com.collabdoc.ot.InsertOperation;
import com.collabdoc.ot.OperationApplier;
import com.collabdoc.websocket.grpc.InProcessDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CP 5.3's verification through the actual WebSocket layer (not just the
 * bare DocumentSequencer API): make several edits, stop the server, start a
 * completely new one against the same Postgres data, reconnect, and confirm
 * a fresh client loads the identical document AND can keep editing it
 * correctly -- the real, end-to-end durability proof.
 */
class ServerRestartDurabilityTest {

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

    private static ServerHandle startServer() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        InProcessDocumentService documentService = new InProcessDocumentService(new PostgresOperationLog(dataSource));
        ConnectionTierServer server = new ConnectionTierServer(0, documentService.client()) {
            @Override
            public void onStart() {
                super.onStart();
                started.countDown();
            }
        };
        server.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "server did not start in time");
        return new ServerHandle(server, documentService);
    }

    private record ServerHandle(ConnectionTierServer server, InProcessDocumentService documentService) {
        void stop() throws InterruptedException {
            server.stop();
            documentService.close();
        }
    }

    @Test
    void editsSurviveAFullServerRestartAndReconnect() throws Exception {
        String documentId = "doc-" + UUID.randomUUID();

        // Server instance #1: make several edits, then stop it entirely.
        ServerHandle firstServer = startServer();
        TestClient firstClient = connect(firstServer.server().getPort(), documentId);
        firstClient.nextMessage(5, TimeUnit.SECONDS); // initial (empty) history

        firstClient.send(MAPPER.writeValueAsString(new InsertOperation(0, "alice", 0, "Hello")));
        firstClient.nextMessage(5, TimeUnit.SECONDS); // ack
        firstClient.send(MAPPER.writeValueAsString(new InsertOperation(1, "alice", 5, " world")));
        firstClient.nextMessage(5, TimeUnit.SECONDS); // ack

        firstClient.closeBlocking();
        firstServer.stop();

        // Server instance #2: a completely separate process, in spirit -- new
        // InProcessDocumentService (its own gRPC server + DocumentSequencer +
        // in-memory cache), same Postgres database.
        ServerHandle secondServer = startServer();
        TestClient secondClient = connect(secondServer.server().getPort(), documentId);

        ServerMessage history = secondClient.nextMessage(5, TimeUnit.SECONDS);
        assertEquals("history", history.type());
        String replayed = "";
        for (CommittedOperation committed : history.committed()) {
            replayed = OperationApplier.apply(replayed, committed.operation());
        }
        assertEquals("Hello world", replayed, "a fresh client after restart must see the identical prior document");

        // The critical case: editing after the restart must build on the REAL
        // prior content. Before the CP 5.3 fix, the server's cache would have
        // been empty, corrupting this edit (or throwing, for an out-of-bounds
        // position against "").
        secondClient.send(MAPPER.writeValueAsString(new InsertOperation(2, "bob", 11, "!")));
        ServerMessage ack = secondClient.nextMessage(5, TimeUnit.SECONDS);
        assertEquals("ack", ack.type());
        InsertOperation committedInsert = (InsertOperation) ack.committed().get(0).operation();
        assertEquals(11, committedInsert.position());

        secondClient.closeBlocking();
        secondServer.stop();
    }

    private static TestClient connect(int port, String documentId) throws Exception {
        TestClient client = new TestClient(new URI("ws://localhost:" + port + "/" + documentId));
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
