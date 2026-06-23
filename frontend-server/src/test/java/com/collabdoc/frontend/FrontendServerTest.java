package com.collabdoc.frontend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendServerTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private FrontendServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        Path staticRoot = Path.of("src/main/resources/static");
        server = new FrontendServer(0, staticRoot); // 0 = let the OS pick a free port
        server.start();
        port = server.getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rootServesIndexHtml() throws Exception {
        HttpResponse<String> response = get("/");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Concord"));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
    }

    @Test
    void realAssetIsServedWithCorrectContentType() throws Exception {
        HttpResponse<String> response = get("/css/base.css");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
    }

    @Test
    void unknownPathFallsBackToEditorHtmlForDocumentRoutes() throws Exception {
        HttpResponse<String> response = get("/some-arbitrary-doc-id");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("doc-textarea"));
    }

    @Test
    void pathTraversalAttemptDoesNotEscapeTheStaticRoot() throws Exception {
        // Would resolve outside the static root if not guarded; must NOT leak pom.xml --
        // falls back to editor.html like any other non-asset path instead.
        HttpResponse<String> response = get("/../../pom.xml");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("doc-textarea"));
    }
}
