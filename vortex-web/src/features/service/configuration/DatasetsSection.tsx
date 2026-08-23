import { useRef, useState } from 'react';
import { Alert, Badge, Button, Group, Skeleton, Stack, Text } from '@mantine/core';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import { ApiError } from '../../../api/client';
import {
  useDatasetsQuery,
  useDeleteDatasetMutation,
  usePromoteDatasetMutation,
  useUploadDatasetMutation,
  type DatasetSummary,
} from '../../../api/requestData';
import classes from './DatasetsSection.module.css';

const ACCEPTED = '.csv,.json';

/**
 * The realistic data a service's requests draw from.
 *
 * <p>The file is read here, in the browser, and its contents are sent — Vortex never takes a
 * filesystem path over HTTP. Being a local tool is a reason to be careful about that, not a licence
 * to skip it.
 *
 * <p>A dataset lands on this machine. Committing it alongside the service is a separate, explicit
 * action that names the file it will write first, because test data that turns out to be real
 * customer data is not a mistake anyone should make by dragging a file into a browser.
 */
export function DatasetsSection({ serviceId }: { serviceId: string }) {
  const query = useDatasetsQuery(serviceId);
  const upload = useUploadDatasetMutation(serviceId);
  const fileInput = useRef<HTMLInputElement>(null);
  const [problem, setProblem] = useState('');

  async function chooseFile(file: File | undefined) {
    if (!file) {
      return;
    }
    setProblem('');
    const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
    if (extension !== 'csv' && extension !== 'json') {
      setProblem(`Vortex reads CSV and JSON. ${file.name} is neither.`);
      return;
    }
    const content = await file.text();
    upload.mutate(
      {
        name: file.name.replace(/\.[^.]+$/, '').toLowerCase().replace(/[^a-z0-9_-]/g, '-'),
        format: extension,
        scope: 'local',
        content,
      },
      {
        onSuccess: (dataset) =>
          notifications.show({
            message: `Added ${dataset.name} — ${dataset.records.toLocaleString()} records.`,
            color: 'pass',
          }),
        onError: (error) =>
          setProblem(
            error instanceof ApiError && error.detail
              ? error.detail
              : `Vortex could not read ${file.name}.`
          ),
      }
    );
  }

  return (
    <div>
      <Text size="sm" c="dimmed" mb="sm">
        Values a request needs that have to be real — customer ids, account numbers, product codes.
        Rows are walked in order and wrap at the end; every value one request reads from a dataset
        comes from the same row.
      </Text>

      {problem && (
        <Alert color="fail" mb="sm">
          {problem}
        </Alert>
      )}

      {query.isPending && <Skeleton height={60} />}

      {query.data && query.data.length > 0 && (
        <Stack gap={0} mb="sm">
          {query.data.map((dataset) => (
            <DatasetRow key={`${dataset.scope}:${dataset.name}`} serviceId={serviceId} dataset={dataset} />
          ))}
        </Stack>
      )}

      {query.data && query.data.length === 0 && (
        <Text size="sm" c="dimmed" mb="sm">
          No datasets yet. Add a CSV or JSON file and its columns become selectable wherever a
          request needs a value.
        </Text>
      )}

      <input
        ref={fileInput}
        type="file"
        accept={ACCEPTED}
        className={classes.fileInput}
        onChange={(event) => {
          void chooseFile(event.currentTarget.files?.[0]);
          event.currentTarget.value = '';
        }}
      />
      <Button
        size="xs"
        variant="default"
        loading={upload.isPending}
        onClick={() => fileInput.current?.click()}
      >
        Add a dataset
      </Button>
    </div>
  );
}

function DatasetRow({ serviceId, dataset }: { serviceId: string; dataset: DatasetSummary }) {
  const promote = usePromoteDatasetMutation(serviceId);
  const remove = useDeleteDatasetMutation(serviceId);

  function confirmPromote() {
    modals.openConfirmModal({
      title: `Commit ${dataset.name} with this service?`,
      children: (
        <Stack gap="xs">
          <Text size="sm">Vortex will write:</Text>
          <Text size="sm" ff="monospace">
            {dataset.promotionTarget}
          </Text>
          <Text size="sm" c="dimmed">
            The file becomes part of the repository, so anyone who checks it out can run tests that
            use it. Check that it holds test data rather than anything real first.
          </Text>
        </Stack>
      ),
      labels: { confirm: 'Write the file', cancel: 'Cancel' },
      onConfirm: () =>
        promote.mutate(dataset.name, {
          onSuccess: () =>
            notifications.show({
              message: `${dataset.name} is now committed with this service.`,
              color: 'pass',
            }),
          onError: (error) =>
            notifications.show({
              message:
                error instanceof ApiError && error.detail
                  ? error.detail
                  : `Vortex could not commit ${dataset.name}.`,
              color: 'fail',
            }),
        }),
    });
  }

  return (
    <div className={classes.row}>
      <Group justify="space-between" wrap="nowrap" align="flex-start">
        <div style={{ minWidth: 0 }}>
          <Group gap={6} wrap="nowrap">
            <Text size="sm" fw={600} ff="monospace">
              {dataset.name}
            </Text>
            <Badge size="xs" variant="light" color={dataset.scope === 'portable' ? 'pass' : 'neutral'}>
              {dataset.scope === 'portable' ? 'committed' : 'this machine'}
            </Badge>
          </Group>
          {dataset.problem ? (
            <Text size="xs" c="fail">
              {dataset.problem}
            </Text>
          ) : (
            <Text size="xs" c="dimmed">
              {dataset.records.toLocaleString()} records · {dataset.fields.join(', ')}
            </Text>
          )}
        </div>
        <Group gap={4} wrap="nowrap">
          {dataset.scope === 'local' && dataset.promotionTarget && (
            <Button size="compact-xs" variant="subtle" onClick={confirmPromote}>
              Commit with service
            </Button>
          )}
          <Button
            size="compact-xs"
            variant="subtle"
            color="fail"
            onClick={() =>
              remove.mutate(
                { name: dataset.name, scope: dataset.scope },
                {
                  onSuccess: (response) =>
                    notifications.show({ message: response.message, color: 'pass' }),
                }
              )
            }
          >
            Remove
          </Button>
        </Group>
      </Group>

      {/* A few records, never the dataset. Somebody needs to recognise the file, not browse it. */}
      {dataset.preview.length > 0 && (
        <div className={classes.preview}>
          <table className={classes.previewTable}>
            <thead>
              <tr>
                {dataset.fields.map((field) => (
                  <th key={field}>{field}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {dataset.preview.map((record, index) => (
                <tr key={index}>
                  {dataset.fields.map((field) => (
                    <td key={field}>{formatCell(record[field])}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/** An absent cell reads as an em dash rather than as the word "null", which is not what it says. */
function formatCell(value: unknown): string {
  return value === null || value === undefined ? '—' : String(value);
}
