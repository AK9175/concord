package com.collabdoc.ot;

/**
 * Test helper: simulates two sites starting from the same baseDoc, each
 * applying their own op immediately and then receiving the other's op
 * (transformed against their own). Both sites must end up with the
 * identical document -- this is the convergence property the whole OT
 * scheme exists to guarantee.
 */
final class Convergence {

    private Convergence() {
    }

    static String[] bothOrders(String baseDoc, Operation opA, Operation opB) {
        String siteA = OperationApplier.apply(baseDoc, opA);
        siteA = OperationApplier.applyAll(siteA, OperationTransformer.transform(opB, opA));

        String siteB = OperationApplier.apply(baseDoc, opB);
        siteB = OperationApplier.applyAll(siteB, OperationTransformer.transform(opA, opB));

        return new String[] {siteA, siteB};
    }
}
