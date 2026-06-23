package com.collabdoc.websocket;

import com.collabdoc.document.DocumentSequencer;
import com.collabdoc.document.InMemoryOperationLog;
import com.collabdoc.ot.InsertOperation;
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

class ConnectionTierServerAckBroadcastTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConnectionTierServer server;
    private int port;

    @BeforeEach
    void startServer() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        DocumentSequencer sequencer = new DocumentSequencer(new InMemoryOperationLog());
        server = new ConnectionTierServer(0, sequencer) { // 0 = let the OS pick a free port
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
    }

    private TestClient connect(String documentId) throws Exception {
        TestClient client = new TestClient(new URI("ws://localhost:" + port + "/" + documentId));
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), "client failed to connect");
        // Every connect now also delivers a "history" message (CP 2.5) -- drain it
        // here so the rest of this test file's ack/update assertions are unaffected.
        assertEquals("history", client.nextMessage(5, TimeUnit.SECONDS).type());
        return client;
    }

    @Test
    void editorsEditReachesOtherClientAsUpdateAndReturnsAsAck() throws Exception {
        TestClient alice = connect("doc-1");
        TestClient bob = connect("doc-1");

        alice.send(MAPPER.writeValueAsString(new InsertOperation(0, "alice", 0, "hello")));

        ServerMessage aliceAck = alice.nextMessage(5, TimeUnit.SECONDS);
        ServerMessage bobUpdate = bob.nextMessage(5, TimeUnit.SECONDS);

        assertEquals("ack", aliceAck.type());
        assertEquals("doc-1", aliceAck.documentId());
        assertEquals(1, aliceAck.committed().size());
        assertEquals(1, aliceAck.committed().get(0).revision());

        assertEquals("update", bobUpdate.type());
        assertEquals("doc-1", bobUpdate.documentId());
        assertEquals(aliceAck.committed(), bobUpdate.committed());

        // Alice must not also receive her own op echoed back as an "update".
        assertNull(alice.pollMessage(500, TimeUnit.MILLISECONDS));

        alice.closeBlocking();
        bob.closeBlocking();
    }

    @Test
    void differentDocumentsDoNotCrossDeliver() throws Exception {
        TestClient alice = connect("doc-A");
        TestClient bob = connect("doc-B");

        alice.send(MAPPER.writeValueAsString(new InsertOperation(0, "alice", 0, "hello")));

        ServerMessage aliceAck = alice.nextMessage(5, TimeUnit.SECONDS);
        assertEquals("ack", aliceAck.type());

        // Bob is on a different document entirely; he should never hear about this.
        assertNull(bob.pollMessage(500, TimeUnit.MILLISECONDS));

        alice.closeBlocking();
        bob.closeBlocking();
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
