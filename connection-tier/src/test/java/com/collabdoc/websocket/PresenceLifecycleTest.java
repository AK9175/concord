package com.collabdoc.websocket;

import com.collabdoc.document.InMemoryOperationLog;
import com.collabdoc.websocket.grpc.InProcessDocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CP 4.3's literal verification: a tab joining sees existing presence;
 * opening a second tab under the same identity does NOT create a second
 * presence entry for others; closing one of two same-identity tabs does NOT
 * remove that user; closing the last one does.
 */
class PresenceLifecycleTest {

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
        assertEquals("history", client.nextMessage(5, TimeUnit.SECONDS).path("type").asText()); // drain history
        return client;
    }

    private void announcePresence(TestClient client, String userId, String username, String color) throws Exception {
        client.send(MAPPER.writeValueAsString(new PresenceMessage("presence", null, userId, username, color, null, null)));
    }

    @Test
    void joiningClientSeesExistingPresenceAndOthersSeeTheNewJoin() throws Exception {
        TestClient alice = connect("doc-1");
        announcePresence(alice, "u-alice", "Alice", "#ff0000");
        JsonNode aliceSnapshot = alice.nextMessage(5, TimeUnit.SECONDS);
        assertEquals("presence-snapshot", aliceSnapshot.path("type").asText());
        assertEquals(0, aliceSnapshot.path("users").size()); // nobody else here yet

        TestClient bob = connect("doc-1");
        announcePresence(bob, "u-bob", "Bob", "#0000ff");

        JsonNode bobSnapshot = bob.nextMessage(5, TimeUnit.SECONDS);
        assertEquals("presence-snapshot", bobSnapshot.path("type").asText());
        assertEquals(1, bobSnapshot.path("users").size());
        assertEquals("u-alice", bobSnapshot.path("users").get(0).path("userId").asText());

        JsonNode aliceSeesJoin = alice.nextMessage(5, TimeUnit.SECONDS);
        assertEquals("presence-join", aliceSeesJoin.path("type").asText());
        assertEquals("u-bob", aliceSeesJoin.path("userId").asText());
        assertEquals("Bob", aliceSeesJoin.path("username").asText());

        alice.closeBlocking();
        bob.closeBlocking();
    }

    @Test
    void secondTabUnderSameIdentityDoesNotCreateASecondPresenceEntry() throws Exception {
        TestClient bob = connect("doc-1");
        announcePresence(bob, "u-bob", "Bob", "#0000ff");
        bob.nextMessage(5, TimeUnit.SECONDS); // its own snapshot

        TestClient aliceTab1 = connect("doc-1");
        announcePresence(aliceTab1, "u-alice", "Alice", "#ff0000");
        aliceTab1.nextMessage(5, TimeUnit.SECONDS); // its own snapshot
        assertEquals("presence-join", bob.nextMessage(5, TimeUnit.SECONDS).path("type").asText());

        TestClient aliceTab2 = connect("doc-1");
        announcePresence(aliceTab2, "u-alice", "Alice", "#ff0000");
        JsonNode tab2Snapshot = aliceTab2.nextMessage(5, TimeUnit.SECONDS);
        assertEquals(1, tab2Snapshot.path("users").size()); // just bob; alice doesn't see herself

        // Bob must NOT receive a second presence-join for u-alice's second tab.
        assertNull(bob.pollMessage(400, TimeUnit.MILLISECONDS));

        bob.closeBlocking();
        aliceTab1.closeBlocking();
        aliceTab2.closeBlocking();
    }

    @Test
    void closingOneOfTwoSameIdentityTabsDoesNotRemovePresenceButClosingTheLastOneDoes() throws Exception {
        TestClient bob = connect("doc-1");
        announcePresence(bob, "u-bob", "Bob", "#0000ff");
        bob.nextMessage(5, TimeUnit.SECONDS);

        TestClient aliceTab1 = connect("doc-1");
        announcePresence(aliceTab1, "u-alice", "Alice", "#ff0000");
        aliceTab1.nextMessage(5, TimeUnit.SECONDS);
        bob.nextMessage(5, TimeUnit.SECONDS); // presence-join for alice

        TestClient aliceTab2 = connect("doc-1");
        announcePresence(aliceTab2, "u-alice", "Alice", "#ff0000");
        aliceTab2.nextMessage(5, TimeUnit.SECONDS);

        aliceTab1.closeBlocking();
        Thread.sleep(300);
        // Bob must NOT see a departure -- alice's second tab is still open.
        assertNull(bob.pollMessage(400, TimeUnit.MILLISECONDS));

        aliceTab2.closeBlocking();
        JsonNode departure = bob.nextMessage(5, TimeUnit.SECONDS);
        assertEquals("presence-leave", departure.path("type").asText());
        assertEquals("u-alice", departure.path("userId").asText());

        bob.closeBlocking();
    }

    @Test
    void cursorUpdatesBroadcastToOthersButNotTheSenderAndFollowTheMostRecentConnection() throws Exception {
        TestClient bob = connect("doc-1");
        announcePresence(bob, "u-bob", "Bob", "#0000ff");
        bob.nextMessage(5, TimeUnit.SECONDS); // its own snapshot

        TestClient alice = connect("doc-1");
        announcePresence(alice, "u-alice", "Alice", "#ff0000");
        alice.nextMessage(5, TimeUnit.SECONDS); // its own snapshot
        bob.nextMessage(5, TimeUnit.SECONDS); // presence-join for alice

        alice.send(MAPPER.writeValueAsString(Map.of("type", "updateCursor", "position", 7)));
        JsonNode cursorUpdate = bob.nextMessage(5, TimeUnit.SECONDS);
        assertEquals("cursor-update", cursorUpdate.path("type").asText());
        assertEquals("u-alice", cursorUpdate.path("userId").asText());
        assertEquals(7, cursorUpdate.path("position").asInt());

        // The sender never gets their own cursor update echoed back.
        assertNull(alice.pollMessage(400, TimeUnit.MILLISECONDS));

        // A second tab under the same identity moving its cursor updates the SAME
        // logical presence -- "follows their most-recently-active connection".
        TestClient aliceTab2 = connect("doc-1");
        announcePresence(aliceTab2, "u-alice", "Alice", "#ff0000");
        aliceTab2.nextMessage(5, TimeUnit.SECONDS); // its own snapshot
        // No second presence-join is broadcast -- tab2 isn't u-alice's first connection.
        assertNull(bob.pollMessage(400, TimeUnit.MILLISECONDS));

        aliceTab2.send(MAPPER.writeValueAsString(Map.of("type", "updateCursor", "position", 12)));
        JsonNode secondCursorUpdate = bob.nextMessage(5, TimeUnit.SECONDS);
        assertEquals("u-alice", secondCursorUpdate.path("userId").asText());
        assertEquals(12, secondCursorUpdate.path("position").asInt());

        bob.closeBlocking();
        alice.closeBlocking();
        aliceTab2.closeBlocking();
    }

    private static final class TestClient extends WebSocketClient {

        private final BlockingQueue<JsonNode> messages = new ArrayBlockingQueue<>(256);

        TestClient(URI uri) {
            super(uri);
        }

        JsonNode nextMessage(long timeout, TimeUnit unit) throws InterruptedException {
            JsonNode message = messages.poll(timeout, unit);
            assertTrue(message != null, "did not receive a message in time");
            return message;
        }

        JsonNode pollMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return messages.poll(timeout, unit);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
        }

        @Override
        public void onMessage(String message) {
            try {
                messages.add(MAPPER.readTree(message));
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
