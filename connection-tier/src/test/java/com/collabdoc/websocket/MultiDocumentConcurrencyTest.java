package com.collabdoc.websocket;

import com.collabdoc.document.InMemoryOperationLog;
import com.collabdoc.ot.InsertOperation;
import com.collabdoc.websocket.grpc.InProcessDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * CP 4.1's WebSocket-layer verification: with multiple documents being
 * edited AT THE SAME TIME, broadcasts never leak across document boundaries
 * -- a peer on doc-A must never see a doc-B message, under real concurrent
 * load, not just "one document at a time" (already covered by
 * ConnectionTierServerAckBroadcastTest.differentDocumentsDoNotCrossDeliver).
 */
class MultiDocumentConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InProcessDocumentService documentService;
    private ConnectionTierServer server;
    private int port;

    @BeforeEach
    void startServer() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        documentService = new InProcessDocumentService(new InMemoryOperationLog());
        server = new ConnectionTierServer(0, documentService.client()) {
            @Override
            public void onStart() {
                super.onStart();
                started.countDown();
            }
        };
        server.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "server did not start in time");
        port = server.getPort();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        server.stop();
        documentService.close();
    }

    private TestClient connect(String documentId) throws Exception {
        TestClient client = new TestClient(new URI("ws://localhost:" + port + "/" + documentId));
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), "client failed to connect");
        assertEquals("history", client.nextMessage(5, TimeUnit.SECONDS).type()); // drain initial history
        return client;
    }

    @Test
    void concurrentEditsAcrossManyDocumentsNeverCrossDeliver() throws Exception {
        int documentCount = 4;
        int editsPerDocument = 15;

        TestClient[] editors = new TestClient[documentCount];
        TestClient[] observers = new TestClient[documentCount];
        for (int d = 0; d < documentCount; d++) {
            String documentId = "doc-" + d;
            editors[d] = connect(documentId);
            observers[d] = connect(documentId);
        }

        // Fire edits for every document interleaved, all roughly at once, rather
        // than finishing one document's edits before starting the next.
        for (int i = 0; i < editsPerDocument; i++) {
            for (int d = 0; d < documentCount; d++) {
                String marker = "doc-" + d + "-marker-" + i + ";";
                editors[d].send(MAPPER.writeValueAsString(
                        new InsertOperation(0, "doc-" + d + "-user", 0, marker)));
            }
        }

        for (int d = 0; d < documentCount; d++) {
            String expectedDocumentId = "doc-" + d;
            for (int i = 0; i < editsPerDocument; i++) {
                ServerMessage update = observers[d].nextMessage(5, TimeUnit.SECONDS);
                assertEquals("update", update.type());
                assertEquals(expectedDocumentId, update.documentId(),
                        "observer on " + expectedDocumentId + " received a message for a different document");
                String text = ((InsertOperation) update.committed().get(0).operation()).text();
                assertTrue(text.startsWith(expectedDocumentId + "-marker-"),
                        "cross-contamination: " + expectedDocumentId + "'s observer saw \"" + text + "\"");
            }
            // Nothing else should arrive for this observer beyond its own document's edits.
            assertNull(observers[d].pollMessage(300, TimeUnit.MILLISECONDS));
        }

        for (TestClient client : editors) client.closeBlocking();
        for (TestClient client : observers) client.closeBlocking();
    }

    private static final class TestClient extends WebSocketClient {

        private final BlockingQueue<ServerMessage> messages = new ArrayBlockingQueue<>(256);

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
