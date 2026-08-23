/** A service's short visual identity: initials plus a deterministic color from the Vortex palette. */

/**
 * "checkout-service" → CS, "loan-account-service" → LAS, "orders" → OR.
 * Splits on kebab/snake/space and camelCase boundaries; a single word takes its first two letters
 * rather than just one, so short single-word service names still get a recognizable two-letter mark.
 */
export function initialsFor(name: string): string {
  const words = name
    .trim()
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .split(/[-_\s]+/)
    .filter(Boolean);

  if (words.length === 0) return '?';
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return words
    .slice(0, 3)
    .map((w) => w[0].toUpperCase())
    .join('');
}

// Restrained, already-in-theme hues — never a hue chosen only for identity, so a badge never reads
// as a status color by accident. Two shade pairs per hue give more visual variety than one swatch
// each without introducing a single new color into the palette.
const BADGE_VARIANTS: Array<{ bg: string; fg: string }> = [
  { bg: 'var(--mantine-color-brand-1)', fg: 'var(--mantine-color-brand-8)' },
  { bg: 'var(--mantine-color-brand-2)', fg: 'var(--mantine-color-brand-9)' },
  { bg: 'var(--mantine-color-live-1)', fg: 'var(--mantine-color-live-8)' },
  { bg: 'var(--mantine-color-live-2)', fg: 'var(--mantine-color-live-9)' },
  { bg: 'var(--mantine-color-ai-1)', fg: 'var(--mantine-color-ai-8)' },
  { bg: 'var(--mantine-color-ai-2)', fg: 'var(--mantine-color-ai-9)' },
  { bg: 'var(--mantine-color-neutral-2)', fg: 'var(--mantine-color-neutral-8)' },
  { bg: 'var(--mantine-color-warn-1)', fg: 'var(--mantine-color-warn-8)' },
];

/** Same service, same variant, every render — a badge is a landmark, not a decoration that shifts. */
export function badgeVariantFor(id: string): { bg: string; fg: string } {
  let hash = 0;
  for (let i = 0; i < id.length; i++) {
    hash = (hash * 31 + id.charCodeAt(i)) | 0;
  }
  return BADGE_VARIANTS[Math.abs(hash) % BADGE_VARIANTS.length];
}
