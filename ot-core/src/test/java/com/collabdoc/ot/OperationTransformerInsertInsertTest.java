package com.collabdoc.ot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationTransformerInsertInsertTest {

    private static String[] convergeBothOrders(String baseDoc, Operation opA, Operation opB) {
        return Convergence.bothOrders(baseDoc, opA, opB);
    }

    @Test
    void distinctPositionsConvergeRegardlessOfArrivalOrder() {
        InsertOperation opA = new InsertOperation(0, "alice", 0, "X");
        InsertOperation opB = new InsertOperation(0, "bob", 5, "Y");

        String[] results = convergeBothOrders("hello", opA, opB);

        assertEquals(results[0], results[1]);
        assertEquals("Xhello" + "Y", results[0]);
    }

    @Test
    void insertBeforeAnEarlierInsertIsUnaffected() {
        InsertOperation opA = new InsertOperation(0, "alice", 0, "A");
        InsertOperation opB = new InsertOperation(0, "bob", 3, "B");

        String[] results = convergeBothOrders("xyz", opA, opB);

        assertEquals(results[0], results[1]);
        assertEquals("AxyzB", results[0]);
    }

    @Test
    void samePositionTieBreakConvergesRegardlessOfArrivalOrder() {
        InsertOperation opAlice = new InsertOperation(0, "alice", 0, "A");
        InsertOperation opBob = new InsertOperation(0, "bob", 0, "B");

        String[] results = convergeBothOrders("", opAlice, opBob);

        assertEquals(results[0], results[1]);
        // "alice" < "bob" lexicographically, so alice's insert has priority at the tie.
        assertEquals("AB", results[0]);
    }

    @Test
    void samePositionTieBreakIsDeterminedByUserIdNotArrivalOrder() {
        // Same scenario, but bob's op is constructed/sent first this time -- the
        // resulting document must still place alice's text first, because priority
        // is decided by userId comparison, never by who got there first.
        InsertOperation opBob = new InsertOperation(0, "bob", 0, "B");
        InsertOperation opAlice = new InsertOperation(0, "alice", 0, "A");

        String[] results = convergeBothOrders("", opBob, opAlice);

        assertEquals(results[0], results[1]);
        assertEquals("AB", results[0]);
    }
}
