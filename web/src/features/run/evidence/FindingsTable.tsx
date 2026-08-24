import { Title } from '@mantine/core';
import type { RunEvidence } from '../../../api/run';
import shared from './shared.module.css';
import classes from './FindingsTable.module.css';

const MARK: Record<string, string> = { FAIL: '✕', WARNING: '⚠', OBSERVATION: '○', PASS: '✓' };
const TONE: Record<string, string> = { FAIL: shared.fail, WARNING: shared.warn, OBSERVATION: shared.neutral, PASS: shared.pass };

/**
 * Every deterministic finding, one line each — replacing the old page's large card-per-finding
 * layout. A finding expands, on click, to its own detail, evidence strength and citable ids; nothing
 * below the headline is shown until asked for.
 */
export function FindingsTable({ findings }: { findings: RunEvidence['findings'] }) {
  if (findings.length === 0) return null;

  return (
    <section>
      <Title order={2} size="h4" mb="sm">
        Findings
      </Title>
      <div className={shared.table}>
        {findings.map((finding) => (
          <details
            key={finding.headline}
            className={classes.row}
            data-evidence-ids={finding.evidenceIds.join(' ')}
          >
            <summary className={classes.summary}>
              <span className={`${classes.mark} ${TONE[finding.levelKind] ?? ''}`}>
                {MARK[finding.levelKind] ?? '?'}
              </span>
              <span className={classes.headline}>{finding.headline}</span>
              <span className={shared.dim}>{finding.strengthLabel}</span>
            </summary>
            {(finding.hasDetail || finding.evidenceIds.length > 0) && (
              <div className={classes.detail}>
                {finding.hasDetail && finding.detail && <p>{finding.detail}</p>}
                {finding.evidenceIds.length > 0 && (
                  <p className={shared.dim}>Evidence: {finding.evidenceIds.join(', ')}</p>
                )}
              </div>
            )}
          </details>
        ))}
      </div>
    </section>
  );
}
