package com.collabdoc.document;

import com.collabdoc.ot.CommittedOperation;
import com.collabdoc.ot.InsertOperation;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentSequencerConcurrencyTest {

    @RepeatedTest(20)
    void concurrentSubmissionsForSameDocumentGetContiguousRevisions() {
        DocumentSequencer sequencer = new DocumentSequencer(new InMemoryOperationLog());
        String documentId = "doc-1";
        int opCount = 200;

        List<CompletableFuture<List<CommittedOperation>>> futures = IntStream.range(0, opCount)
                .parallel()
                .mapToObj(i -> sequencer.submit(documentId, new InsertOperation(0, "site-" + i, 0, "x")))
                .collect(Collectors.toList());

        Set<Long> revisions = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .map(CommittedOperation::revision)
                .collect(Collectors.toSet());

        Set<Long> expected = LongStream.rangeClosed(1, opCount).boxed().collect(Collectors.toSet());
        assertEquals(expected, revisions);
    }

    @Test
    void differentDocumentsProceedIndependently() {
        DocumentSequencer sequencer = new DocumentSequencer(new InMemoryOperationLog());

        CommittedOperation a1 = sequencer.submit("doc-A", new InsertOperation(0, "alice", 0, "a")).join().get(0);
        CommittedOperation b1 = sequencer.submit("doc-B", new InsertOperation(0, "bob", 0, "b")).join().get(0);
        CommittedOperation a2 = sequencer.submit("doc-A", new InsertOperation(1, "alice", 1, "a2")).join().get(0);

        assertEquals(1, a1.revision());
        assertEquals(1, b1.revision());
        assertEquals(2, a2.revision());
    }
}
