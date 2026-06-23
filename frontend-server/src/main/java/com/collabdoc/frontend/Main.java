package com.collabdoc.frontend;

import java.io.IOException;
import java.nio.file.Path;

public final class Main {

    private static final int PORT = 8082;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Path frontendRoot = Path.of(System.getProperty("frontend.dir", "frontend-server/src/main/resources/static"));
        FrontendServer server = new FrontendServer(PORT, frontendRoot);
        server.start();
    }
}
