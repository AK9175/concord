package com.collabdoc.ot;

import java.util.Comparator;
import java.util.List;

/**
 * Applies a single Operation to a document's string content.
 *
 * This is deliberately separate from transform(): apply() never needs to
 * know about concurrency, only how to mutate text given a position. Keeping
 * it pure and dependency-free means it's trivial to test in isolation and
 * reusable both on the server (committing ops) and on the client (optimistic
 * local apply + replaying broadcast ops).
 */
public final class OperationApplier {

    private OperationApplier() {
    }

    public static String apply(String document, Operation operation) {
        return switch (operation) {
            case InsertOperation insert -> applyInsert(document, insert);
            case DeleteOperation delete -> applyDelete(document, delete);
        };
    }

    /**
     * Applies every operation returned by a single OperationTransformer.transform()
     * call. Those operations all share one coordinate frame (the document state
     * appliedFirst was applied to) rather than being chained to each other, so they
     * must be applied highest-position-first: removing a later range first leaves
     * every earlier range's position still valid, while the reverse order would not.
     */
    public static String applyAll(String document, List<Operation> operations) {
        List<Operation> byDescendingPosition = operations.stream()
                .sorted(Comparator.comparingInt(Operation::position).reversed())
                .toList();
        String result = document;
        for (Operation operation : byDescendingPosition) {
            result = apply(result, operation);
        }
        return result;
    }

    private static String applyInsert(String document, InsertOperation insert) {
        int position = insert.position();
        if (position < 0 || position > document.length()) {
            throw new IllegalArgumentException(
                    "insert position " + position + " out of bounds for document length " + document.length());
        }
        return document.substring(0, position) + insert.text() + document.substring(position);
    }

    private static String applyDelete(String document, DeleteOperation delete) {
        int position = delete.position();
        int length = delete.length();
        if (position < 0 || length < 0 || position + length > document.length()) {
            throw new IllegalArgumentException(
                    "delete range [" + position + ", " + (position + length) + ") out of bounds for document length "
                            + document.length());
        }
        return document.substring(0, position) + document.substring(position + length);
    }
}
