package com.collabdoc.ot;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * An edit to a document. Every operation carries the revision it was created
 * against (baseRevision) and the userId of whoever created it -- both are
 * required for the server to detect concurrency and for convergence to work.
 *
 * Sealed to InsertOperation and DeleteOperation: the transform() function in
 * OperationTransformer must handle every pair of these exhaustively, and the
 * compiler enforces that there are only two cases to handle.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = InsertOperation.class, name = "insert"),
        @JsonSubTypes.Type(value = DeleteOperation.class, name = "delete")
})
public sealed interface Operation permits InsertOperation, DeleteOperation {

    long baseRevision();

    String userId();

    int position();
}
