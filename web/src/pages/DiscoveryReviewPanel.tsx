import type { ReactNode } from 'react';
import { useState } from 'react';
import { Alert, Button, Checkbox, Group, List, Stack, Text } from '@mantine/core';
import type { ConflictField, DiscoveryConflict, DiscoveryScanResponse } from '../api/discovery';
import { FindingCard } from './FindingCard';
import classes from './DiscoveryReviewPanel.module.css';

const DEPENDENCY_KINDS = new Set([
  'DEPENDENCY_POSTGRESQL',
  'DEPENDENCY_REDIS',
  'DEPENDENCY_KAFKA',
  'DEPENDENCY_WIREMOCK',
]);
const OBSERVABILITY_KINDS = new Set(['OBSERVABILITY_PROMETHEUS', 'OBSERVABILITY_ACTUATOR']);

export interface DiscoverySelections {
  includeOpenApiSource: boolean;
  includeEnvironment: boolean;
  includeLocalLab: boolean;
}

/**
 * What Vortex found in a project, reviewed and approved one section at a time. Every checkbox here
 * defaults to on for an unambiguous, conflict-free proposal and off for anything that disagrees with
 * what the service already has saved — "keep existing" is always the safer default, never
 * "discovered wins." Nothing is applied by rendering this; {@link onApply} only reports what was
 * selected, and it is the caller's job to turn that into a real request (see
 * {@code DiscoveryReviewDrawer} for the existing-service path, and {@code NewServicePage} for
 * onboarding, which folds the selection into its own "create service" submit instead of applying
 * separately).
 */
export function DiscoveryReviewPanel({
  proposal,
  onApply,
  applying = false,
  applyError = null,
  primaryLabel = 'Apply setup',
}: {
  proposal: DiscoveryScanResponse;
  onApply: (selections: DiscoverySelections) => void;
  applying?: boolean;
  applyError?: string | null;
  primaryLabel?: string;
}) {
  const conflictFor = (field: ConflictField) =>
    proposal.conflicts.find((conflict) => conflict.field === field);

  const [includeOpenApiSource, setIncludeOpenApiSource] = useState(
    Boolean(proposal.proposedOpenApiSourceFile) && !conflictFor('OPENAPI_SOURCE'),
  );
  const [includeEnvironment, setIncludeEnvironment] = useState(
    Boolean(proposal.proposedEnvironment) && !conflictFor('EXECUTION_TARGET'),
  );
  const [includeLocalLab, setIncludeLocalLab] = useState(
    Boolean(proposal.proposedLocalLabComposeFile) && !conflictFor('LOCAL_LAB'),
  );

  const openApiFindings = proposal.findings.filter((finding) => finding.kind === 'OPENAPI_SPEC');
  const dependencyFindings = proposal.findings.filter((finding) => DEPENDENCY_KINDS.has(finding.kind));
  const observabilityFindings = proposal.findings.filter((finding) => OBSERVABILITY_KINDS.has(finding.kind));
  const shown = new Set([...openApiFindings, ...dependencyFindings, ...observabilityFindings]);
  const otherFindings = proposal.findings.filter((finding) => !shown.has(finding));

  const hasNothingActionable =
    !proposal.proposedOpenApiSourceFile && !proposal.proposedEnvironment && !proposal.proposedLocalLabComposeFile;

  if (proposal.findings.length === 0) {
    return (
      <Alert color="neutral" title="Nothing to apply yet">
        Vortex couldn&rsquo;t find a configuration it can safely use in this project. You can still
        configure this service manually.
      </Alert>
    );
  }

  return (
    <Stack gap="lg">
      {proposal.partialFailures.length > 0 && (
        <Alert
          color="warn"
          title={`Inspection completed with ${proposal.partialFailures.length} warning${
            proposal.partialFailures.length === 1 ? '' : 's'
          }`}
        >
          <List size="sm">
            {proposal.partialFailures.map((failure) => (
              <List.Item key={failure}>{failure}</List.Item>
            ))}
          </List>
        </Alert>
      )}

      {openApiFindings.length > 0 && (
        <Section title="Interface">
          {openApiFindings.map((finding) => (
            <FindingCard key={finding.sourceFile} finding={finding} />
          ))}
          {proposal.proposedOpenApiSourceFile && (
            <Checkbox
              checked={includeOpenApiSource}
              onChange={(event) => setIncludeOpenApiSource(event.currentTarget.checked)}
              label={`Import operations from ${proposal.proposedOpenApiSourceFile}`}
            />
          )}
          <ConflictNotice conflict={conflictFor('OPENAPI_SOURCE')} />
        </Section>
      )}

      {dependencyFindings.length > 0 && (
        <Section title="Dependencies">
          {dependencyFindings.map((finding) => (
            <FindingCard key={`${finding.kind}-${finding.sourceFile}`} finding={finding} />
          ))}
          {proposal.proposedLocalLabComposeFile ? (
            <Checkbox
              checked={includeLocalLab}
              onChange={(event) => setIncludeLocalLab(event.currentTarget.checked)}
              label={`Use ${proposal.proposedLocalLabComposeFile} as the Local Lab file`}
            />
          ) : (
            <Text size="xs" c="dimmed">
              These dependencies span more than one Compose file, so Vortex isn&rsquo;t guessing which
              one to link — add a Local Lab file manually if you&rsquo;d like one.
            </Text>
          )}
          <ConflictNotice conflict={conflictFor('LOCAL_LAB')} />
        </Section>
      )}

      {proposal.proposedEnvironment && (
        <Section title="Execution target">
          <Text size="sm">{proposal.proposedEnvironment.targetSummary}</Text>
          <Checkbox
            checked={includeEnvironment}
            onChange={(event) => setIncludeEnvironment(event.currentTarget.checked)}
            label={`Add "${proposal.proposedEnvironment.name}" as an execution target`}
          />
          <ConflictNotice conflict={conflictFor('EXECUTION_TARGET')} />
        </Section>
      )}

      {observabilityFindings.length > 0 && (
        <Section title="Observability">
          {observabilityFindings.map((finding) => (
            <FindingCard key={finding.kind} finding={finding} />
          ))}
          <Text size="xs" c="dimmed">
            Configure where this data comes from once the service is running, from Settings.
          </Text>
        </Section>
      )}

      {otherFindings.length > 0 && (
        <Section title="Also detected">
          {otherFindings.map((finding) => (
            <FindingCard key={`${finding.kind}-${finding.sourceFile}`} finding={finding} />
          ))}
        </Section>
      )}

      {applyError && (
        <Alert color="fail" title="Could not apply this setup">
          {applyError}
        </Alert>
      )}

      {!hasNothingActionable && (
        <Group>
          <Button
            loading={applying}
            onClick={() =>
              onApply({ includeOpenApiSource, includeEnvironment, includeLocalLab })
            }
          >
            {primaryLabel}
          </Button>
        </Group>
      )}
    </Stack>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <Text className={classes.sectionTitle}>{title}</Text>
      <Stack gap="xs">{children}</Stack>
    </div>
  );
}

function ConflictNotice({ conflict }: { conflict: DiscoveryConflict | undefined }) {
  if (!conflict) {
    return null;
  }
  return (
    <Alert color="warn" variant="light" title="Different from what's already saved">
      <Text size="sm">Existing: {conflict.existingDescription}</Text>
      <Text size="sm">Discovered: {conflict.discoveredDescription}</Text>
      <Text size="xs" c="dimmed" mt={4}>
        Left unchecked by default — check the box above to use the discovered value instead.
      </Text>
    </Alert>
  );
}
