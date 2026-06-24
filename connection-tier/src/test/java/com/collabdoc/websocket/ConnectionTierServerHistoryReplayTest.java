package com.collabdoc.websocket;

import com.collabdoc.document.InMemoryOperationLog;
import com.collabdoc.ot.CommittedOperation;
import com.collabdoc.ot.InsertOperation;
import com.collabdoc.ot.OperationApplier;
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
 * CP 2.5's literal verification: connect a fresh client after several edits
 * already happened; it must receive the whole op log and reconstruct the
 * identical current document by replaying it -- no snapshots.
 */
class ConnectionTierServerHistoryReplayTest {

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
        return client;
    }

    @Test
    void freshClientReceivesFullHistoryAndReconstructsCurrentText() throws Exception {
        TestClient alice = connect("doc-1");

        // Alice's own connect also delivers a "history" message (empty, since
        // nothing's committed yet) -- drain it before her edits.
        assertEquals("history", alice.nextMessage(5, TimeUnit.SECONDS).type());

        alice.send(MAPPER.writeValueAsString(new InsertOperation(0, "alice", 0, "hello")));
        alice.nextMessage(5, TimeUnit.SECONDS); // ack for revision 1

        alice.send(MAPPER.writeValueAsString(new InsertOperation(1, "alice", 5, " world")));
        alice.nextMessage(5, TimeUnit.SECONDS); // ack for revision 2

        TestClient bob = connect("doc-1");
        ServerMessage history = bob.nextMessage(5, TimeUnit.SECONDS);

        assertEquals("history", history.type());
        assertEquals("doc-1", history.documentId());
        assertEquals(2, history.committed().size());
        assertEquals(1, history.committed().get(0).revision());
        assertEquals(2, history.committed().get(1).revision());

        String reconstructed = "";
        for (CommittedOperation committed : history.committed()) {
            reconstructed = OperationApplier.apply(reconstructed, committed.operation());
        }
        assertEquals("hello world", reconstructed);

        // Bob shouldn't also get acks/updates for edits that already happened
        // before he connected -- just the one history message.
        assertNull(bob.pollMessage(500, TimeUnit.MILLISECONDS));

        alice.closeBlocking();
        bob.closeBlocking();
    }

    @Test
    void freshClientOnAnUntouchedDocumentReceivesEmptyHistory() throws Exception {
        TestClient client = connect("doc-never-touched");

        ServerMessage history = client.nextMessage(5, TimeUnit.SECONDS);

        assertEquals("history", history.type());
        assertEquals("doc-never-touched", history.documentId());
        assertTrue(history.committed().isEmpty());

        client.closeBlocking();
    }

    /** A tiny WebSocketClient that parses every received frame as a ServerMessage and queues it. */
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
