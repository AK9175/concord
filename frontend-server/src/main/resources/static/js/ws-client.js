// Opens a WebSocket scoped to one document (connection-tier routes by the
// connection's request path, not by message content -- see CP 2.4) and
// parses every frame as JSON before handing it to onMessage.
const WS_BASE = "ws://localhost:8081";

export function connectToDocument(documentId, onMessage) {
  const socket = new WebSocket(`${WS_BASE}/${documentId}`);
  socket.addEventListener("message", (event) => onMessage(JSON.parse(event.data)));
  return socket;
}
