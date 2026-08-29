import { Divider, Modal, Stack, Text, Title } from '@mantine/core';
import { useSettingsQuery } from '../api/settings';
import classes from './AboutModal.module.css';

interface AboutModalProps {
  opened: boolean;
  onClose: () => void;
}

/**
 * Project identity, not a settings panel — plain text and one divider rather than the app's usual
 * `Fact`/`Facts` rows, so this reads as a one-time "what is this" screen instead of another table.
 */
export function AboutModal({ opened, onClose }: AboutModalProps) {
  const { data } = useSettingsQuery(opened);

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      size="sm"
      radius="md"
      centered
      aria-label="About Vortex"
      classNames={{ header: classes.header }}
    >
      <Stack gap={0} align="center" pt={4} pb="lg">
        <svg width="50" height="50" viewBox="0 0 24 24" fill="none" aria-hidden="true" className={classes.mark}>
          <path
            d="M12 3a9 9 0 1 1-6.37 2.63"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
          />
          <path
            d="M12 6.75a5.25 5.25 0 1 1-3.71 1.54"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
          />
          <circle cx="12" cy="12" r="1.8" fill="currentColor" />
        </svg>

        <Title order={3} mt={8}>
          Vortex
        </Title>
        <Text size="sm" c="dimmed" mt={4}>
          Performance Engineering Workbench
        </Text>
        <Text size="xs" c="dimmed" ta="center" maw={300} mt="md">
          A local-first performance engineering workbench for turning load tests into repeatable,
          production-informed capacity evidence.
        </Text>

        <Divider w="100%" mt="lg" mb="sm" />

        <Text size="sm" c="dimmed">
          Created by Alvin Cris Tabontabon
        </Text>
        <Text size="xs" c="dimmed" mt={4}>
          Version {data?.vortexVersion ?? '—'} · Apache License 2.0
        </Text>

        <div className={classes.links}>
          <a href="https://github.com/acltabontabon/vortex" target="_blank" rel="noopener noreferrer">
            GitHub
          </a>
          <span aria-hidden="true">·</span>
          <a
            href="https://acltabontabon.com/vortex/docs.html"
            target="_blank"
            rel="noopener noreferrer"
          >
            Documentation
          </a>
          <span aria-hidden="true">·</span>
          <a
            href="https://github.com/acltabontabon/vortex/blob/main/LICENSE"
            target="_blank"
            rel="noopener noreferrer"
          >
            License
          </a>
        </div>
      </Stack>
    </Modal>
  );
}
