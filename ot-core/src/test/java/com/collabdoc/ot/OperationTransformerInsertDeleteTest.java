package com.collabdoc.ot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationTransformerInsertDeleteTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    @Test
    void insertBeforeDeletedRangeIsUnaffected() {
        InsertOperation insert = new InsertOperation(0, ALICE, 0, "X");
        DeleteOperation delete = new DeleteOperation(0, BOB, 6, 5); // deletes "world"

        String[] results = Convergence.bothOrders("hello world", insert, delete);

        assertEquals(results[0], results[1]);
        assertEquals("Xhello ", results[0]);
    }

    @Test
    void insertAfterDeletedRangeShiftsBack() {
        InsertOperation insert = new InsertOperation(0, ALICE, 11, "!"); // end of "hello world"
        DeleteOperation delete = new DeleteOperation(0, BOB, 0, 6); // deletes "hello "

        String[] results = Convergence.bothOrders("hello world", insert, delete);

        assertEquals(results[0], results[1]);
        assertEquals("world!", results[0]);
    }

    @Test
    void insertExactlyAtDeleteStartSurvivesUntouched() {
        InsertOperation insert = new InsertOperation(0, ALICE, 2, "X");
        DeleteOperation delete = new DeleteOperation(0, BOB, 2, 2); // deletes "cd"

        String[] results = Convergence.bothOrders("abcdef", insert, delete);

        assertEquals(results[0], results[1]);
        assertEquals("abXef", results[0]);
    }

    @Test
    void insertExactlyAtDeleteEndShiftsBack() {
        InsertOperation insert = new InsertOperation(0, ALICE, 2, "X"); // == delete's end
        DeleteOperation delete = new DeleteOperation(0, BOB, 0, 2); // deletes "ab"

        String[] results = Convergence.bothOrders("abcdef", insert, delete);

        assertEquals(results[0], results[1]);
        assertEquals("Xcdef", results[0]);
    }

    @Test
    void insertStrictlyInsideDeletedRangeSurvives() {
        // alice types "XYZ" into the middle of text bob concurrently deletes entirely.
        // alice's text survives even though bob's deletion swallows everything around it.
        InsertOperation insert = new InsertOperation(0, ALICE, 5, "XYZ");
        DeleteOperation delete = new DeleteOperation(0, BOB, 0, 11); // deletes all of "hello world"

        String[] results = Convergence.bothOrders("hello world", insert, delete);

        assertEquals(results[0], results[1]);
        assertEquals("XYZ", results[0]);
    }

    @Test
    void insertStrictlyInsideAPartialDeletedRangeSurvives() {
        InsertOperation insert = new InsertOperation(0, ALICE, 4, "XYZ"); // inside [2, 6)
        DeleteOperation delete = new DeleteOperation(0, BOB, 2, 4); // deletes "cdef"

        String[] results = Convergence.bothOrders("abcdefgh", insert, delete);

        assertEquals(results[0], results[1]);
        assertEquals("abXYZgh", results[0]);
    }
}
