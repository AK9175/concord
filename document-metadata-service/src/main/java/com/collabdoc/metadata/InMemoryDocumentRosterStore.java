package com.collabdoc.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryDocumentRosterStore implements DocumentRosterStore {

    private final Map<String, Map<String, User>> rostersByDocument = new ConcurrentHashMap<>();

    @Override
    public User addUser(String documentId, String username, String color) {
        String userId = UUID.randomUUID().toString();
        User user = new User(userId, username, color);
        rosterFor(documentId).put(userId, user);
        return user;
    }

    @Override
    public List<User> listUsers(String documentId) {
        Map<String, User> roster = rostersByDocument.get(documentId);
        return roster == null ? List.of() : new ArrayList<>(roster.values());
    }

    @Override
    public Optional<User> renameUser(String documentId, String userId, String username, String color) {
        Map<String, User> roster = rostersByDocument.get(documentId);
        if (roster == null || !roster.containsKey(userId)) {
            return Optional.empty();
        }
        User renamed = new User(userId, username, color);
        roster.put(userId, renamed);
        return Optional.of(renamed);
    }

    private Map<String, User> rosterFor(String documentId) {
        return rostersByDocument.computeIfAbsent(documentId, id -> new ConcurrentHashMap<>());
    }
}
