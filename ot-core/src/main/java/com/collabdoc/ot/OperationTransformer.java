package com.collabdoc.ot;

import java.util.List;

/**
 * Transforms one operation against another operation that is concurrent with
 * it (both were created against the same base revision, but one has already
 * been committed).
 *
 * transform(toTransform, appliedFirst) returns the sequence of operations
 * that, applied in order to a document appliedFirst has already been applied
 * to, produce the correct result. This is the one-directional transform used
 * by both the server (transforming an incoming op against every committed op
 * in the revision gap, in order) and the client (transforming an incoming
 * broadcast op against its own pending ops).
 *
 * Most pairs transform into exactly one operation. The exception is a delete
 * whose range is split by a concurrent insert landing in its interior: we
 * preserve the newly inserted text (see transformDeleteInsert), which means
 * the single original delete must become two deletes, one on each side of
 * the survivor. Returning a List<Operation> lets that case exist without
 * forcing every other case to carry a meaningless extra slot.
 *
 * The returned operations all share one coordinate frame: the document state
 * appliedFirst was applied to. They are NOT a sequential chain where each
 * assumes the previous one already ran. Apply them with
 * OperationApplier.applyAll(), and if you need to transform them again
 * against a further op, transform each one independently against it.
 */
public final class OperationTransformer {

    private OperationTransformer() {
    }

    public static List<Operation> transform(Operation toTransform, Operation appliedFirst) {
        return switch (toTransform) {
            case InsertOperation insert -> switch (appliedFirst) {
                case InsertOperation otherInsert -> List.of(transformInsertInsert(insert, otherInsert));
                case DeleteOperation otherDelete -> List.of(transformInsertDelete(insert, otherDelete));
            };
            case DeleteOperation delete -> switch (appliedFirst) {
                case InsertOperation otherInsert -> transformDeleteInsert(delete, otherInsert);
                case DeleteOperation otherDelete -> List.of(transformDeleteDelete(delete, otherDelete));
            };
        };
    }

    private static InsertOperation transformInsertInsert(InsertOperation toTransform, InsertOperation appliedFirst) {
        if (toTransform.position() < appliedFirst.position()) {
            // toTransform's insertion point is untouched by appliedFirst's insert.
            return toTransform;
        }
        if (toTransform.position() > appliedFirst.position()) {
            // appliedFirst's text now sits before toTransform's insertion point; shift past it.
            return shiftedInsert(toTransform, appliedFirst.text().length());
        }
        // Same position: both inserts want the same spot. Without a deterministic
        // tie-break, the two sites that concurrently received these ops in opposite
        // order could each decide a different relative ordering of the two inserted
        // runs of text, and never converge. Breaking the tie by comparing userId
        // means both sites independently compute the same answer.
        if (toTransform.userId().compareTo(appliedFirst.userId()) < 0) {
            // toTransform has priority at this position: it lands first, so appliedFirst's
            // text (already applied) must be treated as coming after it -- no shift.
            return toTransform;
        }
        // appliedFirst has priority: its text logically precedes toTransform's, so shift past it.
        return shiftedInsert(toTransform, appliedFirst.text().length());
    }

    private static InsertOperation transformInsertDelete(InsertOperation toTransform, DeleteOperation appliedFirst) {
        int insertPos = toTransform.position();
        int delStart = appliedFirst.position();
        int delEnd = delStart + appliedFirst.length();

        if (insertPos <= delStart) {
            // Inserting at or before the deletion's start: the deletion never touches
            // this insertion point, so it's unaffected.
            return toTransform;
        }
        if (insertPos >= delEnd) {
            // Entirely after the deleted range: shift back by however much text vanished.
            return shiftedInsert(toTransform, -appliedFirst.length());
        }
        // The insertion point fell strictly inside a range that's concurrently being
        // deleted in full. We preserve the inserted text -- the deleting user never
        // saw it, so it survives the deletion -- by collapsing the insertion point to
        // wherever the deletion collapsed to. The dual case below splits the delete
        // around this same survivor so both sides converge.
        return new InsertOperation(toTransform.baseRevision(), toTransform.userId(), delStart, toTransform.text());
    }

