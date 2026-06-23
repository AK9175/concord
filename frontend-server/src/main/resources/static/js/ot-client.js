// Client-side mirror of ot-core's OperationApplier.apply(). For CP 3.4 this
// only needs to replay already-committed history (no transform involved --
// these ops are final). CP 3.5/3.6 add the optimistic-apply and
// transform-against-pending logic on top of this same primitive.

export function applyOperation(text, operation) {
  if (operation.type === "insert") {
    return text.slice(0, operation.position) + operation.text + text.slice(operation.position);
  }
  if (operation.type === "delete") {
    return text.slice(0, operation.position) + text.slice(operation.position + operation.length);
  }
  throw new Error("unknown operation type: " + operation.type);
}

/** Replays a list of CommittedOperation (as sent by connection-tier) from an empty document. */
export function applyHistory(committedOps) {
  let text = "";
  for (const committed of committedOps) {
    text = applyOperation(text, committed.operation);
  }
  return text;
}

/**
 * Applies every operation in ops to text, highest-position-first -- same
 * rule as ot-core's OperationApplier.applyAll() and for the same reason: a
 * single transform() call can return pieces that share one coordinate frame
 * rather than being chained to each other, so applying the higher-positioned
 * one first never invalidates the lower one's index.
 */
export function applyAll(text, ops) {
  const sorted = [...ops].sort((a, b) => b.position - a.position);
  let result = text;
  for (const op of sorted) {
    result = applyOperation(result, op);
  }
  return result;
}

// ---- transform(): direct port of ot-core's OperationTransformer ----
//
// transform(toTransform, appliedFirst) returns the array of operations that,
// applied (via applyAll) to a document appliedFirst was already applied to,
// produce the correct result. Mirrors the Java version field-for-field,
// including the same tradeoffs already proven correct there: the userId
// tie-break for same-position inserts, the insert-survives-delete policy,
// and the delete-split case for a surviving insert.

export function transform(toTransform, appliedFirst) {
  if (toTransform.type === "insert" && appliedFirst.type === "insert") {
    return [transformInsertInsert(toTransform, appliedFirst)];
  }
  if (toTransform.type === "insert" && appliedFirst.type === "delete") {
    return [transformInsertDelete(toTransform, appliedFirst)];
  }
  if (toTransform.type === "delete" && appliedFirst.type === "insert") {
    return transformDeleteInsert(toTransform, appliedFirst);
  }
  return [transformDeleteDelete(toTransform, appliedFirst)];
}

function transformInsertInsert(toTransform, appliedFirst) {
  if (toTransform.position < appliedFirst.position) {
    return toTransform;
  }
  if (toTransform.position > appliedFirst.position) {
    return shiftedInsert(toTransform, appliedFirst.text.length);
  }
  if (toTransform.userId < appliedFirst.userId) {
    return toTransform;
  }
  return shiftedInsert(toTransform, appliedFirst.text.length);
}

function transformInsertDelete(toTransform, appliedFirst) {
  const insertPos = toTransform.position;
  const delStart = appliedFirst.position;
  const delEnd = delStart + appliedFirst.length;

  if (insertPos <= delStart) {
    return toTransform;
  }
  if (insertPos >= delEnd) {
    return shiftedInsert(toTransform, -appliedFirst.length);
  }
  return { ...toTransform, position: delStart };
}

function transformDeleteInsert(toTransform, appliedFirst) {
  const insertPos = appliedFirst.position;
  const delStart = toTransform.position;
  const delEnd = delStart + toTransform.length;
  const insertLen = appliedFirst.text.length;

  if (insertPos <= delStart) {
    return [{ ...toTransform, position: delStart + insertLen }];
  }
  if (insertPos >= delEnd) {
    return [toTransform];
  }

  const leftLength = insertPos - delStart;
  const left = { ...toTransform, position: delStart, length: leftLength };
  const rightPosition = insertPos + insertLen;
  const rightLength = delEnd - insertPos;
  const right = { ...toTransform, position: rightPosition, length: rightLength };
  return [left, right];
}

