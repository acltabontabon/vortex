import { useEffect } from 'react';
import { Button, Group, Loader, Modal, ScrollArea, Stack, Text, UnstyledButton } from '@mantine/core';
import { IconCornerLeftUp, IconFolder } from '@tabler/icons-react';
import { useBrowseDirectoryMutation } from '../api/services';
import classes from './DirectoryBrowserModal.module.css';

/**
 * A folder picker backed by Vortex's own filesystem access.
 *
 * <p>A browser has no way to turn a native file dialog's pick into an absolute path a server can act
 * on, so this walks the filesystem through {@link useBrowseDirectoryMutation} instead — clicking a
 * row descends into it, and "Choose this folder" fills the path field with wherever browsing has
 * landed.
 */
export function DirectoryBrowserModal({
  opened,
  startingPath,
  onClose,
  onChoose,
}: {
  opened: boolean;
  startingPath: string;
  onClose: () => void;
  onChoose: (path: string) => void;
}) {
  const browse = useBrowseDirectoryMutation();

  useEffect(() => {
    if (opened) {
      browse.mutate({ path: startingPath.trim() });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened]);

  const data = browse.data;

  return (
    <Modal opened={opened} onClose={onClose} title="Choose a folder" size="md">
      <Stack gap="sm">
        <Text className={classes.currentPath}>{data?.path ?? (startingPath || 'Loading…')}</Text>

        {data?.error && (
          <Text size="sm" c="fail">
            {data.error}
          </Text>
        )}

        <ScrollArea.Autosize mah={320}>
          <Stack gap={2}>
            {browse.isPending && (
              <Group justify="center" p="md">
                <Loader size="sm" />
              </Group>
            )}
            {!browse.isPending && data?.parentPath && (
              <UnstyledButton
                className={classes.row}
                onClick={() => browse.mutate({ path: data.parentPath! })}
              >
                <IconCornerLeftUp size={16} />
                <Text size="sm">..</Text>
              </UnstyledButton>
            )}
            {!browse.isPending &&
              data?.entries.map((entry) => (
                <UnstyledButton
                  key={entry.path}
                  className={classes.row}
                  onClick={() => browse.mutate({ path: entry.path })}
                >
                  <IconFolder size={16} />
                  <Text size="sm">{entry.name}</Text>
                </UnstyledButton>
              ))}
            {!browse.isPending && data && !data.error && data.entries.length === 0 && (
              <Text size="xs" c="dimmed" p="sm">
                No subfolders here.
              </Text>
            )}
          </Stack>
        </ScrollArea.Autosize>

        <Group justify="flex-end" mt="xs">
          <Button variant="subtle" color="gray" onClick={onClose}>
            Cancel
          </Button>
          <Button
            disabled={!data?.path}
            onClick={() => {
              if (data?.path) {
                onChoose(data.path);
                onClose();
              }
            }}
          >
            Choose this folder
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
