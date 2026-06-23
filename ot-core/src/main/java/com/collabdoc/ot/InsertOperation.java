package com.collabdoc.ot;

/**
 * Inserts {@code text} at {@code position} in the document.
 *
 * @param baseRevision the server revision this op was created against
 * @param userId       who created it
 * @param position     0-based character index where text is inserted
 * @param text         the text to insert (may be empty, though callers shouldn't bother)
 */
public record InsertOperation(long baseRevision, String userId, int position, String text)
        implements Operation {
}
