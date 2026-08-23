/**
 * Mirrors `SecretReferences.MASK` server-side. A header value equal to this is not a real value —
 * Vortex never sends a literal header value back to the browser (see `Environment.headerNames()`),
 * only this placeholder or, for a value that is purely a `${NAME}` reference, the reference itself.
 */
export const SECRET_MASK = '••••••••';

export interface HeaderRow {
  id: string;
  name: string;
  value: string;
  /** Seeded from a masked value — the value field shows a placeholder, not `value`, until replaced. */
  masked: boolean;
}

let nextId = 0;
function rowId(): string {
  nextId += 1;
  return `header-${nextId}`;
}

export function emptyRow(): HeaderRow {
  return { id: rowId(), name: '', value: '', masked: false };
}

/** Seeds rows from an environment's `maskedHeaders` — the read side of {@link rowsToWire}. */
export function rowsFromMasked(maskedHeaders: Record<string, string>): HeaderRow[] {
  return Object.entries(maskedHeaders).map(([name, value]) => ({
    id: rowId(),
    name,
    value,
    masked: value === SECRET_MASK,
  }));
}

/** Same newline-joined wire format the two free-text textareas always produced. */
export function rowsToWire(rows: HeaderRow[]): { headerNames: string; headerValues: string } {
  const named = rows.filter((row) => row.name.trim());
  return {
    headerNames: named.map((row) => row.name).join('\n'),
    headerValues: named.map((row) => row.value).join('\n'),
  };
}
