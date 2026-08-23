import { Text } from '@mantine/core';
import type { Capacity, CapacityRange, TestRow as Test, Verdict } from '../../api/workspace';
import type { AcceptanceResult, RunEvidence } from '../../api/run';
import { Fact, Facts } from '../Fact';
import { VerdictBadge } from '../VerdictBadge';
import { UnknownInline } from '../Unknown';
import { InfoPopover } from '../InfoPopover';

/**
 * Average load's own answer to its own question — "does the service meet its objectives under the
 * traffic it normally receives?" — is a comparison against one known reference, not a boundary
 * hunt. This is deliberately not a scale: {@link CapacityRangeFigure}/{@code EvidenceScale} draw a
 * line with marks on it, which is the right shape for "where does this break" but the wrong shape
 * for "does this one number, at this one level, hold" — a line implies a range worth scanning, and
 * an average-load run was never trying to find one.
 *
 * <p>Built entirely from {@link Fact}/{@link Facts}, the same labelled-row primitive the full run
 * report already uses — not a bespoke component invented for this one kind.
 */
export function LoadSummary({
  test,
  evidence,
}: {
  test: Test;
  evidence: RunEvidence | null | undefined;
}) {
  const production = markerFor(test.range, 'PRODUCTION');
  const tested = markerFor(test.range, 'TESTED_CAPACITY');
  const latency = evidence?.acceptance.results.filter((r) => r.kind === 'LATENCY') ?? [];
  const errorRate = evidence?.acceptance.results.filter((r) => r.kind === 'ERROR_RATE') ?? [];

  return (
    <Facts>
      {production && <Fact label="Production requirement">{production.displayWithUnit}</Fact>}
      {tested && <Fact label="Tested level">{tested.displayWithUnit}</Fact>}
      {test.capacity && <Fact label="Headroom">{headroom(test.capacity)}</Fact>}
      {latency.length > 0 && (
        <Fact label="Latency objective">
          <ObjectiveList results={latency} />
        </Fact>
      )}
      {errorRate.length > 0 && (
        <Fact label="Error rate objective">
          <ObjectiveList results={errorRate} />
        </Fact>
      )}
    </Facts>
  );
}

function markerFor(range: CapacityRange, kind: 'PRODUCTION' | 'TESTED_CAPACITY') {
  return range.markers.find((marker) => marker.kind === kind) ?? null;
}

function headroom(capacity: Capacity) {
  if (capacity.headroom) {
    return <>{capacity.headroom} above production</>;
  }
  return (
    <>
      <UnknownInline>Not established</UnknownInline>{' '}
      <InfoPopover icon ariaLabel="Why headroom is not established" width={340}>
        <Text size="xs">{capacity.headroomRefusal}</Text>
      </InfoPopover>
    </>
  );
}

function ObjectiveList({ results }: { results: AcceptanceResult[] }) {
  return (
    <>
      {results.map((result) => (
        <div key={result.describe}>
          <VerdictBadge verdict={result.verdict as Verdict} label={result.verdictLabel} subtleText />{' '}
          <Text component="span" size="sm">
            {result.describe} — {result.observed}
          </Text>
        </div>
      ))}
    </>
  );
}
