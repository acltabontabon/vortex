import { useState } from 'react';
import { Collapse, Group, Text, UnstyledButton } from '@mantine/core';
import { IconChevronRight } from '@tabler/icons-react';
import type { ThresholdProvenanceDto } from '../../../api/thresholds';
import { EvidenceQualityBadge } from './EvidenceQualityBadge';
import classes from './EvidenceCard.module.css';

/**
 * "Why 550ms?" — the collapsed-by-default disclosure behind one committed threshold, in the same
 * spirit as Project Discovery's `FindingCard`: a reader can always answer "why does Vortex think
 * that?" without leaving the page. Reads the evidence snapshotted at save time, not a live fetch —
 * this is the record of what actually justified the number, which is the whole point of snapshotting.
 */
export function EvidenceCard({
  value,
  provenance,
}: {
  value: string;
  provenance: ThresholdProvenanceDto | null;
}) {
  const [expanded, setExpanded] = useState(false);

  if (!provenance || provenance.source === 'MANUAL_OBJECTIVE') {
    return (
      <Text size="xs" c="dimmed">
        Manual objective — no supporting evidence recorded.
      </Text>
    );
  }

  return (
    <div className={classes.card}>
      <UnstyledButton
        className={classes.header}
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
      >
        <Group gap={6} wrap="nowrap">
          <IconChevronRight size={12} className={`${classes.chevron} ${expanded ? classes.open : ''}`} />
          <Text size="xs" fw={600}>
            Why {value}?
          </Text>
        </Group>
        <Group gap={6} wrap="nowrap">
          <Text size="xs" c="dimmed">
            {provenance.sourceLabel}
          </Text>
          {provenance.evidenceQuality && <EvidenceQualityBadge quality={provenance.evidenceQuality} />}
        </Group>
      </UnstyledButton>
      <Collapse expanded={expanded}>
        <div className={classes.body}>
          {provenance.detail && (
            <Text size="xs" c="dimmed">
              {provenance.detail}
            </Text>
          )}
          {provenance.derivation && <Text size="xs">{provenance.derivation}</Text>}
          {provenance.baselineExecutionId && (
            <Text size="xs" c="dimmed">
              From run {provenance.baselineExecutionId}
            </Text>
          )}
        </div>
      </Collapse>
    </div>
  );
}
