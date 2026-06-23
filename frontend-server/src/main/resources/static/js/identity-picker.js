import { listUsers, addUser } from "./api.js";

const DEFAULT_COLORS = ["#6366f1", "#ef4444", "#10b981", "#f59e0b", "#ec4899", "#06b6d4"];

function randomDefaultColor() {
  return DEFAULT_COLORS[Math.floor(Math.random() * DEFAULT_COLORS.length)];
}

function renderUserList(listEl, users, onPick) {
  listEl.innerHTML = "";
  if (users.length === 0) {
    const empty = document.createElement("p");
    empty.textContent = "No one has joined this document yet.";
    empty.className = "text-sm text-slate-500";
    listEl.append(empty);
    return;
  }
  for (const user of users) {
    const button = document.createElement("button");
    button.type = "button";
    button.className =
      "flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm hover:bg-slate-100";
    button.innerHTML = `
      <span class="h-3 w-3 flex-none rounded-full" style="background:${user.color}"></span>
      <span class="font-medium text-slate-800">${user.username}</span>
    `;
    button.addEventListener("click", () => onPick(user));
    listEl.append(button);
  }
}

/**
 * Shows the roster picker for documentId and resolves with the chosen user
 * once someone picks an existing identity or adds a new one. Per the
 * project's identity model, the choice is in-memory only for this page
 * session -- it is never written to localStorage, so reloading always shows
 * the picker again with nothing pre-selected.
 */
export function pickIdentity(documentId) {
  const overlayEl = document.getElementById("picker-overlay");
  const listEl = document.getElementById("picker-user-list");
  const formEl = document.getElementById("picker-add-form");
  const usernameInput = document.getElementById("picker-username-input");
  const colorInput = document.getElementById("picker-color-input");
  const errorEl = document.getElementById("picker-error");

  colorInput.value = randomDefaultColor();

  return new Promise((resolve) => {
    function settle(user) {
      overlayEl.classList.add("hidden");
      resolve(user);
    }

    function showError(message) {
      errorEl.textContent = message;
      errorEl.classList.remove("hidden");
    }

    listUsers(documentId)
      .then((users) => renderUserList(listEl, users, settle))
      .catch((err) => showError("Couldn't load this document's participants: " + err.message));

    formEl.addEventListener("submit", async (event) => {
      event.preventDefault();
      const username = usernameInput.value.trim();
      if (!username) {
        return;
      }
      try {
        const created = await addUser(documentId, username, colorInput.value);
        settle(created);
      } catch (err) {
        showError("Couldn't join: " + err.message);
      }
    });
  });
}
