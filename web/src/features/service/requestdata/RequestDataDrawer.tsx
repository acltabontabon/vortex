import { useState } from 'react';
import { Alert, Badge, Button, Drawer, Group, Skeleton, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useReviewOperationMutation } from '../../../api/configuration';
import {
  TARGET_LABEL,
  toUpdate,
  useRequestDataQuery,
  useSaveRequestDataMutation,
  type RequestDataView,
  type ValueSlot,
  type ValueTarget,
} from '../../../api/requestData';
import { ApiError } from '../../../api/client';
import { ValueSlotRow } from './ValueSlotRow';
import classes from './RequestDataDrawer.module.css';

/** The order a request is actually assembled in, which is the order somebody reads it. */
const ORDER: ValueTarget[] = ['PATH', 'QUERY', 'HEADER', 'BODY_FIELD'];

/**
 * What one endpoint sends.
 *
 * <p>Request-centric, because that is how somebody thinks about it: they are looking at
 * `POST /applications` and deciding what it needs. There is deliberately no page that configures
 * every operation's data at once — that would be a wall of forms, and the thing this feature exists
 * to avoid is making a developer feel they are programming a load test rather than describing an
 * endpoint.
 *
 * <p>An inspection-and-edit panel in the same right-side drawer the workspace already uses for a
 * test's details, so the workspace keeps one vocabulary for "look closer at this thing".
 */
export function RequestDataDrawer({
  serviceId,
  operationId,
  onClose,
}: {
  serviceId: string;
  operationId: string | null;
  onClose: () => void;
}) {
  const query = useRequestDataQuery(serviceId, operationId);

  return (
    <Drawer
      opened={operationId !== null}
      onClose={onClose}
      position="right"
      size={560}
      padding="xl"
      title={
        query.data && (
          <span className={classes.title}>
            <Badge size="sm" variant="light" color={query.data.mutating ? 'warn' : 'live'}>
              {query.data.method}
            </Badge>
            <span className={classes.path}>{query.data.path}</span>
          </span>
        )
      }
    >
      {query.isPending && <Skeleton height={220} />}

      {query.isError && (
        <Alert color="fail">
          {query.error instanceof ApiError && query.error.detail
            ? query.error.detail
            : 'Vortex could not read this operation.'}
        </Alert>
      )}

      {query.data && (
        // Keyed by operation, so opening a different endpoint starts from that endpoint's values
        // rather than carrying the previous form's edits across. The form seeds its own state from
        // the data it is given, which is why there is no effect here re-seeding it — one that ran on
        // every refetch would discard what somebody was in the middle of typing.
        <RequestDataForm
          key={query.data.operationId}
          serviceId={serviceId}
          data={query.data}
          onClose={onClose}
        />
      )}
    </Drawer>
  );
}

function RequestDataForm({
  serviceId,
  data,
  onClose,
}: {
  serviceId: string;
  data: RequestDataView;
  onClose: () => void;
}) {
  const save = useSaveRequestDataMutation(serviceId, data.operationId);
  const review = useReviewOperationMutation(serviceId);
  const [slots, setSlots] = useState<ValueSlot[]>(data.values);

  // A mutating operation Vortex hasn't been told to trust yet — approving it here, having just
  // looked at the values, is the whole point of the gate. There used to be a one-click "Review
  // data" button on the operations list that skipped straight to this without opening anything;
  // it let somebody mark data reviewed without ever seeing what they were approving.
  const needsApproval = data.mutating && !data.reviewed;

  function approve(message: string) {
    review.mutate(data.operationId, {
      onSuccess: (r) => {
        notifications.show({ message: r.message ?? message, color: 'pass' });
        onClose();
      },
      onError: (error) => {
        notifications.show({
          message:
            error instanceof ApiError && error.detail
              ? error.detail
              : 'Vortex could not approve this operation.',
          color: 'fail',
        });
      },
    });
  }

  function submit() {
    if (slots.length === 0) {
      // Nothing to save — this is a pure approval, and the button below never reaches here unless
      // there's either something to save or approving is exactly what it means to do.
      approve('Approved.');
      return;
    }
    save.mutate(
      { body: data.body, values: slots.map(toUpdate) },
      {
        onSuccess: (response) => {
          if (needsApproval) {
            approve(response.message);
          } else {
            notifications.show({ message: response.message, color: 'pass' });
            onClose();
          }
        },
        onError: (error) => {
          notifications.show({
            message:
              error instanceof ApiError && error.detail
                ? error.detail
                : 'Vortex could not save this request data.',
            color: 'fail',
          });
        },
      }
    );
  }

  return (
    <Stack gap="xl">
      {needsApproval && (
        <Text size="sm" c="dimmed">
          This operation can change data, so Vortex won't send it in a workload until a person
          confirms these are the right values. {slots.length === 0 ? 'Approving' : 'Saving'} below
          does that.
        </Text>
      )}

      {slots.length === 0 ? (
        <Text size="sm" c="dimmed">
          This operation takes no parameters and sends no body, so there is nothing to configure.
          Vortex will issue it exactly as the API description declares it.
        </Text>
      ) : (
        ORDER.filter((target) => slots.some((slot) => slot.target === target)).map((target) => (
          <div key={target}>
            <div className={classes.sectionLabel}>{TARGET_LABEL[target]}</div>
            {slots
              .filter((slot) => slot.target === target)
              .map((slot) => (
                <ValueSlotRow
                  key={`${slot.target}:${slot.name}`}
                  slot={slot}
                  datasets={data.datasets}
                  generators={data.generators}
                  onChange={(next) =>
                    setSlots((current) =>
                      current.map((candidate) =>
                        candidate.target === next.target && candidate.name === next.name
                          ? next
                          : candidate
                      )
                    )
                  }
                />
              ))}
          </div>
        ))
      )}

      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          Cancel
        </Button>
        <Button
          onClick={submit}
          loading={save.isPending || review.isPending}
          disabled={slots.length === 0 && !needsApproval}
        >
          {needsApproval ? (slots.length === 0 ? 'Approve' : 'Save & approve') : 'Save'}
        </Button>
      </Group>
    </Stack>
  );
}
