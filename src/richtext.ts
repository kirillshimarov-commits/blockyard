/**
 * Mod descriptions: a small Markdown renderer (Modrinth) and an HTML cleaner (both sources).
 * Only plain formatting survives cleaning: no scripts, styles, frames or event handlers, and
 * links and images must be http(s).
 */

function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/** Bold, italics, code, links and images inside one block. Raw HTML is left for the cleaner. */
function inline(text: string): string {
  const codes: string[] = [];
  let s = text.replace(/`([^`]+)`/g, (_, code: string) => {
    codes.push(`<code>${escapeHtml(code)}</code>`);
    return `\u0000${codes.length - 1}\u0000`;
  });
  s = s.replace(/!\[([^\]]*)\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g, (_, alt: string, src: string) => {
    return `<img src="${escapeHtml(src)}" alt="${escapeHtml(alt)}">`;
  });
  s = s.replace(/\[([^\]]*(?:<img[^>]*>)?[^\]]*)\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g, (_, label: string, href: string) => {
    return `<a href="${escapeHtml(href)}">${label}</a>`;
  });
  s = s.replace(/<(https?:\/\/[^>\s]+)>/g, (_, url: string) => `<a href="${escapeHtml(url)}">${escapeHtml(url)}</a>`);
  s = s.replace(/(\*\*|__)(?=\S)([\s\S]*?\S)\1/g, "<strong>$2</strong>");
  s = s.replace(/(^|[^\w*])\*(?=\S)([^*]*?\S)\*(?!\w)/g, "$1<em>$2</em>");
  s = s.replace(/(^|[^\w])_(?=\S)([^_]*?\S)_(?!\w)/g, "$1<em>$2</em>");
  s = s.replace(/~~(?=\S)([\s\S]*?\S)~~/g, "<del>$1</del>");
  return s.replace(/\u0000(\d+)\u0000/g, (_, i: string) => codes[Number(i)]);
}

function tableRow(line: string, cell: "td" | "th"): string {
  const cells = line.trim().replace(/^\||\|$/g, "").split("|");
  return `<tr>${cells.map((c) => `<${cell}>${inline(c.trim())}</${cell}>`).join("")}</tr>`;
}

/** Markdown to HTML, enough for mod pages: headings, paragraphs, lists, quotes, code, tables. */
export function markdownToHtml(md: string): string {
  const lines = md.replace(/\r\n?/g, "\n").split("\n");
  const out: string[] = [];
  let paragraph: string[] = [];
  const flush = () => {
    if (paragraph.length) out.push(`<p>${inline(paragraph.join("\n")).replace(/ {2,}\n/g, "<br>").replace(/\n/g, " ")}</p>`);
    paragraph = [];
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const trimmed = line.trim();

    if (/^(```|~~~)/.test(trimmed)) {
      flush();
      const fence = trimmed.slice(0, 3);
      const code: string[] = [];
      while (++i < lines.length && !lines[i].trim().startsWith(fence)) code.push(lines[i]);
      out.push(`<pre><code>${escapeHtml(code.join("\n"))}</code></pre>`);
      continue;
    }
    if (!trimmed) {
      flush();
      continue;
    }
    const heading = /^(#{1,6})\s+(.*?)\s*#*$/.exec(trimmed);
    if (heading) {
      flush();
      const level = heading[1].length;
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`);
      continue;
    }
    if (/^(\*\s*\*\s*\*|-\s*-\s*-|_\s*_\s*_)[\s*_-]*$/.test(trimmed)) {
      flush();
      out.push("<hr>");
      continue;
    }
    if (trimmed.startsWith(">")) {
      flush();
      const quote: string[] = [];
      for (; i < lines.length && lines[i].trim().startsWith(">"); i++) quote.push(lines[i].trim().replace(/^>\s?/, ""));
      i--;
      out.push(`<blockquote>${markdownToHtml(quote.join("\n"))}</blockquote>`);
      continue;
    }
    const listItem = /^\s*([-*+]|\d+[.)])\s+/;
    if (listItem.test(line)) {
      flush();
      const ordered = /^\s*\d/.test(line);
      const items: string[] = [];
      for (; i < lines.length && (listItem.test(lines[i]) || (lines[i].startsWith("  ") && lines[i].trim())); i++) {
        if (listItem.test(lines[i])) items.push(lines[i].replace(listItem, ""));
        else items[items.length - 1] += ` ${lines[i].trim()}`;
      }
      i--;
      const tag = ordered ? "ol" : "ul";
      out.push(`<${tag}>${items.map((it) => `<li>${inline(it)}</li>`).join("")}</${tag}>`);
      continue;
    }
    if (trimmed.startsWith("|") && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) {
      flush();
      const rows = [tableRow(line, "th")];
      for (i += 2; i < lines.length && lines[i].trim().startsWith("|"); i++) rows.push(tableRow(lines[i], "td"));
      i--;
      out.push(`<table>${rows.join("")}</table>`);
      continue;
    }
    // Block-level HTML (centered images, details…) goes through as it is.
    if (/^<\/?(div|center|p|details|summary|table|h\d|img|a|br|hr|iframe|ul|ol|li|blockquote)\b/i.test(trimmed)) {
      flush();
      out.push(inline(line));
      continue;
    }
    paragraph.push(line);
  }
  flush();
  return out.join("\n");
}

const ALLOWED = new Set([
  "a", "b", "blockquote", "br", "code", "dd", "del", "details", "div", "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5",
  "h6", "hr", "i", "img", "kbd", "li", "ol", "p", "pre", "s", "small", "span", "strong", "sub", "summary", "sup",
  "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul",
]);
/** Removed together with everything inside. */
const DROPPED = new Set([
  "script", "style", "object", "embed", "form", "input", "button", "textarea", "select", "svg", "math", "link", "meta",
  "noscript", "template", "head", "title", "audio", "video", "canvas",
]);
const ATTRIBUTES: Record<string, string[]> = {
  a: ["href", "title"],
  img: ["src", "alt", "title", "width", "height"],
  td: ["colspan", "rowspan"],
  th: ["colspan", "rowspan"],
};

const isWebUrl = (url: string) => /^https?:\/\//i.test(url.trim());

/** Keeps only safe formatting. Embedded videos become links labelled `videoLabel`. */
export function sanitizeHtml(html: string, videoLabel: string): string {
  const doc = new DOMParser().parseFromString(`<body>${html}</body>`, "text/html");

  const clean = (node: Element) => {
    for (const child of [...node.children]) {
      const tag = child.tagName.toLowerCase();
      if (tag === "iframe") {
        const src = child.getAttribute("src") ?? "";
        if (isWebUrl(src)) {
          const link = doc.createElement("a");
          link.setAttribute("href", src);
          link.textContent = `▶ ${videoLabel}`;
          child.replaceWith(link);
        } else {
          child.remove();
        }
        continue;
      }
      if (DROPPED.has(tag)) {
        child.remove();
        continue;
      }
      clean(child);
      if (!ALLOWED.has(tag)) {
        // Unknown wrappers (center, font…) give way to their content.
        child.replaceWith(...child.childNodes);
        continue;
      }
      const keep = ATTRIBUTES[tag] ?? [];
      for (const attr of [...child.attributes]) {
        if (!keep.includes(attr.name)) child.removeAttribute(attr.name);
      }
      if (tag === "a" && !isWebUrl(child.getAttribute("href") ?? "")) child.removeAttribute("href");
      if (tag === "img" && !isWebUrl(child.getAttribute("src") ?? "")) child.remove();
    }
  };
  clean(doc.body);
  return doc.body.innerHTML;
}
