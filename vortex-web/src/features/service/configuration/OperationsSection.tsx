import { useState } from 'react';
import { Badge, Button, Group, List, Stack, Tabs, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { Catalog, CatalogOperation } from '../../../api/configuration';
import { useImportCatalogMutation, useReviewOperationMutation } from '../../../api/configuration';
import { RequestDataDrawer } from '../requestdata/RequestDataDrawer';
import classes from './OperationsSection.module.css';

/**
 * What the service can do, imported from its API description — and, for each operation that can
 * change data, whether the request Vortex would send has been reviewed.
 *
 * <p>A read-only operation never shows a review control: schema-valid traffic against a GET cannot
 * corrupt anything, so there is nothing to approve.
 *
 * <p>Each row opens the request data for that endpoint, because that is how somebody thinks about
 * it — they are looking at `POST /applications` and deciding what it needs to send.
 */
export function OperationsSection({ serviceId, catalog }: { serviceId: string; catalog: Catalog }) {
  if (!catalog.imported) {
    return <ImportForm serviceId={serviceId} />;
  }
  return <CatalogView serviceId={serviceId} catalog={catalog} />;
}

function ImportForm({ serviceId, prefillUrl }: { serviceId: string; prefillUrl?: string }) {
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
          } else {
            notifications.show({ message: response.error!, color: 'fail' });
          }
        },
      }
    );
  }

  return (
    <div>
      <Text size="sm" fw={600} mb={4}>
        Import an API description
      </Text>
      <Text size="sm" c="dimmed" mb="sm">
        OpenAPI 3.x, as a URL Vortex fetches or pasted directly.
      </Text>
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
            minRows={6}
            value={content}
            onChange={(e) => setContent(e.currentTarget.value)}
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

function CatalogView({ serviceId, catalog }: { serviceId: string; catalog: Catalog }) {
  const [reimporting, setReimporting] = useState(false);
  const [inspecting, setInspecting] = useState<string | null>(null);
  const review = useReviewOperationMutation(serviceId);

  const grouped = new Map<string, CatalogOperation[]>();
  for (const op of catalog.operations) {
    const list = grouped.get(op.primaryTag) ?? [];
    list.push(op);
    grouped.set(op.primaryTag, list);
  }

  return (
    <div>
      <Group justify="space-between" align="flex-start" mb="sm">
        <Text size="sm" c="dimmed">
          {catalog.operationCount} discovered from {catalog.title || 'the imported document'}
        </Text>
        <Button size="xs" variant="subtle" onClick={() => setReimporting((v) => !v)}>
          {reimporting ? 'Cancel' : 'Re-import'}
        </Button>
      </Group>

      {reimporting && (
        <div style={{ marginBottom: '1rem' }}>
          <ImportForm serviceId={serviceId} prefillUrl={catalog.sourceRef ?? undefined} />
        </div>
      )}

      <Stack gap="lg">
        {Array.from(grouped.entries()).map(([tag, operations]) => (
          <div key={tag}>
            <Text size="xs" fw={600} c="dimmed" tt="uppercase" mb={4}>
              {tag}
            </Text>
            <Stack gap={4}>
              {operations.map((op) => (
                <Group
                  key={op.id}
                  className={classes.operation}
                  justify="space-between"
                  wrap="nowrap"
                  py={4}
                  role="button"
                  tabIndex={0}
                  aria-label={`Request data for ${op.method} ${op.path}`}
                  onClick={(event) => {
                    // The row is an inspectable object, but it also carries its own controls. The
                    // same guard TestRow uses, so pressing Review never also opens the drawer.
                    if ((event.target as HTMLElement).closest('a, button')) {
                      return;
                    }
                    setInspecting(op.id);
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      setInspecting(op.id);
                    }
                  }}
                >
                  <div style={{ minWidth: 0 }}>
                    <Group gap={6} wrap="nowrap">
                      <Badge size="xs" variant="light" color={op.kind === 'READ' ? 'live' : 'warn'}>
                        {op.method}
                      </Badge>
                      <Text size="sm" ff="monospace">
                        {op.path}
                      </Text>
                    </Group>
                    {op.summary && (
                      <Text size="xs" c="dimmed">
                        {op.summary}
                      </Text>
                    )}
                  </div>
                  {op.reviewed ? (
                    <Badge size="sm" color="pass">
                      Reviewed
                    </Badge>
                  ) : op.requiresReview ? (
                    <Button
                      size="xs"
                      variant="default"
                      loading={review.isPending}
                      onClick={() =>
                        review.mutate(op.id, {
                          onSuccess: (r) => notifications.show({ message: r.message, color: 'pass' }),
                        })
                      }
                    >
                      Review data
                    </Button>
                  ) : (
                    <Text size="xs" c="dimmed">
                      Read-only
                    </Text>
                  )}
                </Group>
              ))}
            </Stack>
          </div>
        ))}
      </Stack>

      <RequestDataDrawer
        serviceId={serviceId}
        operationId={inspecting}
        onClose={() => setInspecting(null)}
      />
    </div>
  );
}
