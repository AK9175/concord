package com.collabdoc.websocket;

import com.collabdoc.connectiontier.proto.ConnectionTierAdminGrpc;
import com.collabdoc.connectiontier.proto.EvictDocumentRequest;
import com.collabdoc.connectiontier.proto.EvictDocumentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.stub.StreamObserver;
import org.java_websocket.WebSocket;

import java.util.Set;

/**
 * The admin side of document deletion: document-metadata-service calls
 * EvictDocument once it's decided to delete a document, so anyone actively
 * editing it gets told and disconnected rather than silently left talking
 * to a document that no longer exists anywhere. Lives directly in
 * com.collabdoc.websocket (not a .grpc subpackage like DocumentServiceClient)
 * because it needs package-private access to DocumentSessionRegistry --
 * this is connection-tier's own internal state, not a wire-protocol concern.
 */
class ConnectionTierAdminServer extends ConnectionTierAdminGrpc.ConnectionTierAdminImplBase {

    private final DocumentSessionRegistry sessions;
    private final ObjectMapper objectMapper = new ObjectMapper();

    ConnectionTierAdminServer(DocumentSessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    public void evictDocument(EvictDocumentRequest request, StreamObserver<EvictDocumentResponse> responseObserver) {
        String documentId = request.getDocumentId();
        Set<WebSocket> connections = sessions.allInDocument(documentId);

        for (WebSocket connection : connections) {
            try {
                connection.send(objectMapper.writeValueAsString(ServerMessage.deleted(documentId)));
            } catch (Exception ex) {
                // Best-effort notification -- still close the connection below
                // even if telling it why failed (e.g. the socket is already
                // half-closed on the client's end).
                ex.printStackTrace();
            }
            connection.close(1000, "document deleted");
        }

        responseObserver.onNext(EvictDocumentResponse.newBuilder()
                .setConnectionsClosed(connections.size())
                .build());
        responseObserver.onCompleted();
    }
}
