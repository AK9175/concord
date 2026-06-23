package com.collabdoc.ot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void insertOperationConstructsWithExpectedFields() {
        InsertOperation op = new InsertOperation(5, "user-1", 3, "hello");

        assertEquals(5, op.baseRevision());
        assertEquals("user-1", op.userId());
        assertEquals(3, op.position());
        assertEquals("hello", op.text());
    }

    @Test
    void deleteOperationConstructsWithExpectedFields() {
        DeleteOperation op = new DeleteOperation(7, "user-2", 4, 2);

        assertEquals(7, op.baseRevision());
        assertEquals("user-2", op.userId());
        assertEquals(4, op.position());
        assertEquals(2, op.length());
    }

    @Test
    void insertOperationRoundTripsThroughJson() throws Exception {
        InsertOperation original = new InsertOperation(12, "site-A", 8, "world");

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"type\":\"insert\""), "wire form must carry a type discriminator");

        Operation deserialized = mapper.readValue(json, Operation.class);

        assertInstanceOf(InsertOperation.class, deserialized);
        assertEquals(original, deserialized);
    }

    @Test
    void deleteOperationRoundTripsThroughJson() throws Exception {
        DeleteOperation original = new DeleteOperation(3, "site-B", 0, 6);

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"type\":\"delete\""), "wire form must carry a type discriminator");

        Operation deserialized = mapper.readValue(json, Operation.class);

        assertInstanceOf(DeleteOperation.class, deserialized);
        assertEquals(original, deserialized);
    }
}
