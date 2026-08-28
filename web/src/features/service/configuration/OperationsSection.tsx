import { useState } from 'react';
import { Badge, Button, Group, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { Catalog, CatalogOperation } from '../../../api/configuration';
import { useReviewOperationMutation } from '../../../api/configuration';
import { RequestDataDrawer } from '../requestdata/RequestDataDrawer';
import { ImportForm } from './ImportForm';
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
