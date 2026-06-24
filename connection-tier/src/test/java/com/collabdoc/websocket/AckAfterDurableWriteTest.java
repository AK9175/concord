package com.collabdoc.websocket;

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
 * CP 5.2's other half: an op that fails to persist is not acked and not
 * broadcast. Uses FailingOperationLog to simulate a durable-write failure
 * (e.g. the database being unreachable) without needing a real Postgres
 * outage -- the ordering guarantee being tested lives entirely in
 * ConnectionTierServer/DocumentSequencer, not in any particular
 * OperationLog implementation.
 */
class AckAfterDurableWriteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InProcessDocumentService documentService;
    private ConnectionTierServer server;
    private int port;

    @BeforeEach
    void startServer() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        documentService = new InProcessDocumentService(new FailingOperationLog());
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
    void anOperationThatFailsToPersistIsNeitherAckedNorBroadcast() throws Exception {
        TestClient sender = connect("doc-1");
        TestClient observer = connect("doc-1");

        sender.send(MAPPER.writeValueAsString(new InsertOperation(0, "alice", 0, "hello")));

        // Neither the sender nor the observer should ever hear about this op --
        // the durable write failed, so DocumentCommitter.commit() throws, the
        // CompletableFuture completes exceptionally, and .thenAccept (which
        // would ack/broadcast) never runs at all.
        assertNull(sender.pollMessage(1, TimeUnit.SECONDS), "sender must not be acked for a failed write");
        assertNull(observer.pollMessage(500, TimeUnit.MILLISECONDS), "observer must not see a failed write broadcast");

        sender.closeBlocking();
        observer.closeBlocking();
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
