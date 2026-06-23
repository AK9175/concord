package com.collabdoc.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CP 3.1's literal verification: create/list/rename a doc and add/list its users via a REST test. */
class DocumentMetadataServerTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DocumentMetadataServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = new DocumentMetadataServer(0, new InMemoryDocumentMetadataStore(), new InMemoryDocumentRosterStore());
        server.start();
        port = server.getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private HttpResponse<String> request(String method, String path, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        HttpRequest.BodyPublisher body = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);
        builder.method(method, body);
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void createListGetAndRenameADocument() throws Exception {
        HttpResponse<String> createResponse = request("POST", "/docs", "{\"title\":\"My Notes\"}");
        assertEquals(201, createResponse.statusCode());
        JsonNode created = MAPPER.readTree(createResponse.body());
        String documentId = created.get("id").asText();
        assertEquals("My Notes", created.get("title").asText());

        HttpResponse<String> listResponse = request("GET", "/docs", null);
        assertEquals(200, listResponse.statusCode());
        JsonNode list = MAPPER.readTree(listResponse.body());
        assertEquals(1, list.size());
        assertEquals(documentId, list.get(0).get("id").asText());

        HttpResponse<String> getResponse = request("GET", "/docs/" + documentId, null);
        assertEquals(200, getResponse.statusCode());
        assertEquals("My Notes", MAPPER.readTree(getResponse.body()).get("title").asText());

        HttpResponse<String> renameResponse = request("PATCH", "/docs/" + documentId, "{\"title\":\"Renamed\"}");
        assertEquals(200, renameResponse.statusCode());
        assertEquals("Renamed", MAPPER.readTree(renameResponse.body()).get("title").asText());

        HttpResponse<String> getAfterRename = request("GET", "/docs/" + documentId, null);
        assertEquals("Renamed", MAPPER.readTree(getAfterRename.body()).get("title").asText());
    }

    @Test
    void gettingOrRenamingAnUnknownDocumentReturns404() throws Exception {
        HttpResponse<String> getResponse = request("GET", "/docs/does-not-exist", null);
        assertEquals(404, getResponse.statusCode());

        HttpResponse<String> renameResponse = request("PATCH", "/docs/does-not-exist", "{\"title\":\"x\"}");
        assertEquals(404, renameResponse.statusCode());
    }

    @Test
    void addAndListRosterUsersForADocument() throws Exception {
        HttpResponse<String> createResponse = request("POST", "/docs", "{\"title\":\"Shared Doc\"}");
        String documentId = MAPPER.readTree(createResponse.body()).get("id").asText();

        HttpResponse<String> addUserResponse =
                request("POST", "/docs/" + documentId + "/users", "{\"username\":\"alice\",\"color\":\"#ff0000\"}");
        assertEquals(201, addUserResponse.statusCode());
        JsonNode addedUser = MAPPER.readTree(addUserResponse.body());
        assertEquals("alice", addedUser.get("username").asText());
        assertEquals("#ff0000", addedUser.get("color").asText());
        assertTrue(addedUser.get("userId").asText().length() > 0);

        request("POST", "/docs/" + documentId + "/users", "{\"username\":\"bob\",\"color\":\"#00ff00\"}");

        HttpResponse<String> listUsersResponse = request("GET", "/docs/" + documentId + "/users", null);
        assertEquals(200, listUsersResponse.statusCode());
        JsonNode users = MAPPER.readTree(listUsersResponse.body());
        assertEquals(2, users.size());
    }

    @Test
    void rostersAreIndependentPerDocument() throws Exception {
        String docA = MAPPER.readTree(request("POST", "/docs", "{\"title\":\"A\"}").body()).get("id").asText();
        String docB = MAPPER.readTree(request("POST", "/docs", "{\"title\":\"B\"}").body()).get("id").asText();

        request("POST", "/docs/" + docA + "/users", "{\"username\":\"alice\",\"color\":\"#ff0000\"}");

        JsonNode usersOnA = MAPPER.readTree(request("GET", "/docs/" + docA + "/users", null).body());
        JsonNode usersOnB = MAPPER.readTree(request("GET", "/docs/" + docB + "/users", null).body());

        assertEquals(1, usersOnA.size());
        assertEquals(0, usersOnB.size());
    }

    @Test
    void renamingAUserKeepsTheirUserIdAndIsVisibleOnTheNextFetch() throws Exception {
        String documentId = MAPPER.readTree(request("POST", "/docs", "{\"title\":\"Shared Doc\"}").body())
                .get("id").asText();
        JsonNode added = MAPPER.readTree(
                request("POST", "/docs/" + documentId + "/users", "{\"username\":\"alice\",\"color\":\"#ff0000\"}")
                        .body());
        String userId = added.get("userId").asText();

        HttpResponse<String> renameResponse = request("PATCH", "/docs/" + documentId + "/users/" + userId,
                "{\"username\":\"alicia\",\"color\":\"#00ff00\"}");
        assertEquals(200, renameResponse.statusCode());
        JsonNode renamed = MAPPER.readTree(renameResponse.body());
        assertEquals(userId, renamed.get("userId").asText());
        assertEquals("alicia", renamed.get("username").asText());
        assertEquals("#00ff00", renamed.get("color").asText());

        // "Propagates to connected clients" for this REST-only checkpoint means:
        // anyone re-fetching the roster (e.g. on reconnect) sees the new name --
        // simulating a fresh client/tab asking for the roster again.
        JsonNode usersAfterRename = MAPPER.readTree(request("GET", "/docs/" + documentId + "/users", null).body());
        assertEquals(1, usersAfterRename.size());
        assertEquals(userId, usersAfterRename.get(0).get("userId").asText());
        assertEquals("alicia", usersAfterRename.get(0).get("username").asText());
    }

    @Test
    void renamingAnUnknownUserReturns404() throws Exception {
        String documentId = MAPPER.readTree(request("POST", "/docs", "{\"title\":\"Shared Doc\"}").body())
                .get("id").asText();

        HttpResponse<String> response = request("PATCH", "/docs/" + documentId + "/users/does-not-exist",
                "{\"username\":\"x\",\"color\":\"#000000\"}");
        assertEquals(404, response.statusCode());
    }

    @Test
    void responsesIncludeCorsHeadersForTheFrontendsOrigin() throws Exception {
        HttpResponse<String> response = request("GET", "/docs", null);
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }
}
