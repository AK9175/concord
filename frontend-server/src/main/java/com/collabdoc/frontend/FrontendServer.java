package com.collabdoc.frontend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves this module's bundled static files (src/main/resources/static)
 * using only the JDK's built-in HTTP server -- no new dependency needed for
 * plain static file serving.
 *
 * "Sharing = the URL": any request path that doesn't correspond to a real
 * static asset (e.g. /<docId>) falls back to editor.html, whose own JS reads
 * the docId from window.location. There is no server-side per-document
 * routing here at all -- this class has no idea what a "document" is.
 */
public class FrontendServer {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon");

    private final HttpServer server;
    private final Path root;

    public FrontendServer(int port, Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
        System.out.println("frontend-server: listening on port " + getPort() + ", serving " + root);
    }

    public void stop() {
        server.stop(0);
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String requestPath = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);

            Path resolved;
            if (requestPath.equals("/")) {
                resolved = root.resolve("index.html");
            } else {
                Path asset = resolveAsset(requestPath);
                resolved = asset != null ? asset : root.resolve("editor.html");
            }

            byte[] body = Files.readAllBytes(resolved);
            exchange.getResponseHeaders().add("Content-Type", contentTypeFor(resolved));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        } catch (IOException ex) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        }
    }

    /**
     * Resolves requestPath to a real file under root, or null if it doesn't
     * exist. normalize() + startsWith(root) rejects path-traversal attempts
     * (e.g. "/../pom.xml") -- requestPath is untrusted network input, so this
     * boundary check is not optional.
     */
    private Path resolveAsset(String requestPath) {
        String relative = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
            return null;
        }
        return candidate;
    }

    private static String contentTypeFor(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1) : "";
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}
