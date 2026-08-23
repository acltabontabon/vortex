import { useState } from 'react';
import { Title } from '@mantine/core';
import type { OperationEvidence } from '../../../api/run';
import shared from './shared.module.css';
import classes from './OperationsTable.module.css';

type SortKey = 'name' | 'requests' | 'rate' | 'p95' | 'p99' | 'errors';

const SORT_FIELD: Record<SortKey, keyof OperationEvidence> = {
  name: 'name',
  requests: 'requestsDisplay',
  rate: 'rateDisplay',
  p95: 'p95Display',
  p99: 'p99Display',
  errors: 'errorRateDisplay',
};

/** Numeric prefix of a display string, for sorting formatted values like "120 ms" or "1.2%" without
 *  re-deriving the number the domain already formatted — comparison only, never displayed. */
function leadingNumber(value: string | null): number {
  if (!value) return -Infinity;
  const match = value.match(/-?[\d.]+/);
  return match ? parseFloat(match[0]) : -Infinity;
}

export function OperationsTable({ operations }: { operations: OperationEvidence[] }) {
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'requests', desc: true });

  const sorted = [...operations].sort((a, b) => {
    const field = SORT_FIELD[sort.key];
    const cmp = sort.key === 'name'
      ? String(a[field] ?? '').localeCompare(String(b[field] ?? ''))
      : leadingNumber(a[field] as string | null) - leadingNumber(b[field] as string | null);
    return sort.desc ? -cmp : cmp;
  });

  function toggle(key: SortKey) {
    setSort((current) => (current.key === key ? { key, desc: !current.desc } : { key, desc: true }));
  }

  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        By operation
      </Title>
      <div className={shared.table}>
        <div className={`${classes.row} ${shared.head}`}>
          <SortHeader label="Operation" sortKey="name" sort={sort} onSort={toggle} />
          <SortHeader label="Requests" sortKey="requests" sort={sort} onSort={toggle} />
          <SortHeader label="Rate" sortKey="rate" sort={sort} onSort={toggle} />
          <SortHeader label="p95" sortKey="p95" sort={sort} onSort={toggle} />
          <SortHeader label="p99" sortKey="p99" sort={sort} onSort={toggle} />
          <SortHeader label="Errors" sortKey="errors" sort={sort} onSort={toggle} />
        </div>
        {sorted.map((op) => (
          <div key={op.name} className={classes.row}>
            <span>{op.name}</span>
            {op.hasTraffic ? (
              <>
                <span>{op.requestsDisplay}</span>
                <span>{op.rateDisplay}</span>
                <span>{op.p95Display}</span>
                <span>{op.p99Display}</span>
                <span>{op.errorRateDisplay}</span>
              </>
            ) : (
              <span className={shared.dim}>no traffic</span>
            )}
          </div>
        ))}
      </div>
    </section>
  );
}

function SortHeader({
  label,
  sortKey,
  sort,
  onSort,
}: {
  label: string;
  sortKey: SortKey;
  sort: { key: SortKey; desc: boolean };
  onSort: (key: SortKey) => void;
}) {
  const active = sort.key === sortKey;
  return (
    <button type="button" className={classes.sortButton} onClick={() => onSort(sortKey)}>
      {label}
      {active && <span aria-hidden="true">{sort.desc ? ' ↓' : ' ↑'}</span>}
    </button>
  );
}
