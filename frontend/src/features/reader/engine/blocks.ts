/** Block extraction that mirrors the server-side text index (M03 XhtmlSanitizer + TextIndexer). */
const BLOCK_TAGS = new Set([
  'p', 'div', 'section', 'article', 'aside', 'header', 'footer', 'nav',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'blockquote', 'pre', 'li', 'dt', 'dd',
  'td', 'th', 'caption', 'figcaption',
]);

export interface BlockText { text: string; globalStart: number }

export interface BlockPosition { blockIndex: number; blockOffset: number }

export function collectBlocks(root: Element): BlockText[] {
  const blocks: BlockText[] = [];
  let current = '';
  let start = 0;
  let committed = 0;
  const flush = () => {
    if (current.length === 0) return;
    blocks.push({ text: current, globalStart: start });
    committed += current.length;
    current = '';
    start = committed;
  };
  const append = (value: string) => {
    if (current.length === 0) start = committed;
    current += value;
  };
  const walk = (node: Node) => {
    for (let child = node.firstChild; child !== null; child = child.nextSibling) {
      if (child.nodeType === Node.TEXT_NODE) { append(child.nodeValue ?? ''); continue; }
      if (child.nodeType !== Node.ELEMENT_NODE) continue;
      const element = child as Element;
      const name = element.localName.toLowerCase();
      if (BLOCK_TAGS.has(name)) { flush(); walk(element); flush(); }
      else if (name === 'br') append('\n');
      else walk(element);
    }
  };
  walk(root);
  flush();
  return blocks;
}

export function globalOffsetOf(root: Element, target: Node, offset: number): number | null {
  let global = 0;
  let found: number | null = null;
  const walk = (node: Node): void => {
    if (found !== null) return;
    for (let child = node.firstChild; child !== null; child = child.nextSibling) {
      if (found !== null) return;
      if (child === target && child.nodeType === Node.TEXT_NODE) { found = global + offset; return; }
      if (child.nodeType === Node.TEXT_NODE) { global += (child.nodeValue ?? '').length; continue; }
      if (child.nodeType !== Node.ELEMENT_NODE) continue;
      const element = child as Element;
      const name = element.localName.toLowerCase();
      if (BLOCK_TAGS.has(name)) { walk(element); }
      else if (name === 'br') global += 1;
      else walk(element);
    }
  };
  walk(root);
  return found;
}

export function locate(blocks: BlockText[], globalOffset: number): BlockPosition | null {
  for (let i = 0; i < blocks.length; i++) {
    const block = blocks[i]!;
    const end = block.globalStart + block.text.length;
    if (globalOffset >= block.globalStart && (globalOffset < end || (globalOffset === end && i === blocks.length - 1))) {
      return { blockIndex: i, blockOffset: globalOffset - block.globalStart };
    }
  }
  return null;
}

export function rangeForGlobal(root: Element, start: number, end: number): Range | null {
  const range = root.ownerDocument.createRange();
  let global = 0;
  let started = false;
  let finished = false;
  const walk = (node: Node): void => {
    for (let child = node.firstChild; child !== null && !finished; child = child.nextSibling) {
      if (child.nodeType === Node.TEXT_NODE) {
        const length = (child.nodeValue ?? '').length;
        if (!started && start >= global && start <= global + length) {
          range.setStart(child, start - global);
          started = true;
        }
        if (started && end >= global && end <= global + length) {
          range.setEnd(child, end - global);
          finished = true;
          return;
        }
        global += length;
        continue;
      }
      if (child.nodeType !== Node.ELEMENT_NODE) continue;
      const element = child as Element;
      const name = element.localName.toLowerCase();
      if (name === 'br') global += 1;
      else if (!started || !finished) walk(element);
    }
  };
  walk(root);
  return started && finished ? range : null;
}

export async function hashText(text: string): Promise<string> {
  const bytes = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest)).map(value => value.toString(16).padStart(2, '0')).join('');
}

export async function verifyBlocks(blocks: BlockText[], expected: { blockId: string; length: number; hash: string }[]): Promise<boolean> {
  if (blocks.length !== expected.length) return false;
  for (let i = 0; i < blocks.length; i++) {
    const block = blocks[i]!; const index = expected[i]!;
    if (block.text.length !== index.length) return false;
    if (await hashText(block.text) !== index.hash) return false;
  }
  return true;
}
