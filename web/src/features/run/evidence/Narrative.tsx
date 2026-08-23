import { Title } from '@mantine/core';
import type { RunEvidence } from '../../../api/run';
import { deriveNarrative } from './narrativeLines';
import classes from './Narrative.module.css';

const MARK: Record<string, string> = { pass: '✓', warn: '⚠', fail: '✕', info: '○' };

/** "What Vortex learned" — see {@code narrative.ts} for how these lines are chosen. */
export function Narrative({ evidence }: { evidence: RunEvidence }) {
  const lines = deriveNarrative(evidence);
  if (lines.length === 0) return null;

  return (
    <section>
      <Title order={2} size="h4" mb="xs">
        What Vortex learned
      </Title>
      <ul className={classes.list}>
        {lines.map((line) => (
          <li key={line.text} className={classes[`tone_${line.tone}`]}>
            <span className={classes.mark} aria-hidden="true">
              {MARK[line.tone]}
            </span>
            <span>{line.text}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
