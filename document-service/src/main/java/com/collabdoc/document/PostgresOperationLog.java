package com.collabdoc.document;

import com.collabdoc.ot.DeleteOperation;
import com.collabdoc.ot.InsertOperation;
import com.collabdoc.ot.Operation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Postgres-backed OperationLog (CP 5.1): same interface InMemoryOperationLog
 * satisfies, so DocumentSequencer/DocumentCommitter need no changes to use
 * this instead -- that was the entire point of designing OperationLog as an
 * interface back in CP 2.2.
 *
 * append()'s revision assignment (SELECT MAX+1, then INSERT) relies on the
 * SAME single-writer-per-document guarantee InMemoryOperationLog already
 * depended on: DocumentSequencer's per-document executor ensures only one
 * thread ever calls append() for a given documentId at a time, so there's no
 * race between the SELECT and the INSERT despite them being two statements.
 * The PRIMARY KEY (document_id, revision) is a defensive backstop, not the
 * primary correctness mechanism: if that single-writer invariant were ever
 * violated by a future bug, the database would reject the duplicate with a
 * constraint violation instead of silently corrupting the log.
 */
public class PostgresOperationLog implements OperationLog {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostgresOperationLog(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             var statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS operations (
                        document_id TEXT NOT NULL,
                        revision BIGINT NOT NULL,
                        user_id TEXT NOT NULL,
                        op_type TEXT NOT NULL,
                        base_revision BIGINT NOT NULL,
                        op_data JSONB NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        PRIMARY KEY (document_id, revision)
                    )
                    """);
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to initialize operations table", ex);
        }
    }

    @Override
    public CommittedOperation append(String documentId, Operation operation) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            long nextRevision;
            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT COALESCE(MAX(revision), 0) + 1 FROM operations WHERE document_id = ?")) {
                select.setString(1, documentId);
                try (ResultSet rs = select.executeQuery()) {
                    rs.next();
                    nextRevision = rs.getLong(1);
                }
            }

            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO operations (document_id, revision, user_id, op_type, base_revision, op_data) "
                            + "VALUES (?, ?, ?, ?, ?, ?::jsonb)")) {
                insert.setString(1, documentId);
                insert.setLong(2, nextRevision);
                insert.setString(3, operation.userId());
                insert.setString(4, opType(operation));
                insert.setLong(5, operation.baseRevision());
                insert.setString(6, opDataJson(operation));
                insert.executeUpdate();
            }

            conn.commit();
            return new CommittedOperation(nextRevision, operation);
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to append operation for " + documentId, ex);
        }
    }

    @Override
    public List<CommittedOperation> readFrom(String documentId, long fromRevision) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT revision, user_id, op_type, base_revision, op_data FROM operations "
                             + "WHERE document_id = ? AND revision > ? ORDER BY revision ASC")) {
            stmt.setString(1, documentId);
            stmt.setLong(2, fromRevision);
            try (ResultSet rs = stmt.executeQuery()) {
                List<CommittedOperation> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toCommittedOperation(rs));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to read operations for " + documentId, ex);
        }
    }

    @Override
    public long currentRevision(String documentId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COALESCE(MAX(revision), 0) FROM operations WHERE document_id = ?")) {
            stmt.setString(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to read current revision for " + documentId, ex);
        }
    }

    private CommittedOperation toCommittedOperation(ResultSet rs) throws SQLException {
        long revision = rs.getLong("revision");
        String userId = rs.getString("user_id");
        String opType = rs.getString("op_type");
        long baseRevision = rs.getLong("base_revision");
        String opDataJson = rs.getString("op_data");
        return new CommittedOperation(revision, toOperation(opType, baseRevision, userId, opDataJson));
    }

    private static String opType(Operation operation) {
        return operation instanceof InsertOperation ? "insert" : "delete";
    }

    private String opDataJson(Operation operation) {
        try {
            if (operation instanceof InsertOperation insert) {
                return objectMapper.writeValueAsString(Map.of("position", insert.position(), "text", insert.text()));
            }
            DeleteOperation delete = (DeleteOperation) operation;
            return objectMapper.writeValueAsString(Map.of("position", delete.position(), "length", delete.length()));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize operation data", ex);
        }
    }

    private Operation toOperation(String opType, long baseRevision, String userId, String opDataJson) {
        try {
            JsonNode data = objectMapper.readTree(opDataJson);
            int position = data.get("position").asInt();
            if ("insert".equals(opType)) {
                return new InsertOperation(baseRevision, userId, position, data.get("text").asText());
            }
            return new DeleteOperation(baseRevision, userId, position, data.get("length").asInt());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to deserialize operation data", ex);
        }
    }
}
