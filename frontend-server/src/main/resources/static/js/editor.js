import { getDocument } from "./api.js";
import { pickIdentity } from "./identity-picker.js";
import { connectToDocument } from "./ws-client.js";
import { applyHistory, applyAll, diffToOps, reconcileIncoming } from "./ot-client.js";

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

// The last revision this client knows about. Tagged onto every outgoing op
// as-sent, never optimistically bumped locally -- the server's gap-transform
// is what reconciles staleness correctly.
let knownRevision = 0;

// The textarea's value as of the last op we accounted for, so the next
// input event diffs against what we've already told the server about (or
// are about to), not just whatever the DOM happens to hold.
let previousValue = "";

// Every locally-applied-but-not-yet-acked edit, sent or not, in creation
// order. Used to transform incoming broadcasts against this client's own
// optimistic edits (project invariant #4).
let pendingPieces = [];

// sendIds not yet transmitted to the server.
const sendQueue = [];

let nextSendId = 0;

// The ONE sendId currently in flight (sent, awaiting ack), or null. Only one
// op is ever outstanding at a time -- see the comment on sendNextIfIdle for
// why: chaining an incoming broadcast through TWO OR MORE of this client's
// own pending ops (e.g. the delete+insert pair from a single select-and-type)
// can land it at an artificial position collision with a later pending piece,
// triggering the insert/insert tie-break for a tie that was never real --
// just an artifact of the chain. Keeping at most one op outstanding removes
// the only way that situation reliably arises in practice (a multi-op local
// edit no longer overlaps in flight with a concurrent broadcast).
let outstandingSendId = null;

function buildOperation(diffOp) {
  return diffOp.kind === "insert"
      ? { type: "insert", baseRevision: knownRevision, userId: currentUser.userId, position: diffOp.position, text: diffOp.text }
      : { type: "delete", baseRevision: knownRevision, userId: currentUser.userId, position: diffOp.position, length: diffOp.length };
}

function sendNextIfIdle() {
  if (outstandingSendId !== null || sendQueue.length === 0) {
    return;
  }
  const sendId = sendQueue.shift();
  outstandingSendId = sendId;
  const entry = pendingPieces.find((piece) => piece.sendId === sendId);
  socket.send(JSON.stringify(entry.operation));
}

// Belt-and-suspenders safety net: serializing sends (above) removes the
// reliable trigger for the chain-collision case, but doesn't formally prove
// it can never happen (e.g. a single outstanding op that itself splits
// during reconciliation, then a second broadcast arrives before its ack).
// Asking for history again costs one extra round-trip and is always safe to
// apply -- pendingPieces is empty when we ask, so there's nothing local to
// lose -- and it's only triggered right after the pending queue empties, not
// on every keystroke.
function requestResync() {
  socket.send(JSON.stringify({ type: "resync" }));
}

const socket = connectToDocument(documentId, (message) => {
  if (message.type === "history") {
    const text = applyHistory(message.committed);
    const latestRevision = message.committed.length
        ? message.committed[message.committed.length - 1].revision
        : 0;
    knownRevision = Math.max(knownRevision, latestRevision);
    // Never stomp on edits the user made locally that the server doesn't
    // know about yet -- only trust a history snapshot's TEXT when nothing of
    // ours is still outstanding.
    if (pendingPieces.length === 0) {
      textareaEl.value = text;
      previousValue = text;
    }
  } else if (message.type === "ack") {
    pendingPieces = pendingPieces.filter((piece) => piece.sendId !== outstandingSendId);
    outstandingSendId = null;
    for (const committed of message.committed) {
      knownRevision = Math.max(knownRevision, committed.revision);
    }
    if (pendingPieces.length === 0) {
      requestResync();
    }
    sendNextIfIdle();
  } else if (message.type === "update") {
    const incomingOps = message.committed.map((c) => c.operation);
    const { toApply, newPendingPieces } = reconcileIncoming(pendingPieces, incomingOps);
    textareaEl.value = applyAll(textareaEl.value, toApply);
    previousValue = textareaEl.value;
    pendingPieces = newPendingPieces;
    for (const committed of message.committed) {
      knownRevision = Math.max(knownRevision, committed.revision);
    }
  }
});

textareaEl.addEventListener("input", () => {
  const newValue = textareaEl.value;
  for (const diffOp of diffToOps(previousValue, newValue)) {
    const operation = buildOperation(diffOp);
    const sendId = nextSendId++;
    pendingPieces.push({ sendId, operation });
    sendQueue.push(sendId);
  }
  previousValue = newValue;
  sendNextIfIdle();
});
