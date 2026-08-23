import { NumberInput } from '@mantine/core';
import type { MixRow } from '../api/workspace';
import classes from './TrafficDistribution.module.css';

interface EditableOperation {
  id: string;
  label: string;
  method: string;
  path: string;
}

/**
 * How one test's traffic divides across its operations.
 *
 * <p>The bar shows the shape; the figures beside it are what somebody checks. Never the bar alone —
 * a picture of a proportion is not a number, and this is a tool for producing evidence.
 *
 * <p>Under a concurrency workload there is no traffic total to divide, so the rows name the single
 * operation the virtual users drive instead of showing shares that would not mean anything.
 *
 * @param drift the rounding the allocator could not avoid. Reported rather than hidden in one
 *              operation, because a total that does not add up is the first thing a careful reader
 *              checks and the second thing they stop trusting
 * @param edit when present, turns each row's share into an editable weight — the same component
 *             used to preview a test's mix (read-only) also edits it, differing only in this prop,
 *             which is the codebase's own standard for when a figure may legitimately appear twice
 *             (editing and previewing are different jobs). `catalog` — not `rows` — decides which
 *             operations render: `rows` only ever contains what the domain already computed a share
 *             for (`weight > 0`), but every operation must stay editable, including one that has no
 *             share yet because its weight is currently zero.
 */
export function TrafficDistribution({
  rows,
  concurrency,
  drift,
  edit,
}: {
  rows: MixRow[];
  concurrency?: boolean;
  drift?: string | null;
  edit?: {
    catalog: EditableOperation[];
    weights: Record<string, number>;
    onChangeWeight: (operationId: string, value: number) => void;
  };
}) {
  const displayRows = edit
    ? edit.catalog.map<MixRow>((op) => {
        const computed = rows.find((row) => row.operationId === op.id);
        return (
          computed ?? {
            operationId: op.id,
            label: op.label,
            method: op.method,
            path: op.path,
            sharePercent: '0%',
            shareFraction: 0,
            rateDisplay: null,
            known: true,
          }
        );
      })
    : rows;

  if (displayRows.length === 0) return null;

  return (
    <div className={classes.mix}>
      {displayRows.map((row) => (
        <div key={row.operationId} className={classes.row}>
          <div className={classes.label}>
            {row.method && <span className={classes.method}>{row.method}</span>}
            <span className={classes.path}>{row.path || row.label}</span>
            {!row.known && (
              <span
                className={classes.unknown}
                title="This operation is not in the imported API description, so this test cannot run yet."
              >
                not in the API description
              </span>
            )}
          </div>

          <div className={classes.value}>
            {edit ? (
              <span className={classes.editValue}>
                <NumberInput
                  size="xs"
                  w={60}
                  min={0}
                  step={1}
                  aria-label={`Weight for ${row.method} ${row.path}`}
                  // Left blank rather than defaulted to a literal 0: matches the field's prior
                  // (pre-mixer) behavior, and typing into a fresh operation starts clean instead
                  // of appending onto a "0" already sitting in the field.
                  value={edit.weights[row.operationId]}
                  onChange={(value) =>
                    edit.onChangeWeight(row.operationId, typeof value === 'number' ? value : 0)
                  }
                />
                <strong>{row.sharePercent}</strong>
                {row.rateDisplay && <span className={classes.dim}> · {row.rateDisplay}/sec</span>}
              </span>
            ) : concurrency ? (
              <span className={classes.dim}>drives these virtual users</span>
            ) : (
              <>
                <strong>{row.sharePercent}</strong>
                {row.rateDisplay && (
                  <span className={classes.dim}> · {row.rateDisplay}/sec</span>
                )}
              </>
            )}
          </div>

          <div className={classes.bar} aria-hidden="true">
            <span style={{ width: `${(row.shareFraction * 100).toFixed(2)}%` }} />
          </div>
        </div>
      ))}

      {drift && (
        <p className={classes.drift}>
          Rounding leaves the parts {drift} requests/sec short of the total. Vortex reports the
          difference rather than hiding it in one operation.
        </p>
      )}
    </div>
  );
}
