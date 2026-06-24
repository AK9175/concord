package com.collabdoc.metadata;

import com.collabdoc.metadata.grpc.ConnectionTierEvictClient;
import com.collabdoc.metadata.grpc.DocumentServiceDeleteClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for document metadata + per-document roster:
 * POST/GET /docs, GET/PATCH /docs/{id}, GET/POST /docs/{id}/users,
 * PATCH /docs/{id}/users/{userId}.
 *
 * Hand-rolled routing over the JDK's built-in HttpServer (same approach as
 * frontend-server) rather than a web framework -- the route table is six
 * endpoints, not worth a dependency for.
 *
 * Sends CORS headers on every response: frontend-server runs on a different
 * origin, so without these the browser would block every response here from
 * ever reaching the page's JS, even though the server handled the request
 * fine.
 */
public class DocumentMetadataServer {

    private final DocumentMetadataStore metadataStore;
    private final DocumentRosterStore rosterStore;
    private final DocumentServiceDeleteClient documentServiceClient;
    private final ConnectionTierEvictClient connectionTierClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpServer server;

    public DocumentMetadataServer(int port, DocumentMetadataStore metadataStore, DocumentRosterStore rosterStore,
            DocumentServiceDeleteClient documentServiceClient, ConnectionTierEvictClient connectionTierClient)
            throws IOException {
        this.metadataStore = metadataStore;
        this.rosterStore = rosterStore;
        this.documentServiceClient = documentServiceClient;
        this.connectionTierClient = connectionTierClient;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/docs", this::handle);
    }

    public void start() {
        server.start();
        System.out.println("document-metadata-service: listening on port " + getPort());
    }

    public void stop() {
        server.stop(0);
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        String method = exchange.getRequestMethod();

        if (method.equals("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String[] segments = pathSegments(exchange.getRequestURI().getPath());

        try {
            if (segments.length == 1) {
                handleDocsCollection(exchange, method);
            } else if (segments.length == 2) {
                handleSingleDocument(exchange, method, segments[1]);
            } else if (segments.length == 3 && segments[2].equals("users")) {
                handleRoster(exchange, method, segments[1]);
            } else if (segments.length == 4 && segments[2].equals("users")) {
                handleSingleUser(exchange, method, segments[1], segments[3]);
            } else {
                sendJson(exchange, 404, errorBody("not found"));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            sendJson(exchange, 500, errorBody("internal error"));
        }
    }

    private void handleDocsCollection(HttpExchange exchange, String method) throws IOException {
        if (method.equals("GET")) {
            sendJson(exchange, 200, metadataStore.list());
        } else if (method.equals("POST")) {
            CreateDocumentRequest request = readBody(exchange, CreateDocumentRequest.class);
            Document created = metadataStore.create(request.title());
            sendJson(exchange, 201, created);
        } else {
            sendJson(exchange, 405, errorBody("method not allowed"));
        }
    }

    private void handleSingleDocument(HttpExchange exchange, String method, String documentId) throws IOException {
        if (method.equals("GET")) {
            Optional<Document> document = metadataStore.get(documentId);
            if (document.isPresent()) {
                sendJson(exchange, 200, document.get());
            } else {
                sendJson(exchange, 404, errorBody("document not found"));
            }
        } else if (method.equals("PATCH")) {
            RenameDocumentRequest request = readBody(exchange, RenameDocumentRequest.class);
            Optional<Document> renamed = metadataStore.rename(documentId, request.title());
            if (renamed.isPresent()) {
                sendJson(exchange, 200, renamed.get());
            } else {
                sendJson(exchange, 404, errorBody("document not found"));
            }
        } else if (method.equals("DELETE")) {
            handleDelete(exchange, documentId);
        } else {
            sendJson(exchange, 405, errorBody("method not allowed"));
        }
    }

    /**
     * Order matters here, since this isn't transactional across three
     * independent services: eviction is best-effort first (so active editors
     * are told before their data disappears, but a briefly-unreachable
     * connection-tier shouldn't block the delete); wiping document-service's
     * op log is REQUIRED to succeed before touching this service's own
     * tables, so a failure here leaves the document fully intact and visible
     * rather than an orphaned, data-less catalog entry; the catalog row
     * itself is removed LAST, once we're confident the underlying content is
     * actually gone.
     */
    private void handleDelete(HttpExchange exchange, String documentId) throws IOException {
        if (metadataStore.get(documentId).isEmpty()) {
            sendJson(exchange, 404, errorBody("document not found"));
            return;
        }

        try {
            connectionTierClient.evictDocument(documentId);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        try {
            documentServiceClient.deleteDocument(documentId);
        } catch (Exception ex) {
            ex.printStackTrace();
            sendJson(exchange, 502, errorBody("failed to delete document content; document was not deleted"));
            return;
        }

        rosterStore.deleteRoster(documentId);
        metadataStore.delete(documentId);
        exchange.sendResponseHeaders(204, -1);
    }

    private void handleRoster(HttpExchange exchange, String method, String documentId) throws IOException {
        if (method.equals("GET")) {
            List<User> users = rosterStore.listUsers(documentId);
            sendJson(exchange, 200, users);
        } else if (method.equals("POST")) {
            AddUserRequest request = readBody(exchange, AddUserRequest.class);
            User added = rosterStore.addUser(documentId, request.username(), request.color());
            sendJson(exchange, 201, added);
        } else {
            sendJson(exchange, 405, errorBody("method not allowed"));
        }
    }

    private void handleSingleUser(HttpExchange exchange, String method, String documentId, String userId)
            throws IOException {
        if (method.equals("PATCH")) {
            RenameUserRequest request = readBody(exchange, RenameUserRequest.class);
            Optional<User> renamed = rosterStore.renameUser(documentId, userId, request.username(), request.color());
            if (renamed.isPresent()) {
                sendJson(exchange, 200, renamed.get());
            } else {
                sendJson(exchange, 404, errorBody("user not found"));
            }
        } else {
            sendJson(exchange, 405, errorBody("method not allowed"));
        }
    }

    private <T> T readBody(HttpExchange exchange, Class<T> type) throws IOException {
        return objectMapper.readValue(exchange.getRequestBody(), type);
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] json = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, json.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(json);
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String[] pathSegments(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("/");
    }

    private static Map<String, String> errorBody(String message) {
        return Map.of("error", message);
    }

    private record CreateDocumentRequest(String title) {
    }

    private record RenameDocumentRequest(String title) {
    }

    private record AddUserRequest(String username, String color) {
    }

    private record RenameUserRequest(String username, String color) {
    }
}
