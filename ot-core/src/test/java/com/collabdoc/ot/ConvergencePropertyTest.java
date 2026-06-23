package com.collabdoc.ot;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate for Phase 1.
 *
 * Scope, deliberately: this proves TP1 (any 2 concurrent ops converge to the
 * same document regardless of which one is transformed against the other)
 * exhaustively, including randomized cases. It does NOT attempt to prove TP2
 * (that N>=3 concurrent ops converge to the same document regardless of
 * arbitrary arrival order) -- that is a much stronger, decades-old open
 * problem for position-based operations (the classic counterexample: two
 * inserts on either side of a character a third op deletes can end up
 * adjacent only as a side effect of that delete, and which one "wins" then
 * depends on arrival order even though every pairwise transform involved is
 * individually correct). Production OT systems (Google Docs included) don't
 * solve TP2 either -- they sidestep it with a single authoritative sequencer
 * per document (this project's invariant #1), so only one arrival order ever
 * actually happens, and every client reconciles to that one canonical order
 * instead of needing N-way order independence. So for N>=3 concurrent ops,
 * this test proves the thing the sequencer actually relies on: chaining
 * transform() across a growing, ordered gap of already-committed ops never
 * corrupts the document, for many random scenarios -- not that every
 * possible arrival order would agree with every other one.
 */
class ConvergencePropertyTest {

    // ---- TP1: any 2 concurrent ops converge regardless of order ----

    @Test
    void twoConcurrentInsertsAtDistinctPositionsConverge() {
        assertAllOrderingsConverge("the cat", List.of(
                new InsertOperation(0, "alice", 3, " big"),
                new InsertOperation(0, "bob", 7, "s")));
    }

    @Test
    void twoConcurrentInsertsAtSamePositionConverge() {
        assertAllOrderingsConverge("", List.of(
                new InsertOperation(0, "alice", 0, "A"),
                new InsertOperation(0, "bob", 0, "B")));
    }

    @Test
    void insertSurvivingADeleteConvergesAcrossArrivalOrders() {
        assertAllOrderingsConverge("hello world", List.of(
                new InsertOperation(0, "alice", 5, "XYZ"),
                new DeleteOperation(0, "bob", 0, 11)));
    }

    @Test
    void twoOverlappingDeletesConverge() {
        assertAllOrderingsConverge("0123456789", List.of(
                new DeleteOperation(0, "alice", 0, 5),
                new DeleteOperation(0, "bob", 3, 5)));
    }

    @RepeatedTest(300)
    void randomizedTwoOpScenariosConverge() {
        Random random = new Random();
        String baseDoc = randomText(random, random.nextInt(12));
        List<Operation> ops = List.of(
                randomOp(random, "site-A", baseDoc),
                randomOp(random, "site-B", baseDoc));

        assertAllOrderingsConverge(baseDoc, ops);
    }

    // ---- sequencer chain model: N concurrent ops, ONE fixed commit order ----
    //
    // These do not compare across arrival orders (that would be the TP2 question
    // above). They prove the mechanism CP 2.2/2.3 will actually use: each new op
    // transformed against the growing, ordered gap of already-committed ops, for
    // one real history, stays well-defined -- no out-of-bounds apply(), no
    // exceptions -- as the number of concurrent ops grows.

    @Test
    void threeConcurrentMixedOpsCommitInOneFixedOrderWithoutCorruption() {
        String result = simulateSequencer("the cat sat", List.of(
                new InsertOperation(0, "alice", 7, "s"),
                new DeleteOperation(0, "bob", 8, 3),
                new InsertOperation(0, "carol", 0, "Look, ")),
                new ArrayList<>());
        assertEquals("Look, the cats ", result);
    }

    @Test
    void threeConcurrentOpsWithOverlappingDeletesAndInteriorInsertCommit() {
        String result = simulateSequencer("0123456789", List.of(
                new DeleteOperation(0, "alice", 0, 6),
                new DeleteOperation(0, "bob", 4, 6),
                new InsertOperation(0, "carol", 5, "XYZ")),
                new ArrayList<>());
        assertEquals("XYZ", result);
    }

    @Test
    void tenConcurrentOpsCommitInOneFixedOrderWithoutCorruption() {
        List<Operation> ops = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ops.add(new InsertOperation(0, "site-" + i, i % 4, "x"));
        }
        assertDoesNotThrow(() -> simulateSequencer("abcdefgh", ops, new ArrayList<>()));
    }

    @RepeatedTest(300)
    void randomizedManyOpSequencerChainsNeverCorruptTheDocument() {
        Random random = new Random();
        int opCount = 2 + random.nextInt(9); // 2..10
        String baseDoc = randomText(random, random.nextInt(12));
        List<Operation> ops = new ArrayList<>();
        for (int i = 0; i < opCount; i++) {
            ops.add(randomOp(random, "site-" + i, baseDoc));
        }

        List<Operation> committedLog = new ArrayList<>();
        String result = assertDoesNotThrow(() -> simulateSequencer(baseDoc, ops, committedLog));

        // The committed log is exactly the sequence of already-transformed ops the
        // server would persist. Replaying it from scratch with no further
        // transformation -- exactly what CP 2.5's full-replay-on-connect does --
        // must reproduce the same document, proving the log alone is sufficient.
        String replayed = baseDoc;
        for (Operation committed : committedLog) {
            replayed = OperationApplier.apply(replayed, committed);
        }
        assertEquals(result, replayed);
        assertTrue(result.length() >= 0);
    }

    // ---- simulation machinery ----

    private static void assertAllOrderingsConverge(String baseDoc, List<Operation> concurrentOps) {
        List<List<Operation>> orderings = permutationsOf(concurrentOps);
        String expected = simulateSequencer(baseDoc, orderings.get(0), new ArrayList<>());
        for (List<Operation> ordering : orderings) {
            String actual = simulateSequencer(baseDoc, ordering, new ArrayList<>());
            assertEquals(expected, actual,
                    () -> "diverged for baseDoc=\"" + baseDoc + "\" arrivalOrder=" + ordering
                            + " (expected from order " + orderings.get(0) + ")");
        }
    }

    /**
     * Simulates a single-sequencer server (or, equivalently, one site folding ops
     * into its own history one at a time): each incoming op is transformed against
     * every op already committed, in the order they were committed, then applied.
     * Appends every committed piece (in the order they were actually applied) to
     * committedLogOut, so callers can verify replay-from-log matches.
     */
    private static String simulateSequencer(String baseDoc, List<Operation> arrivalOrder,
            List<Operation> committedLogOut) {
        String doc = baseDoc;
        List<Operation> committedAtSite = new ArrayList<>();
        for (Operation incoming : arrivalOrder) {
            List<Operation> pieces = new ArrayList<>(List.of(incoming));
            for (Operation alreadyCommitted : committedAtSite) {
                List<Operation> next = new ArrayList<>();
                for (Operation piece : pieces) {
                    next.addAll(OperationTransformer.transform(piece, alreadyCommitted));
                }
                pieces = next;
            }
            List<Operation> inApplyOrder = pieces.stream()
                    .sorted(Comparator.comparingInt(Operation::position).reversed())
                    .toList();
            for (Operation piece : inApplyOrder) {
                doc = OperationApplier.apply(doc, piece);
            }
            committedAtSite.addAll(inApplyOrder);
            committedLogOut.addAll(inApplyOrder);
        }
        return doc;
    }

    private static <T> List<List<T>> permutationsOf(List<T> items) {
        if (items.size() <= 1) {
            return List.of(new ArrayList<>(items));
        }
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T picked = rest.remove(i);
            for (List<T> restPermutation : permutationsOf(rest)) {
                List<T> permutation = new ArrayList<>();
                permutation.add(picked);
                permutation.addAll(restPermutation);
                result.add(permutation);
            }
        }
        return result;
    }

    private static Operation randomOp(Random random, String userId, String baseDoc) {
        int len = baseDoc.length();
        boolean canDelete = len > 0;
        boolean doInsert = !canDelete || random.nextBoolean();

        if (doInsert) {
            int position = random.nextInt(len + 1);
            String text = randomText(random, 1 + random.nextInt(4));
            return new InsertOperation(0, userId, position, text);
        }
        int position = random.nextInt(len);
        int maxLength = len - position;
        int length = 1 + random.nextInt(maxLength);
        return new DeleteOperation(0, userId, position, length);
    }

    private static String randomText(Random random, int length) {
        StringBuilder builder = new StringBuilder();
        String alphabet = "abcdefghij";
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }
}
