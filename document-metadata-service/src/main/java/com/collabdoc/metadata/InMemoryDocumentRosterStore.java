package com.collabdoc.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryDocumentRosterStore implements DocumentRosterStore {

    private final Map<String, CopyOnWriteArrayList<User>> rostersByDocument = new ConcurrentHashMap<>();

    @Override
    public User addUser(String documentId, String username, String color) {
        String userId = UUID.randomUUID().toString();
        User user = new User(userId, username, color);
        rostersByDocument.computeIfAbsent(documentId, id -> new CopyOnWriteArrayList<>()).add(user);
        return user;
    }

    @Override
    public List<User> listUsers(String documentId) {
        CopyOnWriteArrayList<User> roster = rostersByDocument.get(documentId);
        return roster == null ? List.of() : new ArrayList<>(roster);
    }
}
