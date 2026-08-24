// Links an AI finding's cited evidence id back to the deterministic finding row it names — the
// evidence hierarchy insists interpretation stays traceable to measurement, not merely labelled as
// such. FindingsTable rows carry a `data-evidence-ids` attribute for exactly this lookup.

const HIGHLIGHT_CLASS = 'evidence-highlight';
const HIGHLIGHT_MS = 1500;

export function scrollToEvidence(id: string) {
  const target = document.querySelector<HTMLElement>(`[data-evidence-ids~="${CSS.escape(id)}"]`);
  if (!target) return;

  if (target instanceof HTMLDetailsElement) {
    target.open = true;
  }
  target.scrollIntoView({ behavior: 'smooth', block: 'center' });

  // Restart the fade if the same badge is clicked again mid-animation.
  target.classList.remove(HIGHLIGHT_CLASS);
  void target.offsetWidth;
  target.classList.add(HIGHLIGHT_CLASS);
  window.setTimeout(() => target.classList.remove(HIGHLIGHT_CLASS), HIGHLIGHT_MS);
}
