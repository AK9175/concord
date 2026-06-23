import { getDocument, renameUser } from "./api.js";
import { pickIdentity } from "./identity-picker.js";
import { connectToDocument } from "./ws-client.js";
import { applyHistory, applyAll, diffToOps, reconcileIncoming } from "./ot-client.js";
import { renderCursors } from "./cursor-overlay.js";

// "Sharing = the URL": the document id is whatever's in the path. No
// server-side routing decides this -- frontend-server falls back to this
// same page for any path that isn't a real static asset.
const documentId = window.location.pathname.slice(1);

const titleEl = document.getElementById("doc-title");
const textareaEl = document.getElementById("doc-textarea");
const userLabelEl = document.getElementById("current-user-label");

const currentUser = await pickIdentity(documentId);

userLabelEl.textContent = currentUser.username;
document.getElementById("editor-area").classList.remove("hidden");

getDocument(documentId).then((doc) => {
  titleEl.textContent = doc.title || "Untitled document";
});

// CP 4.2: renaming changes username/color in the persistent per-document
// roster without touching the userId -- every op/cursor already attributed
// to this userId stays attributed to the same identity, just displayed with
// a new name going forward. Other clients see the new name next time they
// fetch the roster (e.g. on reconnect); live push without a refetch is
// deferred to CP 4.3/4.4, which need to build a connection-tier <-> roster
// bridge anyway for presence.
const renameFormEl = document.getElementById("rename-self-form");
const renameInputEl = document.getElementById("rename-self-input");
const renameColorEl = document.getElementById("rename-self-color");

userLabelEl.addEventListener("click", () => {
  renameInputEl.value = currentUser.username;
  renameColorEl.value = currentUser.color;
  renameFormEl.classList.remove("hidden");
  renameFormEl.classList.add("flex");
  renameInputEl.focus();
  renameInputEl.select();
});

document.getElementById("rename-self-cancel").addEventListener("click", () => {
  renameFormEl.classList.add("hidden");
  renameFormEl.classList.remove("flex");
});

renameFormEl.addEventListener("submit", async (event) => {
  event.preventDefault();
  const username = renameInputEl.value.trim();
  if (!username) {
    return;
  }
  const updated = await renameUser(documentId, currentUser.userId, username, renameColorEl.value);
  currentUser.username = updated.username;
  currentUser.color = updated.color;
  userLabelEl.textContent = currentUser.username;
  renameFormEl.classList.add("hidden");
  renameFormEl.classList.remove("flex");
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

// baseRevision is deliberately NOT set here -- see sendNextIfIdle. Position/
// text/length/userId are correct as soon as they're computed (they reflect
// this client's own optimistic text, already advanced past any of its own
// earlier queued-but-unsent ops); baseRevision is only meaningful relative
// to whatever knownRevision is AT THE MOMENT OF ACTUAL TRANSMISSION.
function buildOperation(diffOp) {
  return diffOp.kind === "insert"
      ? { type: "insert", userId: currentUser.userId, position: diffOp.position, text: diffOp.text }
      : { type: "delete", userId: currentUser.userId, position: diffOp.position, length: diffOp.length };
}

function sendNextIfIdle() {
  if (outstandingSendId !== null || sendQueue.length === 0) {
    return;
  }
  const sendId = sendQueue.shift();
  outstandingSendId = sendId;
  const entry = pendingPieces.find((piece) => piece.sendId === sendId);
  // Stamp baseRevision NOW, not when this op was queued. A fast typist can
  // queue several keystrokes while the first is still outstanding; by the
  // time THIS one is actually sent, knownRevision may have already advanced
  // (because an earlier one of OUR OWN ops just got acked). Sending a stale,
  // queue-time baseRevision made the server re-apply a position shift that
  // was already baked into this op's position locally -- double-shifting it
  // out of bounds, throwing inside an unhandled async callback, silently
  // freezing the entire one-outstanding-op pipeline forever. Real bug, found
  // by fast manual typing; no existing test exercised "one client queues
  // multiple of its OWN ops before the first acks."
  const toSend = { ...entry.operation, baseRevision: knownRevision };
  socket.send(JSON.stringify(toSend));
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

// CP 4.3: ephemeral presence, keyed by userId -- rebuilt from scratch on
// every connect via "presence-snapshot", never persisted.
// userId -> {userId, username, color, cursorPosition}.
const presenceEl = document.getElementById("presence-list");
const mirrorEl = document.getElementById("cursor-mirror");
const overlayEl = document.getElementById("cursor-overlay");
const presentUsers = new Map();

function renderPresence() {
  presenceEl.innerHTML = "";
  for (const user of presentUsers.values()) {
    const chip = document.createElement("span");
    chip.title = user.username;
    chip.textContent = user.username.slice(0, 1).toUpperCase();
    chip.className = "flex h-7 w-7 items-center justify-center rounded-full text-xs font-semibold text-white ring-2 ring-white";
    chip.style.background = user.color;
    presenceEl.append(chip);
  }
}

// CP 4.4: re-draws every remote caret against the textarea's CURRENT text.
// Remote cursorPosition values are raw, unadjusted character offsets as last
// reported by each user -- not transformed against concurrent edits, so
// there can be a brief, self-correcting visual lag right after a concurrent
// edit until that user's next natural cursor move. Accepted simplification,
// not a defect, for this "deliberately plain" project.
function redrawCursors() {
  renderCursors(textareaEl, mirrorEl, overlayEl, [...presentUsers.values()]);
}

const socket = connectToDocument(documentId, (message) => {
  if (message.type === "presence-snapshot") {
    presentUsers.clear();
    for (const user of message.users) {
      presentUsers.set(user.userId, user);
    }
    renderPresence();
    redrawCursors();
  } else if (message.type === "presence-join") {
    presentUsers.set(message.userId, {
      userId: message.userId, username: message.username, color: message.color, cursorPosition: null,
    });
    renderPresence();
  } else if (message.type === "presence-leave") {
    presentUsers.delete(message.userId);
    renderPresence();
    redrawCursors();
  } else if (message.type === "cursor-update") {
    const user = presentUsers.get(message.userId);
    if (user) {
      user.cursorPosition = message.position;
      redrawCursors();
    }
  } else if (message.type === "history") {
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
      redrawCursors();
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
    redrawCursors();
  }
});

// Announce presence once the socket is actually open -- it's a fresh
// connection created above, so the handshake may not have completed yet.
socket.addEventListener("open", () => {
  socket.send(JSON.stringify({
    type: "presence",
    userId: currentUser.userId,
    username: currentUser.username,
    color: currentUser.color,
  }));
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
  redrawCursors(); // our own edit reshapes the text remote carets are positioned against
});

// CP 4.4: tell everyone else where our caret is whenever it could have moved
// -- typing, clicking, or using arrow keys/selecting. No debouncing: this is
// a small, infrequent-enough message for this project's scale.
function sendCursorPosition() {
  if (socket.readyState !== WebSocket.OPEN) {
    return;
  }
  socket.send(JSON.stringify({ type: "updateCursor", position: textareaEl.selectionStart }));
}

textareaEl.addEventListener("click", sendCursorPosition);
textareaEl.addEventListener("keyup", sendCursorPosition);
textareaEl.addEventListener("select", sendCursorPosition);
