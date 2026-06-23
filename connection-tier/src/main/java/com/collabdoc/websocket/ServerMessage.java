package com.collabdoc.websocket;

import com.collabdoc.document.CommittedOperation;

import java.util.List;

/**
 * Wire format for everything the server sends back. "ack" goes only to the
 * client whose edit this was; "update" goes to every other connected client
 * of the same document. Both carry the same payload shape (one client op can
 * legitimately commit as more than one CommittedOperation, e.g. the
 * insert-survives-delete split) -- only the type and the recipient differ.
 *
 * A single concrete type with a plain "type" string, rather than a
 * polymorphic hierarchy like Operation's, since there's nothing here that
 * needs to deserialize back into different Java types -- this is purely
 * outbound, so a tag the client reads as a string is enough.
 */
public record ServerMessage(String type, String documentId, List<CommittedOperation> committed) {

    public static ServerMessage ack(String documentId, List<CommittedOperation> committed) {
        return new ServerMessage("ack", documentId, committed);
    }

    public static ServerMessage update(String documentId, List<CommittedOperation> committed) {
        return new ServerMessage("update", documentId, committed);
    }

    /** Sent once, right after connecting: the full op log so far, in revision order. */
    public static ServerMessage history(String documentId, List<CommittedOperation> committed) {
        return new ServerMessage("history", documentId, committed);
    }
}
