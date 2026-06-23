// Renders other users' carets over a plain <textarea>, which has no native
// way to draw foreground decorations inside its own text layout. The trick:
// mirrorEl is an invisible (visibility:hidden, still laid out) clone of
// textareaEl's exact box model (font, padding, border, width); inserting a
// zero-width marker span at a character offset and reading its
// getBoundingClientRect() tells us the pixel position that offset would
// have inside the real textarea. overlayEl then draws a colored caret +
// name tag at that position -- visible, but pointer-events:none so it never
// intercepts clicks/typing meant for the textarea underneath.

function escapeHtml(text) {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

export function renderCursors(textareaEl, mirrorEl, overlayEl, users) {
  const text = textareaEl.value;
  const withPosition = users.filter((user) => typeof user.cursorPosition === "number");
  const sorted = [...withPosition].sort((a, b) => a.cursorPosition - b.cursorPosition);

  let html = "";
  let last = 0;
  for (const user of sorted) {
    const position = Math.max(0, Math.min(text.length, user.cursorPosition));
    html += escapeHtml(text.slice(last, position));
    html += `<span class="caret-marker" data-user-id="${user.userId}">​</span>`;
    last = position;
  }
  html += escapeHtml(text.slice(last)) || "​";
  mirrorEl.innerHTML = html;

  // Keep the mirror's visible window in sync with the textarea's scroll
  // position, so measured positions correspond to what's actually on screen.
  mirrorEl.scrollTop = textareaEl.scrollTop;
  mirrorEl.scrollLeft = textareaEl.scrollLeft;

  overlayEl.innerHTML = "";
  const containerRect = textareaEl.getBoundingClientRect();

  for (const user of sorted) {
    const marker = mirrorEl.querySelector(`.caret-marker[data-user-id="${user.userId}"]`);
    if (!marker) {
      continue;
    }
    const markerRect = marker.getBoundingClientRect();
    const left = markerRect.left - containerRect.left;
    const top = markerRect.top - containerRect.top;
    if (top < 0 || top > containerRect.height) {
      continue; // scrolled out of the visible area
    }

    const caret = document.createElement("div");
    caret.className = "absolute w-0.5";
    caret.style.background = user.color;
    caret.style.height = "1.2em";
    caret.style.left = `${left}px`;
    caret.style.top = `${top}px`;

    const label = document.createElement("span");
    label.textContent = user.username;
    label.className = "absolute -top-4 left-0 whitespace-nowrap rounded px-1 text-[10px] font-medium text-white";
    label.style.background = user.color;
    caret.append(label);

    overlayEl.append(caret);
  }
}
