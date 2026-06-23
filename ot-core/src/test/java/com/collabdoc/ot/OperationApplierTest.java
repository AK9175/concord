package com.collabdoc.ot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperationApplierTest {

    private static final String USER = "user-1";

    // ---- insert ----

    @Test
    void insertAtStart() {
        String result = OperationApplier.apply("world", new InsertOperation(0, USER, 0, "hello "));
        assertEquals("hello world", result);
    }

    @Test
    void insertInMiddle() {
        String result = OperationApplier.apply("hed", new InsertOperation(0, USER, 2, "ll"));
        assertEquals("helld", result);
    }

    @Test
    void insertAtEnd() {
        String result = OperationApplier.apply("hello", new InsertOperation(0, USER, 5, " world"));
        assertEquals("hello world", result);
    }

    @Test
    void insertIntoEmptyDocument() {
        String result = OperationApplier.apply("", new InsertOperation(0, USER, 0, "first"));
        assertEquals("first", result);
    }

    @Test
    void insertPastEndThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationApplier.apply("abc", new InsertOperation(0, USER, 4, "x")));
    }

    @Test
    void insertAtNegativePositionThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationApplier.apply("abc", new InsertOperation(0, USER, -1, "x")));
    }

    // ---- delete ----

    @Test
    void deleteFromStart() {
        String result = OperationApplier.apply("hello world", new DeleteOperation(0, USER, 0, 6));
        assertEquals("world", result);
    }

    @Test
    void deleteFromMiddle() {
        String result = OperationApplier.apply("hexllo", new DeleteOperation(0, USER, 2, 1));
        assertEquals("hello", result);
    }

    @Test
    void deleteToEnd() {
        String result = OperationApplier.apply("hello world", new DeleteOperation(0, USER, 5, 6));
        assertEquals("hello", result);
    }

    @Test
    void deleteEntireDocument() {
        String result = OperationApplier.apply("hello", new DeleteOperation(0, USER, 0, 5));
        assertEquals("", result);
    }

    @Test
    void deleteZeroLengthIsNoOp() {
        String result = OperationApplier.apply("hello", new DeleteOperation(0, USER, 2, 0));
        assertEquals("hello", result);
    }

    @Test
    void deletePastEndThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationApplier.apply("abc", new DeleteOperation(0, USER, 1, 10)));
    }

    @Test
    void deleteFromEmptyDocumentThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationApplier.apply("", new DeleteOperation(0, USER, 0, 1)));
    }

    @Test
    void deleteNegativeLengthThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationApplier.apply("abc", new DeleteOperation(0, USER, 0, -1)));
    }
}
