import { listDocuments, createDocument, renameDocument, deleteDocument } from "./api.js";

const docListEl = document.getElementById("doc-list");
const emptyStateEl = document.getElementById("empty-state");
const errorBannerEl = document.getElementById("error-banner");

const modalEl = document.getElementById("doc-modal");
const modalTitleEl = document.getElementById("doc-modal-title");
const modalFormEl = document.getElementById("doc-modal-form");
const modalInputEl = document.getElementById("doc-modal-input");
const modalSubmitEl = document.getElementById("doc-modal-submit");
const modalCancelEl = document.getElementById("doc-modal-cancel");

// Either { mode: "create" } or { mode: "rename", documentId }.
let modalContext = null;

function showError(message) {
  errorBannerEl.textContent = message;
  errorBannerEl.classList.remove("hidden");
}

function clearError() {
  errorBannerEl.classList.add("hidden");
}

function openModal(context) {
  modalContext = context;
  if (context.mode === "create") {
    modalTitleEl.textContent = "New document";
    modalSubmitEl.textContent = "Create";
    modalInputEl.value = "";
  } else {
    modalTitleEl.textContent = "Rename document";
    modalSubmitEl.textContent = "Save";
    modalInputEl.value = context.currentTitle;
  }
  modalEl.classList.remove("hidden");
  modalEl.classList.add("flex");
  modalInputEl.focus();
  modalInputEl.select();
}

function closeModal() {
  modalEl.classList.add("hidden");
  modalEl.classList.remove("flex");
  modalContext = null;
}

function renderDocuments(documents) {
  docListEl.innerHTML = "";
  emptyStateEl.classList.toggle("hidden", documents.length > 0);

  for (const doc of documents) {
    const row = document.createElement("div");
    row.className = "flex items-center justify-between px-4 py-3";

    const link = document.createElement("a");
    link.href = `/${doc.id}`;
    link.textContent = doc.title || "Untitled document";
    link.className = "text-sm font-medium text-slate-800 hover:text-indigo-600";

    const renameButton = document.createElement("button");
    renameButton.textContent = "Rename";
    renameButton.className = "text-sm text-slate-400 hover:text-indigo-600";
    renameButton.addEventListener("click", () =>
      openModal({ mode: "rename", documentId: doc.id, currentTitle: doc.title })
    );

    const deleteButton = document.createElement("button");
    deleteButton.textContent = "Delete";
    deleteButton.className = "ml-3 text-sm text-slate-400 hover:text-red-600";
    deleteButton.addEventListener("click", () => handleDelete(doc));

    const actions = document.createElement("div");
    actions.append(renameButton, deleteButton);

    row.append(link, actions);
    docListEl.append(row);
  }
}

async function handleDelete(doc) {
  const title = doc.title || "Untitled document";
  if (!window.confirm(`Delete "${title}"? This permanently removes its content and roster, and disconnects anyone currently editing it.`)) {
    return;
  }
  try {
    await deleteDocument(doc.id);
    await refresh();
  } catch (err) {
    showError("Couldn't delete: " + err.message);
  }
}

async function refresh() {
  try {
    const documents = await listDocuments();
    clearError();
    renderDocuments(documents);
  } catch (err) {
    showError("Couldn't load documents: " + err.message);
  }
}

document.getElementById("new-doc-button").addEventListener("click", () => openModal({ mode: "create" }));
modalCancelEl.addEventListener("click", closeModal);
modalEl.addEventListener("click", (event) => {
  if (event.target === modalEl) closeModal();
});

modalFormEl.addEventListener("submit", async (event) => {
  event.preventDefault();
  const title = modalInputEl.value.trim() || "Untitled document";

  try {
    if (modalContext.mode === "create") {
      const created = await createDocument(title);
      window.location.href = `/${created.id}`;
      return;
    }
    await renameDocument(modalContext.documentId, title);
    closeModal();
    await refresh();
  } catch (err) {
    showError("Couldn't save: " + err.message);
  }
});

refresh();
