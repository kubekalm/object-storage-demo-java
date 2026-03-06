const statusEl = document.getElementById("status");
const tableBody = document.getElementById("documents");
const form = document.getElementById("upload-form");
const fileInput = document.getElementById("file-input");
const fileLabel = document.getElementById("file-label");
const uploadButton = document.getElementById("upload-button");
const rowTemplate = document.getElementById("document-row-template");

const state = {
  items: [],
  busy: false,
};

const formatBytes = (bytes) => {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return "0 B";
  }
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i += 1;
  }
  const rounded = i === 0 ? value.toFixed(0) : value.toFixed(1);
  return `${rounded} ${units[i]}`;
};

const formatDate = (iso) => {
  if (!iso) {
    return "-";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return "-";
  }
  return new Intl.DateTimeFormat(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
};

const setStatus = (text, tone = "neutral") => {
  statusEl.textContent = text || "";
  statusEl.dataset.tone = tone;
};

const setBusy = (busy) => {
  state.busy = busy;
  uploadButton.disabled = busy;
};

const parseJson = async (res) => {
  const text = await res.text();
  if (!text) {
    return {};
  }
  try {
    return JSON.parse(text);
  } catch {
    return { message: text };
  }
};

const request = async (path, options = {}) => {
  const res = await fetch(path, options);
  const payload = await parseJson(res);
  if (!res.ok) {
    const message = payload.message || payload.error || `Request failed (${res.status})`;
    throw new Error(message);
  }
  return payload;
};

const renderEmptyState = (message) => {
  tableBody.innerHTML = "";
  const row = document.createElement("tr");
  row.className = "empty-row";
  const cell = document.createElement("td");
  cell.colSpan = 4;
  cell.textContent = message;
  row.appendChild(cell);
  tableBody.appendChild(row);
};

const onDownload = async (item) => {
  try {
    setStatus(`Downloading "${item.name}"...`);
    const res = await fetch(`/api/documents/${encodeURIComponent(item.id)}/download`);
    if (!res.ok) {
      const payload = await parseJson(res);
      throw new Error(payload.message || "Download failed");
    }
    const blob = await res.blob();
    const href = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = href;
    anchor.download = item.name || "document.bin";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(href);
    setStatus(`Downloaded "${item.name}".`, "success");
  } catch (error) {
    setStatus(error.message, "danger");
  }
};

const onDelete = async (item) => {
  try {
    setStatus(`Deleting "${item.name}"...`);
    await request(`/api/documents/${encodeURIComponent(item.id)}`, { method: "DELETE" });
    await loadDocuments();
    setStatus(`Deleted "${item.name}".`, "success");
  } catch (error) {
    setStatus(error.message, "danger");
  }
};

const renderDocuments = () => {
  if (!Array.isArray(state.items) || state.items.length === 0) {
    renderEmptyState("No documents yet. Upload your first file.");
    return;
  }

  tableBody.innerHTML = "";
  for (const item of state.items) {
    const row = rowTemplate.content.firstElementChild.cloneNode(true);
    row.dataset.documentId = item.id;
    row.querySelector(".doc-name").textContent = item.name || "Untitled";
    row.querySelector(".doc-size").textContent = formatBytes(item.size);
    row.querySelector(".doc-date").textContent = formatDate(item.uploadedAt);

    row.querySelector(".download").addEventListener("click", () => onDownload(item));
    row.querySelector(".delete").addEventListener("click", () => onDelete(item));
    tableBody.appendChild(row);
  }
};

const loadDocuments = async () => {
  setStatus("Loading documents...");
  const payload = await request("/api/documents");
  state.items = Array.isArray(payload.items) ? payload.items : [];
  renderDocuments();
  setStatus("Ready");
};

fileInput.addEventListener("change", () => {
  if (fileInput.files && fileInput.files[0]) {
    fileLabel.textContent = fileInput.files[0].name;
    return;
  }
  fileLabel.textContent = "Choose a document (max 5MB)";
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const file = fileInput.files && fileInput.files[0];
  if (!file) {
    setStatus("Please choose a file first.", "danger");
    return;
  }

  setBusy(true);
  try {
    setStatus(`Uploading "${file.name}"...`);
    const formData = new FormData();
    formData.append("file", file);
    await request("/api/documents", { method: "POST", body: formData });
    fileInput.value = "";
    fileLabel.textContent = "Choose a document (max 5MB)";
    await loadDocuments();
    setStatus(`Uploaded "${file.name}".`, "success");
  } catch (error) {
    setStatus(error.message, "danger");
  } finally {
    setBusy(false);
  }
});

loadDocuments().catch((error) => {
  setStatus(error.message, "danger");
  renderEmptyState("Unable to load documents right now.");
});
