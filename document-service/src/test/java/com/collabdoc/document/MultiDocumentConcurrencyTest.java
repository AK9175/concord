package com.collabdoc.document;

import com.collabdoc.ot.InsertOperation;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CP 4.1's literal verification: documents stay fully independent under
 * REAL concurrent load -- not just "one after another on different docs"
 * (already covered by DocumentSequencerConcurrencyTest), but many documents
 * all being hammered by concurrent submissions AT THE SAME TIME, from many
 * threads, with the submissions themselves interleaved across documents.
 *
 * This isn't new functionality -- DocumentSequencer, InMemoryOperationLog,
 * and (in connection-tier) DocumentSessionRegistry have all been keyed by
 * documentId since CP 2.2/2.4, so independence already holds by
 * construction. This test exists to prove that under load, not just assert
 * it by reading the code.
 */
class MultiDocumentConcurrencyTest {

    @RepeatedTest(10)
    void manyDocumentsHandleConcurrentEditsWithZeroCrossContamination() {
        DocumentSequencer sequencer = new DocumentSequencer(new InMemoryOperationLog());
        int documentCount = 5;
        int opsPerDocument = 50;
        List<String> documentIds = IntStream.range(0, documentCount)
                .mapToObj(i -> "doc-" + i)
                .toList();

        // Interleave submissions across ALL documents from many threads at once,
        // rather than finishing one document before starting the next.
        List<CompletableFuture<List<CommittedOperation>>> futures = documentIds.stream()
                .parallel()
                .flatMap(documentId -> IntStream.range(0, opsPerDocument)
                        .parallel()
                        .mapToObj(i -> sequencer.submit(documentId,
                                new InsertOperation(0, documentId + "-user", 0, documentId + "-marker-" + i + ";"))))
                .toList();

        futures.forEach(CompletableFuture::join);

        for (String documentId : documentIds) {
            String text = sequencer.currentText(documentId);
            List<String> fragments = new ArrayList<>(List.of(text.split(";")));
            fragments.removeIf(String::isEmpty);

            assertEquals(opsPerDocument, fragments.size(),
                    documentId + ": expected " + opsPerDocument + " of its own fragments, found " + fragments.size());
            for (String fragment : fragments) {
                assertTrue(fragment.startsWith(documentId + "-marker-"),
                        "cross-contamination: " + documentId + " contains foreign fragment \"" + fragment + "\"");
            }
        }
    }
}
