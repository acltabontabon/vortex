import { useMemo, useState } from 'react';
import {
  Accordion,
  ActionIcon,
  Badge,
  Button,
  Group,
  ScrollArea,
  Stack,
  Text,
  TextInput,
  Tooltip,
} from '@mantine/core';
import { IconChevronRight, IconRefresh, IconSearch } from '@tabler/icons-react';
import type { Catalog, CatalogOperation } from '../../../api/configuration';
import { RequestDataDrawer } from '../requestdata/RequestDataDrawer';
import { ImportForm } from './ImportForm';
import { SubsectionHeader } from './SubsectionHeader';
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

// A real OpenAPI description can carry hundreds of operations across dozens of tags — below this,
// every group starts open (today's typical service reads exactly as it always has); at or above it,
// everything starts collapsed so the page opens to a scannable list of tag names, not a wall of rows.
const AUTO_EXPAND_THRESHOLD = 8;

function CatalogView({ serviceId, catalog }: { serviceId: string; catalog: Catalog }) {
  const [reimporting, setReimporting] = useState(false);
  const [inspecting, setInspecting] = useState<string | null>(null);
  const [search, setSearch] = useState('');

  const allTags = useMemo(
    () => Array.from(new Set(catalog.operations.map((op) => op.primaryTag))),
    [catalog.operations]
  );
  const [openTags, setOpenTags] = useState<string[]>(() =>
    catalog.operations.length <= AUTO_EXPAND_THRESHOLD ? allTags : []
  );

  const query = search.trim().toLowerCase();
  const grouped = useMemo(() => {
    const groups = new Map<string, CatalogOperation[]>();
    for (const op of catalog.operations) {
      if (query && !`${op.method} ${op.path} ${op.summary} ${op.primaryTag}`.toLowerCase().includes(query)) {
        continue;
      }
      const list = groups.get(op.primaryTag) ?? [];
      list.push(op);
      groups.set(op.primaryTag, list);
    }
    return groups;
  }, [catalog.operations, query]);

  const visibleTags = Array.from(grouped.keys());
  const matchCount = visibleTags.reduce((n, tag) => n + (grouped.get(tag)?.length ?? 0), 0);
  // While searching, every matching group is forced open regardless of `openTags` — but the control
  // still fires onChange, so it's ignored below rather than fighting the forced-open value.
  const accordionValue = query ? visibleTags : openTags;
  const allOpen = allTags.length > 0 && allTags.every((tag) => openTags.includes(tag));

  return (
    <div>
      <SubsectionHeader
        label="Operations"
        meta={
          query
            ? `${matchCount} of ${catalog.operationCount} match "${search.trim()}"`
            : `${catalog.operationCount} discovered from ${catalog.title || 'the imported document'}`
        }
        action={
          <Tooltip label={reimporting ? 'Cancel re-import' : 'Re-import from source'}>
            <ActionIcon
              size="lg"
              variant="default"
              aria-label={reimporting ? 'Cancel re-import' : 'Re-import from source'}
              onClick={() => setReimporting((v) => !v)}
            >
              <IconRefresh size={16} />
            </ActionIcon>
          </Tooltip>
        }
      />

      {reimporting && (
        <div style={{ marginBottom: '1rem' }}>
          <ImportForm serviceId={serviceId} prefillUrl={catalog.sourceRef ?? undefined} />
        </div>
      )}

      {allTags.length > 1 && (
        <Group gap="xs" mb="sm" wrap="nowrap">
          <TextInput
            size="xs"
            placeholder="Filter by path, method, or tag…"
            leftSection={<IconSearch size={13} />}
            value={search}
            onChange={(e) => setSearch(e.currentTarget.value)}
            style={{ flex: 1 }}
          />
          {!query && (
            <Button size="compact-xs" variant="subtle" onClick={() => setOpenTags(allOpen ? [] : allTags)}>
              {allOpen ? 'Collapse all' : 'Expand all'}
            </Button>
          )}
        </Group>
      )}

      {visibleTags.length === 0 ? (
        <Text size="sm" c="dimmed">
          No operations match "{search.trim()}".
        </Text>
      ) : (
        <ScrollArea.Autosize mah={440} type="auto" offsetScrollbars>
          <Accordion
            multiple
            value={accordionValue}
            onChange={(value) => {
              if (!query) setOpenTags(value);
            }}
            variant="default"
            chevronPosition="left"
          >
            {visibleTags.map((tag) => {
              const operations = grouped.get(tag) ?? [];
              return (
                <Accordion.Item key={tag} value={tag}>
                  <Accordion.Control>
                    <Group gap={8} wrap="nowrap">
                      <Text size="xs" fw={600} c="dimmed" tt="uppercase">
                        {tag}
                      </Text>
                      <Text size="xs" c="dimmed">
                        {operations.length} operation{operations.length === 1 ? '' : 's'}
                      </Text>
                    </Group>
                  </Accordion.Control>
                  <Accordion.Panel>
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
                          <Group gap={8} wrap="nowrap">
                            {op.reviewed ? (
                              <Badge size="sm" color="pass">
                                Reviewed
                              </Badge>
                            ) : op.requiresReview ? (
                              // No one-click "approve" here — that let somebody mark data reviewed
                              // without ever seeing it. Opening the row is the only path, and the
                              // drawer itself is where approval actually happens.
                              <Badge size="sm" color="warn" variant="light">
                                Needs review
                              </Badge>
                            ) : (
                              <Text size="xs" c="dimmed">
                                Read-only
                              </Text>
                            )}
                            <IconChevronRight size={14} className={classes.chevron} />
                          </Group>
                        </Group>
                      ))}
                    </Stack>
                  </Accordion.Panel>
                </Accordion.Item>
              );
            })}
          </Accordion>
        </ScrollArea.Autosize>
      )}

      <RequestDataDrawer
        serviceId={serviceId}
        operationId={inspecting}
        onClose={() => setInspecting(null)}
      />
    </div>
  );
}