function transformDeleteDelete(toTransform, appliedFirst) {
  const start1 = toTransform.position;
  const end1 = start1 + toTransform.length;
  const start2 = appliedFirst.position;
  const end2 = start2 + appliedFirst.length;

  if (end1 <= start2) {
    return toTransform;
  }
  if (start1 >= end2) {
    return { ...toTransform, position: start1 - appliedFirst.length };
  }

  const overlapStart = Math.max(start1, start2);
  const overlapEnd = Math.min(end1, end2);
  const overlapLength = Math.max(0, overlapEnd - overlapStart);
  const newStart = Math.min(start1, start2);
  const newLength = toTransform.length - overlapLength;
  return { ...toTransform, position: newStart, length: newLength };
}

function shiftedInsert(op, delta) {
  return { ...op, position: op.position + delta };
}

/**
 * Two-sided client reconciliation (project invariant #4). pendingPieces is
 * this client's own sent-but-not-yet-acked ops (in send order); incomingOps
 * is a newly-arrived broadcast (in server apply order). Returns:
 *  - toApply: the incoming ops adjusted for this client's pending edits --
 *    safe to applyAll() directly to the textarea's CURRENT value.
 *  - newPendingPieces: this client's pending ops adjusted for the incoming
 *    change, so they stay correctly positioned for whenever they're acked
 *    or another broadcast arrives. Each result piece keeps its parent's
 *    sendId so a later ack (which removes by sendId) still works even if
 *    reconciliation split a pending op into more pieces.
 *
 * The two loops mirror DocumentCommitter's gap-transform pattern exactly,
 * just run twice with the roles swapped: once treating pendingPieces as the
 * "history" to transform incoming against (to find what to apply now), once
 * treating incomingOps as the "history" to transform each pending piece
 * against (to keep pending correctly positioned going forward). The two
 * pending ops never need to transform against each other -- they're
 * sequential edits from this same client, not concurrent with each other.
 */
export function reconcileIncoming(pendingPieces, incomingOps) {
  let toApply = incomingOps;
  for (const { operation } of pendingPieces) {
    toApply = toApply.flatMap((piece) => transform(piece, operation));
  }

  const newPendingPieces = [];
  for (const { sendId, operation } of pendingPieces) {
    let transformed = [operation];
    for (const incoming of incomingOps) {
      transformed = transformed.flatMap((piece) => transform(piece, incoming));
    }
    for (const piece of transformed) {
      newPendingPieces.push({ sendId, operation: piece });
    }
  }

  return { toApply, newPendingPieces };
}

/**
 * Diffs oldText -> newText into the (at most 2) ops needed to turn one into
 * the other: a delete of the changed middle (if any), then an insert of the
 * new middle (if any). This is the standard common-prefix/common-suffix diff
 * used for textarea-driven editors -- good enough for typing, pasting, and
 * select-and-replace. It doesn't search for a cleverer/minimal diff beyond
 * the common prefix and suffix, which is plenty for one person's own edits.
 */
export function diffToOps(oldText, newText) {
  let prefix = 0;
  while (prefix < oldText.length && prefix < newText.length && oldText[prefix] === newText[prefix]) {
    prefix++;
  }

  const maxSuffix = Math.min(oldText.length, newText.length) - prefix;
  let suffix = 0;
  while (suffix < maxSuffix && oldText[oldText.length - 1 - suffix] === newText[newText.length - 1 - suffix]) {
    suffix++;
  }

  const deletedLength = oldText.length - prefix - suffix;
  const insertedText = newText.slice(prefix, newText.length - suffix);

  const ops = [];
  if (deletedLength > 0) {
    ops.push({ kind: "delete", position: prefix, length: deletedLength });
  }
  if (insertedText.length > 0) {
    ops.push({ kind: "insert", position: prefix, text: insertedText });
  }
  return ops;
}
