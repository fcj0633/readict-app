export interface AnchorRect {
  left: number; top: number; right: number; bottom: number; width: number; height: number;
}

export interface Size { width: number; height: number }

export interface Placement {
  left: number;
  top: number;
  placement: 'right' | 'left';
  maxHeight: number;
}

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));

/**
 * Places the dictionary card beside the selected word. The top-left is fixed for the lifetime
 * of one lookup; expansion only grows a bounded internal scroll area.
 */
export function placeCard(anchor: AnchorRect, card: Size, viewport: Size, gap = 14, margin = 16,
                          topMargin = 72): Placement {
  const room = Math.max(1, viewport.height - topMargin - margin);
  const cap = Math.min(Math.floor(viewport.height * 0.75), room);
  const reserve = Math.min(cap, Math.max(Math.min(card.height, cap), 300));
  const rightSpace = viewport.width - margin - (anchor.right + gap);
  const leftSpace = anchor.left - gap - margin;
  const fitsRight = rightSpace >= Math.min(card.width, viewport.width - margin * 2);
  const fitsLeft = leftSpace >= Math.min(card.width, viewport.width - margin * 2);
  const placement: 'right' | 'left' = fitsRight || (!fitsLeft && rightSpace >= leftSpace) ? 'right' : 'left';
  const rawLeft = placement === 'right' ? anchor.right + gap : anchor.left - gap - card.width;
  const left = clamp(rawLeft, margin, Math.max(margin, viewport.width - margin - card.width));
  const top = clamp(anchor.top - 8, topMargin,
    Math.max(topMargin, viewport.height - margin - reserve));
  const maxHeight = Math.min(cap, Math.max(1, viewport.height - margin - top));
  return { left, top, placement, maxHeight };
}

/** Finds the first visible occurrence of a word inside the iframe document without mutating it. */
export function findWordRect(document_: Document, word: string): DOMRect | null {
  const target = word.toLowerCase();
  const walker = document_.createTreeWalker(document_.body, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    const text = node.nodeValue ?? '';
    const lower = text.toLowerCase();
    let from = 0;
    while (from < lower.length) {
      const at = lower.indexOf(target, from);
      if (at < 0) break;
      const before = at === 0 ? '' : lower[at - 1] ?? '';
      const after = lower[at + target.length] ?? '';
      if (!/[a-z]/.test(before) && !/[a-z]/.test(after)) {
        const range = document_.createRange();
        range.setStart(node, at);
        range.setEnd(node, at + target.length);
        const rect = range.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0) return rect;
      }
      from = at + target.length;
    }
  }
  return null;
}
