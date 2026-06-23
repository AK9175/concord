package com.collabdoc.document;

class InMemoryOperationLogTest extends OperationLogContractTest {

    @Override
    protected OperationLog createLog() {
        return new InMemoryOperationLog();
    }
}
