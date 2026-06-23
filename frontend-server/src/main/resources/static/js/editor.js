import { getDocument } from "./api.js";
import { pickIdentity } from "./identity-picker.js";
import { connectToDocument } from "./ws-client.js";
import { applyHistory, diffToOps } from "./ot-client.js";

// "Sharing = the URL": the document id is whatever's in the path. No
// server-side routing decides this -- frontend-server falls back to this
// same page for any path that isn't a real static asset.
const documentId = window.location.pathname.slice(1);

const titleEl = document.getElementById("doc-title");
const textareaEl = document.getElementById("doc-textarea");

const currentUser = await pickIdentity(documentId);

document.getElementById("current-user-label").textContent = currentUser.username;
document.getElementById("editor-area").classList.remove("hidden");

getDocument(documentId).then((doc) => {
  titleEl.textContent = doc.title || "Untitled document";
});

// The last revision this client knows about. Every outgoing op is tagged
// with whatever this is AT THE MOMENT IT'S SENT -- not optimistically
// incremented locally -- because the server's gap-transform (CP 2.3) is what
// reconciles a stale baseRevision correctly; the client doesn't need to
// duplicate that logic, just report what it last actually knew.
let knownRevision = 0;
// The textarea's value as of the last op we sent, so the next input event
// diffs against what the server has, not against whatever the browser
// happens to hold (those are the same right after sending, but tracking it
// explicitly avoids relying on that coincidence).
let previousValue = "";

const socket = connectToDocument(documentId, (message) => {
  if (message.type === "history") {
    const text = applyHistory(message.committed);
    textareaEl.value = text;
    previousValue = text;
    knownRevision = message.committed.length > 0
        ? message.committed[message.committed.length - 1].revision
        : 0;
  } else if (message.type === "ack") {
    for (const committed of message.committed) {
      knownRevision = Math.max(knownRevision, committed.revision);
    }
  }
  // "update" (another client's broadcast edit) is CP 3.6 -- applying it
  // correctly requires transforming it against this client's own pending
  // (sent-but-not-yet-acked) ops, which doesn't exist yet.
});

textareaEl.addEventListener("input", () => {
  const newValue = textareaEl.value;
  for (const diffOp of diffToOps(previousValue, newValue)) {
    const operation = diffOp.kind === "insert"
        ? { type: "insert", baseRevision: knownRevision, userId: currentUser.userId, position: diffOp.position, text: diffOp.text }
        : { type: "delete", baseRevision: knownRevision, userId: currentUser.userId, position: diffOp.position, length: diffOp.length };
    socket.send(JSON.stringify(operation));
  }
  previousValue = newValue;
});