    private static List<Operation> transformDeleteInsert(DeleteOperation toTransform, InsertOperation appliedFirst) {
        int insertPos = appliedFirst.position();
        int delStart = toTransform.position();
        int delEnd = delStart + toTransform.length();
        int insertLen = appliedFirst.text().length();

        if (insertPos <= delStart) {
            // New text landed before the deletion start: shift the whole range forward.
            return List.of(new DeleteOperation(toTransform.baseRevision(), toTransform.userId(),
                    delStart + insertLen, toTransform.length()));
        }
        if (insertPos >= delEnd) {
            // New text landed after the deletion's range entirely: unaffected.
            return List.of(toTransform);
        }
        // Insertion landed inside the range this delete is removing, and (per
        // transformInsertDelete above) that text survives. A single contiguous delete
        // can't skip over surviving text in its middle, so split into two deletes: one
        // for the span before the insertion point, one for the span after it.
        //
        // Both pieces are positioned in the SAME frame -- the document appliedFirst
        // (the insert) was just applied to -- not chained to each other. That matters
        // once a *second* concurrent op also needs transforming against these pieces:
        // each must independently represent its span in that one shared frame, so it
        // can be transformed again on its own. (An earlier version pre-shifted the
        // right piece as if the left piece had already been applied to it; that broke
        // under a third concurrent op, where the right piece was later split again and
        // its position no longer lined up with the frame the new op's position was in
        // -- caught by the CP 1.5 convergence property test.) Callers must apply
        // OperationApplier.applyAll(), which sorts by descending position so applying
        // the higher-positioned piece first never invalidates the lower one's index.
        int leftLength = insertPos - delStart;
        DeleteOperation left = new DeleteOperation(toTransform.baseRevision(), toTransform.userId(),
                delStart, leftLength);
        int rightPosition = insertPos + insertLen;
        int rightLength = delEnd - insertPos;
        DeleteOperation right = new DeleteOperation(toTransform.baseRevision(), toTransform.userId(),
                rightPosition, rightLength);
        return List.of(left, right);
    }

    private static DeleteOperation transformDeleteDelete(DeleteOperation toTransform, DeleteOperation appliedFirst) {
        int start1 = toTransform.position();
        int end1 = start1 + toTransform.length();
        int start2 = appliedFirst.position();
        int end2 = start2 + appliedFirst.length();

        if (end1 <= start2) {
            // toTransform's range is entirely before appliedFirst's: unaffected.
            return toTransform;
        }
        if (start1 >= end2) {
            // toTransform's range is entirely after appliedFirst's: shift back by what's gone.
            return new DeleteOperation(toTransform.baseRevision(), toTransform.userId(),
                    start1 - appliedFirst.length(), toTransform.length());
        }
        // Ranges overlap. Any characters appliedFirst already removed don't need
        // deleting again -- subtract the overlap from this delete's length. The
        // leftover is always a single contiguous range in the post-appliedFirst
        // document: collapsing the shared middle naturally joins whatever remains on
        // either side, so (unlike the insert/delete case) no splitting is needed here.
        int overlapStart = Math.max(start1, start2);
        int overlapEnd = Math.min(end1, end2);
        int overlapLength = Math.max(0, overlapEnd - overlapStart);
        int newStart = Math.min(start1, start2);
        int newLength = toTransform.length() - overlapLength;
        return new DeleteOperation(toTransform.baseRevision(), toTransform.userId(), newStart, newLength);
    }

    private static InsertOperation shiftedInsert(InsertOperation op, int delta) {
        return new InsertOperation(op.baseRevision(), op.userId(), op.position() + delta, op.text());
    }
}
