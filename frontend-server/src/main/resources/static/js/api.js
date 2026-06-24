// REST calls to document-metadata-service. Cross-origin (different port from
// this static server), which is why that service sends CORS headers.
const API_BASE = "http://localhost:8083";

async function asJson(response) {
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || `request failed: ${response.status}`);
  }
  return response.json();
}

export function listDocuments() {
  return fetch(`${API_BASE}/docs`).then(asJson);
}

export function createDocument(title) {
  return fetch(`${API_BASE}/docs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title }),
  }).then(asJson);
}

export function renameDocument(documentId, title) {
  return fetch(`${API_BASE}/docs/${documentId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title }),
  }).then(asJson);
}

export function getDocument(documentId) {
  return fetch(`${API_BASE}/docs/${documentId}`).then(asJson);
}

export function listUsers(documentId) {
  return fetch(`${API_BASE}/docs/${documentId}/users`).then(asJson);
}

export function addUser(documentId, username, color) {
  return fetch(`${API_BASE}/docs/${documentId}/users`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, color }),
  }).then(asJson);
}

export function renameUser(documentId, userId, username, color) {
  return fetch(`${API_BASE}/docs/${documentId}/users/${userId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, color }),
  }).then(asJson);
}

// Deliberately not routed through asJson: a successful delete returns 204
// with no body, and asJson's response.json() would throw trying to parse
// that as JSON.
export async function deleteDocument(documentId) {
  const response = await fetch(`${API_BASE}/docs/${documentId}`, { method: "DELETE" });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || `request failed: ${response.status}`);
  }
}
