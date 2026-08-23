import { ActionIcon, Button, Group, Menu, Text } from '@mantine/core';
import { IconDots } from '@tabler/icons-react';
import type { Environment } from '../../../api/configuration';
import { ClassificationChip } from '../../../components/ClassificationChip';
import classes from './EnvironmentsSection.module.css';

/**
 * Only `EXTERNAL_ENDPOINT`'s summary is a bare, non-self-describing address — a Docker/Compose
 * summary already starts with "Docker:"/"Compose:", so prefixing it with its kind again would say
 * the same thing twice (the same reasoning `ServiceHeader` already applies to this exact pair).
 */
const TARGET_KIND_LABEL: Record<string, string> = {
  EXTERNAL_ENDPOINT: 'Endpoint',
};

/**
 * One environment, as a row rather than a form — name and classification are what somebody scans
 * for first; target, dependencies and (when it matters) production-sizing are the supporting line.
 * Edit and Delete are quiet until the row is touched, matching `ServiceHeader`'s own header actions.
 */
export function EnvironmentCard({
  environment,
  onEdit,
  onDelete,
}: {
  environment: Environment;
  onEdit: () => void;
  onDelete: () => void;
}) {
  return (
    <div className={classes.card}>
      <div className={classes.info}>
        <Group gap={8} wrap="nowrap">
          <Text size="sm" fw={600} ff="monospace">
            {environment.name}
          </Text>
          <ClassificationChip
            classification={environment.classification as 'ISOLATED' | 'INTEGRATED'}
            label={environment.classificationLabel}
            caveat={environment.classificationCaveat}
          />
        </Group>
        <Text size="xs" c="dimmed" className={classes.detail}>
          {TARGET_KIND_LABEL[environment.target.kind] && (
            <>{TARGET_KIND_LABEL[environment.target.kind]}{' · '}</>
          )}
          {environment.target.summary}
          {' · '}
          {environment.dependencyModeLabel}
          {environment.productionLike && (
            <>
              {' · '}
              <Text component="span" fw={600}>
                production-sized
              </Text>
            </>
          )}
        </Text>
      </div>

      <Group gap={4} wrap="nowrap" className={classes.actions}>
        <Button
          size="compact-xs"
          variant="subtle"
          color="gray"
          className={classes.editAction}
          aria-label={`Edit ${environment.name}`}
          onClick={onEdit}
        >
          Edit
        </Button>
        <Menu shadow="md" width={170} position="bottom-end">
          <Menu.Target>
            <ActionIcon
              variant="subtle"
              color="gray"
              size="sm"
              aria-label={`More actions for ${environment.name}`}
            >
              <IconDots size={16} />
            </ActionIcon>
          </Menu.Target>
          <Menu.Dropdown>
            <Menu.Item color="fail" onClick={onDelete}>
              Delete environment
            </Menu.Item>
          </Menu.Dropdown>
        </Menu>
      </Group>
    </div>
  );
}
