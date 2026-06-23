package com.collabdoc.document;

import com.collabdoc.ot.DeleteOperation;
import com.collabdoc.ot.InsertOperation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CP 2.3's literal verification: an op submitted with a stale baseRevision
 * (because something else committed in the meantime) must be transformed
 * against the gap before being applied -- not corrupt the document by being
 * applied at its now-stale literal position.
 */
class DocumentSequencerStaleRevisionTest {

    @Test
    void staleInsertIsTransformedAgainstTheGapBeforeCommitting() {
        DocumentSequencer sequencer = new DocumentSequencer(new InMemoryOperationLog());
        String documentId = "doc-1";

        // Establish the starting text: "the cat", at revision 1.
        sequencer.submit(documentId, new InsertOperation(0, "setup", 0, "the cat")).join();

        // Bob commits "Look, " at the front, based on revision 1 (no gap for him). Now
        // revision 2, text is "Look, the cat".
        sequencer.submit(documentId, new InsertOperation(1, "bob", 0, "Look, ")).join();

        // Alice created her edit (append "s" to make "cats") back when revision was
        // still 1 -- she never saw Bob's edit. Her op's literal position (7) was valid
        // against the OLD "the cat", but applying it literally to "Look, the cat" would
        // corrupt the document (landing inside "Look, " instead of after "cat").
        List<CommittedOperation> aliceResult =
                sequencer.submit(documentId, new InsertOperation(1, "alice", 7, "s")).join();

        assertEquals("Look, the cats", sequencer.currentText(documentId));
        // The committed log entry reflects the CORRECTED position, not Alice's stale one.
        assertEquals(1, aliceResult.size());
        assertEquals(13, ((InsertOperation) aliceResult.get(0).operation()).position());
        assertEquals(3, aliceResult.get(0).revision());
    }

    @Test
    void multipleOpsInTheGapAreTransformedInOrder() {
        DocumentSequencer sequencer = new DocumentSequencer(new InMemoryOperationLog());
        String documentId = "doc-1";

        sequencer.submit(documentId, new InsertOperation(0, "setup", 0, "0123456789")).join(); // revision 1
        sequencer.submit(documentId, new DeleteOperation(1, "bob", 0, 2)).join();               // revision 2: removes "01"
        sequencer.submit(documentId, new DeleteOperation(1, "carol", 8, 2)).join();              // revision 3: removes "89"

        // Dave's op was created against revision 1 (he saw "0123456789"), unaware of
        // either of the two deletes that already landed in the gap.
        List<CommittedOperation> daveResult =
                sequencer.submit(documentId, new InsertOperation(1, "dave", 5, "X")).join();

        // Bob's delete shifts dave's position back by 2 (5 -> 3); carol's committed
        // delete (transformed to (6,2), after accounting for Bob's delete too) doesn't
        // affect position 3 at all -- it's entirely after it.
        assertEquals("234X567", sequencer.currentText(documentId));
        assertEquals(1, daveResult.size());
        assertEquals(3, ((InsertOperation) daveResult.get(0).operation()).position());
    }

    @Test
    void insertSurvivesAnAlreadyCommittedDelete() {
        DocumentSequencer sequencer = new DocumentSequencer(new InMemoryOperationLog());
        String documentId = "doc-1";

        sequencer.submit(documentId, new InsertOperation(0, "setup", 0, "hello world")).join(); // revision 1

        // Bob's delete commits first.
        sequencer.submit(documentId, new DeleteOperation(1, "bob", 0, 11)).join(); // revision 2: deletes everything

        // Carol's insert was created against revision 1 too (truly concurrent with
        // Bob's delete), and lands inside the range Bob already deleted -- but since
        // Bob's delete is the one already committed, transforming Carol's INSERT
        // against it just relocates her insert (never splits an insert), so this is a
        // single log entry, not a split.
        List<CommittedOperation> carolResult =
                sequencer.submit(documentId, new InsertOperation(1, "carol", 5, "XYZ")).join();

        assertEquals("XYZ", sequencer.currentText(documentId));
        assertEquals(1, carolResult.size());
    }

    @Test
    void staleDeleteSplitsIntoMultipleLogEntriesWhenGapContainsASurvivingInsert() {
        DocumentSequencer sequencer = new DocumentSequencer(new InMemoryOperationLog());
        String documentId = "doc-1";

        sequencer.submit(documentId, new InsertOperation(0, "setup", 0, "0123456789")).join(); // revision 1

        // Alice's insert lands in the middle and commits first.
        sequencer.submit(documentId, new InsertOperation(1, "alice", 5, "XYZ")).join(); // revision 2

        // Bob's delete was created against revision 1 (he intended to delete the
        // WHOLE original "0123456789"), unaware Alice's "XYZ" would land in the
        // middle of that range by the time his delete actually commits. Because it's
        // the DELETE side being transformed against the gap's INSERT, and the insert
        // survives, Bob's single delete must split into two pieces around it.
        List<CommittedOperation> bobResult =
                sequencer.submit(documentId, new DeleteOperation(1, "bob", 0, 10)).join();

        assertEquals("XYZ", sequencer.currentText(documentId));
        assertEquals(2, bobResult.size());
    }
}
