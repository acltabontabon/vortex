import { useState } from 'react';
import { Alert, Button, Group, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { LocalLab } from '../../../api/configuration';
import {
  useClearComposeFileMutation,
  useLabDismissMutation,
  useLabDownMutation,
  useLabUpMutation,
  useSetComposeFileMutation,
} from '../../../api/configuration';
import { Fact, Facts } from '../../../components/Fact';
import { extractErrorMessage } from '../../../lib/queryFallback';
import { SectionDisclosure } from './SectionDisclosure';

/**
 * The dependencies this service needs to be tested locally, started and stopped from here.
 *
 * <p>While a command is in flight, the page-level {@link ../../../api/configuration.useConfigurationQuery}
 * polls itself every 2s — see that hook — so this section only ever renders what it is given.
 */
export function LabSection({ serviceId, localLab }: { serviceId: string; localLab: LocalLab }) {
  const [composeFile, setComposeFile] = useState('');
  const setCompose = useSetComposeFileMutation(serviceId);
  const clearCompose = useClearComposeFileMutation(serviceId);
  const up = useLabUpMutation(serviceId);
  const down = useLabDownMutation(serviceId);
  const dismiss = useLabDismissMutation(serviceId);

  const busy = up.isPending || down.isPending || localLab.running;

  function save() {
    setCompose.mutate(
      { composeFile: composeFile || localLab.composeFileDisplay || '' },
      {
        onSuccess: (response) => notifications.show({ message: response.message, color: 'pass' }),
      }
    );
  }

  const saveError = extractErrorMessage(setCompose, 'Something went wrong saving the compose file.');

  return (
    <SectionDisclosure
      id="lab"
      title="Local dependencies"
      openByDefault={!localLab.configured}
      state={localLab.configured ? localLab.composeFileDisplay! : 'not configured'}
    >
      <Group align="flex-end" gap="sm" mb="md">
        <TextInput
          label="Compose file"
          placeholder="compose.yaml"
          defaultValue={localLab.composeFileDisplay ?? ''}
          onChange={(e) => setComposeFile(e.currentTarget.value)}
          style={{ flex: 1 }}
        />
        <Button onClick={save} loading={setCompose.isPending}>
          Save
        </Button>
        {localLab.configured && (
          <Button
            variant="default"
            onClick={() =>
              clearCompose.mutate(undefined, {
                onSuccess: (r) => notifications.show({ message: r.message, color: 'neutral' }),
              })
            }
          >
            Clear
          </Button>
        )}
      </Group>
      {saveError && (
        <Text size="sm" c="fail" mb="md">
          {saveError}
        </Text>
      )}

      {localLab.configured && (
        <>
          {!localLab.status.usable && (
            <Alert color="warn" title="Docker is not usable" mb="md">
              {localLab.status.remedy}
            </Alert>
          )}

          {localLab.running && localLab.activity && (
            <Alert color="live" title={`${localLab.activity.operationLabel === 'start' ? 'Starting' : 'Stopping'} dependencies`} mb="md">
              docker compose {localLab.activity.operationCommand}
            </Alert>
          )}

          {!localLab.running && localLab.activity && (
            <Alert
              color={localLab.activity.succeeded ? 'pass' : 'fail'}
              title={localLab.activity.succeeded ? 'Succeeded' : 'Failed'}
              mb="md"
            >
              <Text size="sm">{localLab.activity.resultMessage}</Text>
              {localLab.activity.output.length > 0 && (
                <details style={{ marginTop: '0.5rem' }} open={localLab.activity.failed}>
                  <summary style={{ cursor: 'pointer', fontSize: '0.8rem' }}>Output</summary>
                  <pre style={{ fontSize: '0.75rem', whiteSpace: 'pre-wrap' }}>
                    {localLab.activity.output.join('\n')}
                  </pre>
                </details>
              )}
            </Alert>
          )}

          {!localLab.running && (
            <Group>
              <Button
                onClick={() => up.mutate(undefined, { onSuccess: (r) => notifications.show({ message: r.message, color: 'pass' }) })}
                disabled={!localLab.status.usable || busy}
                loading={up.isPending}
              >
                Start
              </Button>
              <Button
                variant="default"
                onClick={() => down.mutate(undefined, { onSuccess: (r) => notifications.show({ message: r.message, color: 'pass' }) })}
                disabled={!localLab.status.usable || busy}
                loading={down.isPending}
              >
                Stop
              </Button>
              {localLab.activity && (
                <Button variant="subtle" color="gray" onClick={() => dismiss.mutate(undefined)}>
                  Clear result
                </Button>
              )}
            </Group>
          )}

          <Facts>
            <Fact label="Compose file">{localLab.composeFileDisplay}</Fact>
          </Facts>
        </>
      )}
    </SectionDisclosure>
  );
}
