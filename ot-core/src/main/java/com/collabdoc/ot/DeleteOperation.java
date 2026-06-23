package com.collabdoc.ot;

/**
 * Deletes {@code length} characters starting at {@code position}.
 *
 * @param baseRevision the server revision this op was created against
 * @param userId       who created it
 * @param position     0-based character index where the deletion starts
 * @param length       number of characters removed
 */
public record DeleteOperation(long baseRevision, String userId, int position, int length)
        implements Operation {
}
