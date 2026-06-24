package com.collabdoc.document;

import com.collabdoc.document.grpc.DocumentServiceGrpcImpl;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.Server;
import io.grpc.ServerBuilder;

public final class Main {

    private static final int PORT = 9090;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String jdbcUrl = System.getenv().getOrDefault("CONCORD_DB_URL", "jdbc:postgresql://localhost:5432/concord");
        String username = System.getenv().getOrDefault("CONCORD_DB_USER", "concord");
        String password = System.getenv().getOrDefault("CONCORD_DB_PASSWORD", "concord");

        HikariDataSource dataSource = PostgresDataSources.create(jdbcUrl, username, password);
        DocumentSequencer sequencer = new DocumentSequencer(new PostgresOperationLog(dataSource));

        Server server = ServerBuilder.forPort(PORT)
                .addService(new DocumentServiceGrpcImpl(sequencer))
                .build()
                .start();
        System.out.println("document-service: listening on port " + PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.awaitTermination();
    }
}
