package com.collabdoc.websocket;

import com.collabdoc.document.DocumentSequencer;
import com.collabdoc.document.OperationLog;
import com.collabdoc.document.PostgresDataSources;
import com.collabdoc.document.PostgresOperationLog;
import com.zaxxer.hikari.HikariDataSource;

public final class Main {

    private static final int PORT = 8081;

    private Main() {
    }

    public static void main(String[] args) {
        // CP 5.2: ack-after-durable falls out of the existing structure for free --
        // DocumentCommitter.commit() already calls operationLog.append()
        // synchronously before returning, and ConnectionTierServer only acks/
        // broadcasts once that whole CompletableFuture completes. Swapping in
        // PostgresOperationLog (a real durable write) instead of the in-memory one
        // is the only change CP 5.2 needs; the ordering guarantee was already there.
        String jdbcUrl = System.getenv().getOrDefault("CONCORD_DB_URL", "jdbc:postgresql://localhost:5432/concord");
        String username = System.getenv().getOrDefault("CONCORD_DB_USER", "concord");
        String password = System.getenv().getOrDefault("CONCORD_DB_PASSWORD", "concord");

        HikariDataSource dataSource = PostgresDataSources.create(jdbcUrl, username, password);
        OperationLog operationLog = new PostgresOperationLog(dataSource);

        DocumentSequencer sequencer = new DocumentSequencer(operationLog);
        ConnectionTierServer server = new ConnectionTierServer(PORT, sequencer);
        server.start();
    }
}
