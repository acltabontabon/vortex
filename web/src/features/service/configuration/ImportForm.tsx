import { useState } from 'react';
import { Button, List, Tabs, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useImportCatalogMutation } from '../../../api/configuration';

const MONOSPACE_PASTE = { input: { fontFamily: 'var(--mantine-font-family-monospace)' } };

/** Import an API description — from a URL Vortex fetches, or pasted directly. Shared by the
 *  Configuration page's Operations section (first import and re-import) and the guided setup
 *  pipeline's scoped drawer, so this is the one place that owns what a good import experience
 *  looks like. */
export function ImportForm({
  serviceId,
  prefillUrl,
  onImported,
  showHeading = true,
}: {
  serviceId: string;
  prefillUrl?: string;
  /** Called once the import actually succeeds — never on a failed attempt, so a caller embedding
   *  this in a Drawer can auto-close on success while a validation error stays visible. */
  onImported?: () => void;
  /** False when the surrounding chrome (a Drawer's own `title`) already labels the form, so this
   *  doesn't repeat it. */
  showHeading?: boolean;
}) {
  const [url, setUrl] = useState(prefillUrl ?? '');
  const [content, setContent] = useState('');
  const mutation = useImportCatalogMutation(serviceId);

  function submit() {
    mutation.mutate(
      { url: url || undefined, content: content || undefined },
      {
        onSuccess: (response) => {
          if (response.succeeded) {
            notifications.show({ message: response.message!, color: 'pass' });
            if (response.info) {
              notifications.show({ message: response.info, color: 'neutral' });
            }
            onImported?.();
          } else {
            notifications.show({ message: response.error!, color: 'fail' });
          }
        },
      }
    );
  }

  return (
    <div>
      {showHeading && (
        <>
          <Text size="sm" fw={600} mb={4}>
            Import an API description
          </Text>
          <Text size="sm" c="dimmed" mb="sm">
            OpenAPI 3.x, as a URL Vortex fetches or pasted directly.
          </Text>
        </>
      )}
      <Tabs defaultValue="url">
        <Tabs.List>
          <Tabs.Tab value="url">From a URL</Tabs.Tab>
          <Tabs.Tab value="paste">Paste it</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="url" pt="sm">
          <TextInput
            placeholder="https://api.example.com/openapi.yaml"
            value={url}
            onChange={(e) => setUrl(e.currentTarget.value)}
          />
        </Tabs.Panel>
        <Tabs.Panel value="paste" pt="sm">
          <Textarea
            placeholder="openapi: 3.0.0..."
            rows={18}
            value={content}
            onChange={(e) => setContent(e.currentTarget.value)}
            styles={MONOSPACE_PASTE}
          />
        </Tabs.Panel>
      </Tabs>

      {mutation.data && !mutation.data.succeeded && (
        <div style={{ marginTop: '0.75rem' }}>
          <Text size="sm" c="fail">
            {mutation.data.error}
          </Text>
          {mutation.data.errorDetails.length > 0 && (
            <List size="sm" mt={4}>
              {mutation.data.errorDetails.map((d) => (
                <List.Item key={d}>{d}</List.Item>
              ))}
            </List>
          )}
        </div>
      )}

      <Button mt="sm" onClick={submit} loading={mutation.isPending} disabled={!url && !content}>
        Import
      </Button>
    </div>
  );
}
