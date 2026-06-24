// Renders docs/architecture.md client-side -- no build step, matching this
// project's Tailwind-via-CDN precedent rather than introducing a bundler
// just to render one markdown page. marked parses the markdown; mermaid
// renders the ```mermaid fenced code blocks marked's custom renderer below
// leaves as placeholder divs.
import { marked } from "https://cdn.jsdelivr.net/npm/marked@18/lib/marked.esm.js";
import mermaid from "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs";

mermaid.initialize({ startOnLoad: false, theme: "neutral" });

marked.use({
  renderer: {
    code(token) {
      if (token.lang === "mermaid") {
        return `<div class="mermaid-block" data-def="${encodeURIComponent(token.text)}"></div>`;
      }
      const escaped = token.text
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;");
      return `<pre><code>${escaped}</code></pre>`;
    },
  },
});

async function renderMermaidBlocks(container) {
  const blocks = container.querySelectorAll(".mermaid-block");
  for (const block of blocks) {
    const definition = decodeURIComponent(block.dataset.def ?? "");
    if (!definition) continue;
    const id = `mermaid-${Math.random().toString(36).slice(2)}`;
    try {
      const { svg } = await mermaid.render(id, definition);
      block.innerHTML = svg;
    } catch (err) {
      block.innerHTML = `<pre class="text-red-600">${String(err)}</pre>`;
    }
  }
}

async function main() {
  const loadingEl = document.getElementById("docs-loading");
  const contentEl = document.getElementById("docs-content");

  try {
    const response = await fetch("/docs/architecture.md");
    if (!response.ok) throw new Error(`failed to load docs: ${response.status}`);
    const markdown = await response.text();

    contentEl.innerHTML = marked.parse(markdown);
    await renderMermaidBlocks(contentEl);

    loadingEl.classList.add("hidden");
    contentEl.classList.remove("hidden");
  } catch (err) {
    loadingEl.textContent = "Couldn't load documentation: " + err.message;
  }
}

main();
