package com.collabdoc.document;

import com.collabdoc.ot.Operation;

/**
 * An operation that has been committed to a document's log at a specific
 * revision. Distinct from Operation.baseRevision (the revision the op was
 * CREATED against) -- this is the revision it was actually ASSIGNED on
 * commit, which is what readers replay against.
 */
public record CommittedOperation(long revision, Operation operation) {
}
