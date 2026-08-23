/**
 * One quiet nod to the domain: a measurement scale, not a decoration. The same shape as the
 * capacity-range diagram elsewhere in the app (a line, tested capacity, and where it stopped
 * holding) — drawn here at a glance, low-contrast, as a section divider rather than a chart.
 */
export function MeasurementRule() {
  return (
    <svg
      viewBox="0 0 1200 16"
      preserveAspectRatio="none"
      width="100%"
      height="16"
      aria-hidden="true"
      style={{ display: 'block', color: 'var(--mantine-color-default-border)' }}
    >
      <line x1="0" y1="8" x2="1200" y2="8" stroke="currentColor" strokeWidth="1" />
      {Array.from({ length: 25 }, (_, i) => i * 50).map((x) => (
        <line key={x} x1={x} y1="4" x2={x} y2="12" stroke="currentColor" strokeWidth="1" />
      ))}
      <circle cx="620" cy="8" r="3" fill="var(--mantine-color-brand-6)" />
      <circle cx="960" cy="8" r="3" fill="var(--mantine-color-fail-6)" />
    </svg>
  );
}
