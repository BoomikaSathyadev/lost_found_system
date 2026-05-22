const views = {
  home: document.getElementById("view-home"),
  finder: document.getElementById("view-finder"),
  claimant: document.getElementById("view-claimant"),
};

const toastEl = document.getElementById("toast");
const itemsTbody = document.getElementById("items-tbody");
const itemsEmpty = document.getElementById("items-empty");
const searchResults = document.getElementById("search-results");

let toastTimer;

function showView(name) {
  Object.values(views).forEach((v) => v.classList.remove("active"));
  views[name].classList.add("active");
  if (name === "finder") loadItems();
}

function showToast(message, type = "success") {
  clearTimeout(toastTimer);
  toastEl.textContent = message;
  toastEl.className = `toast ${type}`;
  toastTimer = setTimeout(() => toastEl.classList.add("hidden"), 3500);
}

async function api(url, options = {}) {
  const res = await fetch(url, options);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.error || "Request failed");
  }
  return data;
}

function renderItems(items) {
  itemsTbody.innerHTML = "";
  if (!items.length) {
    itemsEmpty.classList.remove("hidden");
    return;
  }
  itemsEmpty.classList.add("hidden");
  items.forEach((item) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${item.id}</td>
      <td>${escapeHtml(item.name)}</td>
      <td>${escapeHtml(item.category)}</td>
      <td>${escapeHtml(item.location)}</td>
      <td>${escapeHtml(item.date)}</td>
      <td class="${item.claimed ? "status-claimed" : "status-open"}">
        ${item.claimed ? "Claimed" : "Unclaimed"}
      </td>`;
    itemsTbody.appendChild(tr);
  });
}

function escapeHtml(text) {
  const div = document.createElement("div");
  div.textContent = text;
  return div.innerHTML;
}

async function loadItems() {
  try {
    const items = await api("/api/items");
    renderItems(items);
  } catch (err) {
    showToast(err.message, "error");
  }
}

document.querySelectorAll("[data-view]").forEach((el) => {
  el.addEventListener("click", () => showView(el.dataset.view));
});

document.getElementById("refresh-items").addEventListener("click", loadItems);

document.getElementById("add-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = e.target;
  const body = new URLSearchParams(new FormData(form));
  try {
    const data = await api("/api/items", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    showToast(data.message);
    form.reset();
    loadItems();
  } catch (err) {
    showToast(err.message, "error");
  }
});

document.getElementById("search-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const name = new FormData(e.target).get("name").trim();
  searchResults.innerHTML = "<p class='muted'>Searching...</p>";
  try {
    const data = await api(`/api/items/search?name=${encodeURIComponent(name)}`);
    if (!data.items.length) {
      searchResults.innerHTML = `<p class="muted">Item '${escapeHtml(name)}' not found.</p>`;
      return;
    }
    searchResults.innerHTML = data.items
      .map(
        (item) => `
      <div class="result-item">
        <dl>
          <dt>ID</dt><dd>${item.id}</dd>
          <dt>Name</dt><dd>${escapeHtml(item.name)}</dd>
          <dt>Category</dt><dd>${escapeHtml(item.category)}</dd>
          <dt>Location</dt><dd>${escapeHtml(item.location)}</dd>
          <dt>Date</dt><dd>${escapeHtml(item.date)}</dd>
          <dt>Description</dt><dd>${escapeHtml(item.description)}</dd>
          <dt>Status</dt><dd>${item.claimed ? "Claimed" : "Unclaimed"}</dd>
        </dl>
      </div>`
      )
      .join("");
  } catch (err) {
    searchResults.innerHTML = "";
    showToast(err.message, "error");
  }
});

document.getElementById("claim-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const body = new URLSearchParams(new FormData(e.target));
  try {
    const data = await api("/api/items/claim", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    showToast(data.message, data.success ? "success" : "error");
    if (data.success) e.target.reset();
  } catch (err) {
    showToast(err.message, "error");
  }
});
