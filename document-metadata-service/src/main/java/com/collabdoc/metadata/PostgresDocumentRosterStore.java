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

public class PostgresDocumentRosterStore implements DocumentRosterStore {

    private final DataSource dataSource;

    public PostgresDocumentRosterStore(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             var statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS document_roster (
                        document_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        username TEXT NOT NULL,
                        color TEXT NOT NULL,
                        PRIMARY KEY (document_id, user_id)
                    )
                    """);
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to initialize document_roster table", ex);
        }
    }

    @Override
    public User addUser(String documentId, String username, String color) {
        String userId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement insert = conn.prepareStatement(
                     "INSERT INTO document_roster (document_id, user_id, username, color) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, documentId);
            insert.setString(2, userId);
            insert.setString(3, username);
            insert.setString(4, color);
            insert.executeUpdate();
            return new User(userId, username, color);
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to add user to roster for " + documentId, ex);
        }
    }

    @Override
    public List<User> listUsers(String documentId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement select = conn.prepareStatement(
                     "SELECT user_id, username, color FROM document_roster WHERE document_id = ?")) {
            select.setString(1, documentId);
            try (ResultSet rs = select.executeQuery()) {
                List<User> users = new ArrayList<>();
                while (rs.next()) {
                    users.add(toUser(rs));
                }
                return users;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to list roster for " + documentId, ex);
        }
    }

    @Override
    public Optional<User> renameUser(String documentId, String userId, String username, String color) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement update = conn.prepareStatement(
                     "UPDATE document_roster SET username = ?, color = ? WHERE document_id = ? AND user_id = ?")) {
            update.setString(1, username);
            update.setString(2, color);
            update.setString(3, documentId);
            update.setString(4, userId);
            int updated = update.executeUpdate();
            return updated == 0 ? Optional.empty() : Optional.of(new User(userId, username, color));
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to rename user " + userId + " for " + documentId, ex);
        }
    }

    @Override
    public void deleteRoster(String documentId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement delete = conn.prepareStatement("DELETE FROM document_roster WHERE document_id = ?")) {
            delete.setString(1, documentId);
            delete.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("failed to delete roster for " + documentId, ex);
        }
    }

    private static User toUser(ResultSet rs) throws SQLException {
        return new User(rs.getString("user_id"), rs.getString("username"), rs.getString("color"));
    }
}
