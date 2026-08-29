import { useState } from 'react';
import { Badge, Collapse, Group, Text, UnstyledButton } from '@mantine/core';
import { IconChevronRight } from '@tabler/icons-react';
import type { Confidence, Finding } from '../api/discovery';
import classes from './FindingCard.module.css';

const CONFIDENCE_COLOR: Record<Confidence, string> = {
  HIGH: 'pass',
  MEDIUM: 'warn',
  LOW: 'neutral',
};

const CONFIDENCE_LABEL: Record<Confidence, string> = {
  HIGH: 'High confidence',
  MEDIUM: 'Medium confidence',
  LOW: 'Low confidence',
};

/**
 * One piece of Project Discovery's evidence, collapsed by default. The point is that a reader can
 * always answer "why does Vortex think that?" without leaving the page — never that they have to;
 * see docs/04-reference/project-discovery.adoc, "Evidence explorer."
 */
export function FindingCard({ finding }: { finding: Finding }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className={classes.card}>
      <UnstyledButton
        className={classes.header}
        onClick={() => setExpanded((value) => !value)}
        aria-expanded={expanded}
      >
        <Group gap={8} wrap="nowrap" style={{ flex: 1, minWidth: 0 }}>
          <IconChevronRight
            size={13}
            className={`${classes.chevron} ${expanded ? classes.chevronOpen : ''}`}
          />
          <Text size="sm" fw={600} truncate>
            {finding.label}
          </Text>
          <Text size="xs" c="dimmed" truncate>
            {finding.sourceFile}
          </Text>
        </Group>
        <Badge color={CONFIDENCE_COLOR[finding.confidence]} variant="light" size="sm">
          {CONFIDENCE_LABEL[finding.confidence]}
        </Badge>
      </UnstyledButton>
      <Collapse expanded={expanded}>
        <div className={classes.evidence}>
          {finding.evidence.map((line) => (
            <Text key={line} size="xs" c="dimmed" className={classes.evidenceLine}>
              {line}
            </Text>
          ))}
          <Text size="xs" c="dimmed" fs="italic" mt={2}>
            {finding.confidenceExplanation}
          </Text>
        </div>
      </Collapse>
    </div>
  );
}
