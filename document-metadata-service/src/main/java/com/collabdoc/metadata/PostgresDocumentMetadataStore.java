package com.collabdoc.metadata;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PostgresDocumentMetadataStore implements DocumentMetadataStore {

    private final DataSource dataSource;

    public PostgresDocumentMetadataStore(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             var statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS documents (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL
                    )
                    """);
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to initialize documents table", ex);
        }
    }

    @Override
    public Document create(String title) {
        String id = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement insert = conn.prepareStatement(
                     "INSERT INTO documents (id, title) VALUES (?, ?)")) {
            insert.setString(1, id);
            insert.setString(2, title);
            insert.executeUpdate();
            return new Document(id, title);
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to create document", ex);
        }
    }

    @Override
    public Optional<Document> get(String documentId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement select = conn.prepareStatement(
                     "SELECT id, title FROM documents WHERE id = ?")) {
            select.setString(1, documentId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? Optional.of(toDocument(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to fetch document " + documentId, ex);
        }
    }

    @Override
    public List<Document> list() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement select = conn.prepareStatement("SELECT id, title FROM documents")) {
            try (ResultSet rs = select.executeQuery()) {
                List<Document> documents = new ArrayList<>();
                while (rs.next()) {
                    documents.add(toDocument(rs));
                }
                return documents;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to list documents", ex);
        }
    }

    @Override
    public Optional<Document> rename(String documentId, String newTitle) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement update = conn.prepareStatement(
                     "UPDATE documents SET title = ? WHERE id = ?")) {
            update.setString(1, newTitle);
            update.setString(2, documentId);
            int updated = update.executeUpdate();
            return updated == 0 ? Optional.empty() : Optional.of(new Document(documentId, newTitle));
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to rename document " + documentId, ex);
        }
    }

    private static Document toDocument(ResultSet rs) throws SQLException {
        return new Document(rs.getString("id"), rs.getString("title"));
    }
}
