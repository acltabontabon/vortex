import { ActionIcon, Text, Title, Tooltip } from '@mantine/core';
import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { IconPencil, IconTrash } from '@tabler/icons-react';
import type { Readiness, RunRef, ServiceHeader as Header } from '../../api/workspace';
import { ApiError } from '../../api/client';
import { useDeleteServiceMutation } from '../../api/services';
import { ServiceBadge } from '../../components/ServiceBadge';
import { ClassificationChip } from '../../components/ClassificationChip';
import { ReadinessPill } from './ReadinessPill';
import classes from './ServiceHeader.module.css';

/**
 * Who this service is, and where it points.
 *
 * <p>The meta line is the service's whole physical situation on one row: the environment, the
 * address traffic actually goes to, and what class of question a run against it can answer. All in
 * monospace, because every one of them is an identifier somebody may need to read character by
 * character. This is also the header's whole job as far as classification and environment go —
 * Overview's fact grid and Evidence never repeat "Isolated" or the environment name, because this
 * line already said it once, permanently, on every tab.
 *
 * <p>Release is metadata about the header's own claim, not a fact this page is built around, so it
 * only takes up room here when it's actually known — an absent release is a limit worth knowing, but
 * it doesn't need to occupy the same permanent screen space as the service's name on every visit.
 *
 * <p>There is no primary "Run test" action here any more. It used to be, back when Tests was a tab
 * you had to navigate to — the header was the fastest path to running something from anywhere. Now
 * that every test's own `Run` button is a glance down the Overview page (and Tests and Runs carry
 * their own per-row run actions too), a second "run something, which one is up to you" control up
 * here duplicated a decision the row below already makes better, with the test's own question,
 * shape and latest result right there to inform it. What's left is a live readout when a run is
 * already in flight, since that fact is true on every tab under this header, not only Overview's.
 */
export function ServiceHeader({ header }: { header: Header }) {
  const navigate = useNavigate();
  const remove = useDeleteServiceMutation();

  function confirmDelete() {
    modals.openConfirmModal({
      title: `Delete '${header.name}'?`,
      children: (
        <Text size="sm">
          This removes every run, analysis and piece of evidence Vortex has recorded for this
          service. There is no undo. The service's own <code>vortex.yaml</code>, if it has one, is
          left where it is — this only removes it from Vortex.
        </Text>
      ),
      labels: { confirm: 'Delete', cancel: 'Cancel' },
      confirmProps: { color: 'fail' },
      onConfirm: () =>
        remove.mutate(header.id, {
          onSuccess: () => {
            notifications.show({ message: `'${header.name}' deleted.`, color: 'pass' });
            navigate('/');
          },
          onError: (error) =>
            notifications.show({
              message:
                error instanceof ApiError && error.detail
                  ? error.detail
                  : `Vortex could not delete '${header.name}'.`,
              color: 'fail',
            }),
        }),
    });
  }

  return (
    <header className={classes.header}>
      <div className={classes.identity}>
        <div className={classes.titleRow}>
          <ServiceBadge id={header.id} name={header.name} />
          <Title order={1} fw={650} className={classes.name}>
            {header.name}
          </Title>
        </div>

        {header.description && <p className={classes.description}>{header.description}</p>}

        <div className={classes.meta}>
          {header.target ? (
            <>
              <span className={classes.env}>{header.target.environmentName}</span>
              <span className={classes.url}>{stripScheme(header.target.baseUrl)}</span>
              {/* A Docker/Compose target has no pre-run baseUrl (it's the empty string above), so
                  its summary is what carries the useful identity — "Docker: payment-service:1.4.2".
                  Omitted for an external endpoint, where stripScheme(baseUrl) above already is the
                  useful summary and showing both would say the same thing twice. */}
              {header.target.targetKind !== 'EXTERNAL_ENDPOINT' && (
                <span className={classes.url}>{header.target.targetSummary}</span>
              )}
              <ClassificationChip
                classification={header.target.classification}
                label={header.target.classification === 'ISOLATED' ? 'Isolated' : 'Integrated'}
                caveat={header.target.classificationCaveat}
              />
            </>
          ) : (
            <span className={classes.absent}>{unconfiguredMessage(header.readiness)}</span>
          )}

          {/* Present only when known — an unset release doesn't earn permanent space on every tab
              the way the service's identity and target do. Where it materially limits a comparison
              (evidence measured against a different release), that's said where the comparison is. */}
          {header.release && <span className={classes.release}>Release {header.release}</span>}
        </div>
      </div>

      <div className={classes.controls}>
        <ReadinessPill readiness={header.readiness} />
        <RunningIndicator running={header.running} />
        <span className={classes.controlsDivider} aria-hidden="true" />
        <Tooltip label="Edit configuration" openDelay={400} withArrow>
          <ActionIcon
            component="a"
            href={`/services/${header.id}/configuration`}
            variant="subtle"
            color="gray"
            size="lg"
            aria-label="Edit configuration"
            className={classes.editAction}
          >
            <IconPencil size={16} stroke={1.6} />
          </ActionIcon>
        </Tooltip>
        <Tooltip label="Delete service" openDelay={400} withArrow>
          <ActionIcon
            variant="subtle"
            color="fail"
            size="lg"
            aria-label="Delete service"
            className={classes.deleteAction}
            loading={remove.isPending}
            onClick={confirmDelete}
          >
            <IconTrash size={16} stroke={1.6} />
          </ActionIcon>
        </Tooltip>
      </div>
    </header>
  );
}

/**
 * A run is already in flight for this service — only one can be, so there is nothing to offer, only
 * to say. Quiet status text, not a disabled button: it isn't refusing to start anything, it's
 * stating a fact, and every tab under this same header sees it, not only Overview where the running
 * test's own row already shows live progress.
 */
function RunningIndicator({ running }: { running: RunRef | null }) {
  if (!running) return null;

  return (
    <Tooltip label={`${running.testTypeLabel} · ${running.stateLabel}`} openDelay={400} withArrow>
      <span className={classes.runningIndicator}>
        <span className={classes.liveDot} aria-hidden="true" />
        Running {running.testName}
      </span>
    </Tooltip>
  );
}

/** Display only — the scheme is implied by context here, never stripped from an actual link. */
function stripScheme(url: string): string {
  return url.replace(/^https?:\/\//, '');
}

/**
 * What to say in place of a target this service doesn't have yet — every tab under this header,
 * onboarding page included, so it should read as progress rather than a missing field. `blockerCount`
 * is the domain's own count of what stands between now and a runnable test; a target-less service
 * always has at least one, so the "ready" fallback only matters if that ever stops being true.
 */
function unconfiguredMessage(readiness: Readiness): string {
  if (readiness.blockerCount === 0) return 'Getting ready for its first experiment';
  const count = readiness.blockerCount;
  return `${count} setup decision${count === 1 ? '' : 's'} away from running`;
}
