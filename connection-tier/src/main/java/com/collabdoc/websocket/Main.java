package com.collabdoc.websocket;

import com.collabdoc.document.DocumentSequencer;
import com.collabdoc.document.InMemoryOperationLog;

public final class Main {

    private static final int PORT = 8081;

    private Main() {
    }

    public static void main(String[] args) {
        DocumentSequencer sequencer = new DocumentSequencer(new InMemoryOperationLog());
        ConnectionTierServer server = new ConnectionTierServer(PORT, sequencer);
        server.start();
    }
}
