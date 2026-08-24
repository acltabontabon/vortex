import { useState } from 'react';
import { Alert, Badge, Group, List, Stack, Text, TextInput, UnstyledButton } from '@mantine/core';
import { IconCheck, IconChevronRight } from '@tabler/icons-react';
import type { DetectConfigResponse } from '../api/services';
import { FieldLabelWithHint } from './FieldLabelWithHint';
import classes from './NewServicePage.module.css';

/**
 * What "Add service" shows once a repository path resolves to a `vortex.yaml` — either a summary of
 * what adopting it would restore, or an explanation of why it could not be read. Never both: an
 * invalid file is never partially summarised.
 */
export function ConfigFoundSummary({
  detection,
  name,
  onNameChange,
  nameError,
  onContinueWithoutImporting,
}: {
  detection: DetectConfigResponse;
  name: string;
  onNameChange: (value: string) => void;
  nameError?: string | null;
  onContinueWithoutImporting: () => void;
}) {
  if (detection.valid && detection.summary) {
    return (
      <FoundValid
        summary={detection.summary}
        name={name}
        onNameChange={onNameChange}
        nameError={nameError}
      />
    );
  }
  return (
    <FoundInvalid
      problems={detection.problems}
      rawYaml={detection.rawYaml}
      sourcePath={detection.sourcePath}
      onContinueWithoutImporting={onContinueWithoutImporting}
    />
  );
}

function FoundValid({
  summary,
  name,
  onNameChange,
  nameError,
}: {
  summary: NonNullable<DetectConfigResponse['summary']>;
  name: string;
  onNameChange: (value: string) => void;
  nameError?: string | null;
}) {
  return (
    <Stack gap="sm">
      <div className={classes.evidence}>
        <Group gap={6} wrap="nowrap">
          <IconCheck size={13} stroke={2.5} className={classes.evidenceIcon} />
          <Text size="xs" fw={600}>
            Vortex configuration found
          </Text>
        </Group>
        {summary.serviceDescription && (
          <Text size="xs" c="dimmed">
            {summary.serviceDescription}
          </Text>
        )}
        <Group gap={8} wrap="wrap" mt={2}>
          {summary.workloadCount > 0 && (
            <Badge variant="light" size="sm">
              {summary.workloadCount} workload{summary.workloadCount === 1 ? '' : 's'}
            </Badge>
          )}
          {summary.environmentCount > 0 && (
            <Badge variant="light" size="sm">
              {summary.environmentCount} environment{summary.environmentCount === 1 ? '' : 's'}
            </Badge>
          )}
          {summary.operationBindingCount > 0 && (
            <Badge variant="light" size="sm">
              {summary.operationBindingCount} operation binding
              {summary.operationBindingCount === 1 ? '' : 's'}
            </Badge>
          )}
          {summary.hasProductionObservation && (
            <Badge variant="light" size="sm">
              Production observation
            </Badge>
          )}
          {summary.hasLocalLab && (
            <Badge variant="light" size="sm">
              Local lab
            </Badge>
          )}
        </Group>
        {summary.workloadNames.length > 0 && (
          <div className={classes.operationPeek}>
            {summary.workloadNames.map((workloadName) => (
              <span key={workloadName}>{workloadName}</span>
            ))}
          </div>
        )}
        {summary.openApiSourceDescription && (
          <Text size="xs" c="dimmed">
            API description: {summary.openApiSourceDescription}
          </Text>
        )}
      </div>

      <TextInput
        label={
          <FieldLabelWithHint
            text="Register as"
            hint="Vortex will restore this configuration under this name. Rename it if you'd like."
          />
        }
        size="md"
        error={nameError}
        value={name}
        onChange={(event) => onNameChange(event.currentTarget.value)}
      />
    </Stack>
  );
}

function FoundInvalid({
  problems,
  rawYaml,
  sourcePath,
  onContinueWithoutImporting,
}: {
  problems: string[];
  rawYaml: string | null;
  sourcePath: string | null;
  onContinueWithoutImporting: () => void;
}) {
  const [inspecting, setInspecting] = useState(false);

  return (
    <Alert color="warn" title="Vortex configuration found, but it could not be loaded">
      <Stack gap="xs">
        {sourcePath && (
          <Text size="sm" c="dimmed">
            Found at <code>{sourcePath}</code>.
          </Text>
        )}
        {problems.length > 0 && (
          <List size="sm">
            {problems.map((problem) => (
              <List.Item key={problem}>{problem}</List.Item>
            ))}
          </List>
        )}
        <Group gap="md">
          {rawYaml && (
            <UnstyledButton
              onClick={() => setInspecting((value) => !value)}
              className={classes.disclosureToggle}
              aria-expanded={inspecting}
            >
              <IconChevronRight
                size={14}
                className={`${classes.chevron} ${inspecting ? classes.chevronOpen : ''}`}
              />
              Inspect configuration
            </UnstyledButton>
          )}
          <UnstyledButton onClick={onContinueWithoutImporting} className={classes.disclosureToggle}>
            Continue without importing
          </UnstyledButton>
        </Group>
        {inspecting && rawYaml && <pre className={classes.configFile}>{rawYaml}</pre>}
      </Stack>
    </Alert>
  );
}
