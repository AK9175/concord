package com.collabdoc.ot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationTransformerDeleteDeleteTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String DOC = "0123456789";

    @Test
    void disjointRangesNoOverlapBeforeConverge() {
        DeleteOperation deleteA = new DeleteOperation(0, ALICE, 0, 2); // "01"
        DeleteOperation deleteB = new DeleteOperation(0, BOB, 5, 2); // "56"

        String[] results = Convergence.bothOrders(DOC, deleteA, deleteB);

        assertEquals(results[0], results[1]);
        assertEquals("234789", results[0]);
    }

    @Test
    void touchingRangesAtBoundaryDoNotOverlap() {
        DeleteOperation deleteA = new DeleteOperation(0, ALICE, 0, 3); // "012"
        DeleteOperation deleteB = new DeleteOperation(0, BOB, 3, 3); // "345"

        String[] results = Convergence.bothOrders(DOC, deleteA, deleteB);

        assertEquals(results[0], results[1]);
        assertEquals("6789", results[0]);
    }

    @Test
    void partiallyOverlappingRangesConverge() {
        DeleteOperation deleteA = new DeleteOperation(0, ALICE, 0, 5); // "01234"
        DeleteOperation deleteB = new DeleteOperation(0, BOB, 3, 5); // "34567"

        String[] results = Convergence.bothOrders(DOC, deleteA, deleteB);

        // union of [0,5) and [3,8) is [0,8) -> remaining "89"
        assertEquals(results[0], results[1]);
        assertEquals("89", results[0]);
    }

    @Test
    void oneRangeFullyContainsTheOtherConverge() {
        DeleteOperation outer = new DeleteOperation(0, ALICE, 2, 6); // "234567"
        DeleteOperation inner = new DeleteOperation(0, BOB, 4, 2); // "45"

        String[] results = Convergence.bothOrders(DOC, outer, inner);

        assertEquals(results[0], results[1]);
        assertEquals("0189", results[0]);
    }

    @Test
    void identicalRangesCollapseToNoOpOnSecondApplication() {
        DeleteOperation deleteA = new DeleteOperation(0, ALICE, 2, 4); // "2345"
        DeleteOperation deleteB = new DeleteOperation(0, BOB, 2, 4); // "2345"

        String[] results = Convergence.bothOrders(DOC, deleteA, deleteB);

        assertEquals(results[0], results[1]);
        assertEquals("016789", results[0]);
    }
}
