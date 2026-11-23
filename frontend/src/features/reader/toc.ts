import type { ManifestTocNode } from './engine/types';

export function normalizeHref(href: string): string {
  return (href.split('#')[0] ?? '').replace(/^\.\//, '');
}

/** True when two manifest hrefs point at the same chapter, with or without an anchor/fragment. */
export function sameChapter(left: string, right: string): boolean {
  const a = normalizeHref(left);
  const b = normalizeHref(right);
  if (!a || !b) return false;
  return a === b || a.endsWith(`/${b}`) || b.endsWith(`/${a}`);
}

export function findTocLabel(nodes: ManifestTocNode[], href: string): string | undefined {
  for (const node of nodes) {
    const child = findTocLabel(node.children, href);
    if (child) return child;
    if (sameChapter(node.href, href)) return node.label;
  }
  return undefined;
}

export function countToc(nodes: ManifestTocNode[]): number {
  return nodes.reduce((total, node) => total + 1 + countToc(node.children), 0);
}

const GROUP_LABEL = /^(part|book|volume|section|act)\b/i;

/**
 * M03's manifest flattens the nav/NCX tree. For display only, group flat PART/BOOK/... labels
 * with the chapter entries that follow them; nested manifests are returned untouched.
 */
export function groupToc(nodes: ManifestTocNode[]): ManifestTocNode[] {
  if (nodes.some(node => node.children.length > 0)) return nodes;
  const roots: ManifestTocNode[] = [];
  let current: ManifestTocNode | null = null;
  nodes.forEach((node, index) => {
    if (GROUP_LABEL.test(node.label)) {
      current = { ...node, id: node.id || `group-${index}`, children: [] };
      roots.push(current);
    } else if (current) {
      current.children.push(node);
    } else {
      roots.push(node);
    }
  });
  return roots;
}
