import { useEffect, useRef, useState } from 'react';

/**
 * Tiny terminal-flavour status text shown underneath a live execution's own telemetry — never a
 * notification, never load-bearing, always safe to ignore. Dry SRE/performance-engineering register
 * on purpose: Vortex is a serious instrument that also happens to know exactly what it's doing to
 * someone's service right now.
 *
 * <p>Kept as plain data, not markup or JSX, so nothing about presentation (crossfade, motion,
 * `prefers-reduced-motion`) leaks into this list, and nothing about this list leaks into the
 * execution model it sits beside — {@link useRunningCommentary} is the only thing that reads it.
 */
export const RUNNING_COMMENTARY_LINES: readonly string[] = [
  'Boss requires performance testing. Here we go again.',
  'Production said it was fine.',
  'Works on my machine has entered the chat.',
  'Sending requests until something develops character.',
  'Finding the boundary between scalable and optimistic.',
  'Latency is just distributed systems asking for patience.',
  'CPU remains confident. Suspiciously confident.',
  'Memory usage has chosen personal growth.',
  'Somewhere, a connection pool is getting nervous.',
  'Another perfectly innocent request meets reality.',
  'The architecture diagram promised this would scale.',
  'Turning assumptions into measurements.',
  'Trusting nothing. Measuring everything.',
  'The happy path is currently under investigation.',
  'Production traffic sends its regards.',
  'Increasing load. Decreasing confidence.',
  'Somewhere, an autoscaler just woke up.',
  'p99 is where the bodies are buried.',
  'Average latency would prefer we didn’t look at p99.',
  'The database has noticed us.',
  'Kubernetes is probably scheduling another pod about this.',
  'Waiting for horizontal scaling to discover motivation.',
  'Requests are currently being professionally unreasonable.',
  'This endpoint looked confident five minutes ago.',
  'Searching for the exact throughput where excuses begin.',
  'Performance testing: because hope is not an SLO.',
  '“It should handle it” is currently being peer reviewed.',
  'The load balancer has been informed.',
  'Cache hit ratio, don’t fail us now.',
  'One does not simply ignore tail latency.',
  'The service is learning what “concurrent” means.',
  'Somewhere, a thread pool regrets its configuration.',
  'Benchmark first. Explain later.',
  'Converting architectural confidence into graphs.',
  'The SLA is watching.',
  'No mocks were harmed. Only the real thing.',
  'Sending traffic with scientific intent.',
  'Seeing what the architecture review missed.',
  'Every system scales until it doesn’t.',
  'Forecast: rising load, chance of timeouts.',
  'The network insists this is somebody else’s problem.',
  'Measuring the distance between “should” and “does.”',
  'The garbage collector has joined the call.',
  'Heap usage appears emotionally invested.',
  'Asking the API increasingly uncomfortable questions.',
  'This is fine. Metrics pending.',
  'The bottleneck knows we’re coming.',
  'Autoscaling is not a personality trait.',
  'More replicas cannot fix every life decision.',
  'The connection pool requests representation.',
  'Queue depth is beginning to express itself.',
  'Finding out which dependency was secretly synchronous.',
  'Distributed systems: now with distributed blame.',
  'Someone said “stateless.” Verifying.',
  'The service mesh would like some attention too.',
  'Raising concurrency because slides are not evidence.',
  'If it survives, load goes up.',
  'If it fails, we call it data.',
  'Either way, the graph gets interesting.',
  'The breakpoint is out there.',
  'Hunting capacity one request at a time. Mostly thousands.',
  'The error budget is watching from a safe distance.',
  'On-call has not been paged. Yet.',
  'The pager remains peacefully unaware.',
  'A production incident, simulated, with consent.',
  'Better here than Friday at 5 PM.',
  'Friday-deploy energy, in a controlled environment.',
  'Finding out what “production ready” actually meant.',
  'Confidence is temporary. Measurements are timestamped.',
  'The service appears healthy. Continuing to apply pressure.',
  'Nothing has exploded yet. Raising expectations.',
  'Latency remains civilized, for now.',
  'Error rate remains suspiciously polite.',
  'Throughput target acquired.',
  'Holding load. Judging silently.',
  'Waiting for the system to reveal its secrets.',
  'The API is currently defending its architecture.',
  'Threads are being allocated with purpose.',
  'Sockets are doing socket things.',
  'TCP remains committed to the relationship.',
  'DNS has one job. Vigilance continues.',
  'TLS handshakes, quietly negotiating as ever.',
  'Another millisecond, professionally accounted for.',
  'Observability is cheaper than guessing.',
  'Metrics before opinions.',
  'Graphs before war rooms.',
  'Data before architecture debates.',
  '“Probably fine” is not a benchmark.',
  '“We tested it manually” has left the building.',
  'A single successful request is not a load test.',
  'One request succeeded. A few more are still needed.',
  'The endpoint passed the demo. Now comes adulthood.',
  'Demo traffic and production traffic are different species.',
  'The service has entered the finding-out phase.',
  'Applying scientifically calibrated disrespect.',
  'Respectfully overwhelming this API.',
  'Generating evidence for the next architecture meeting.',
  'Someone is about to blame the network.',
  'Asking impolite but measurable questions.',
  'The bottleneck cannot hide forever.',
  'Tail latency has been asked to identify itself.',
  'Everything is fast at zero requests per second.',
  'Scaling theory is now scaling practice.',
  'The dashboard is about to earn its keep.',
  'Running until the assumptions become numbers.',
  'Load increasing. PowerPoint confidence decreasing.',
  'Bringing telemetry to an opinion fight.',
  'Architecture by measurement, not mythology.',
  'This endpoint’s résumé says “highly scalable.” Checking.',
  'A retry storm is forming somewhere. It always is.',
  'Idempotency is being tested whether it likes it or not.',
  'The rate limiter has started paying attention.',
  'Backpressure: nature’s way of saying slow down.',
  'The circuit breaker is reviewing its options.',
  'Somewhere a health check is lying with confidence.',
  'The p50 is fine. The p50 is always fine.',
  'Concurrency and parallelism remain politely distinct.',
  'A synchronous call is hiding in an async method. Looking.',
  'The load generator has opinions about this API now.',
  'This is not a drill. It is, however, a drill script.',
  'Watching the moment optimism becomes a support ticket.',
  'The service level objective has been formally challenged.',
  'Somewhere a connection is being reused. Somewhere else, not.',
  'The n+1 query has been cordially invited to reveal itself.',
  'Load shedding: the art of choosing who gets disappointed.',
  'The retry logic and the outage are having a conversation.',
  'Capacity planning, but with capacity, and a plan.',
  'A cold start is about to have a very bad time.',
  'The connection limit is closer than the README suggested.',
  'Someone’s favorite timeout value is under review.',
  'The saturation point does not care about the roadmap.',
];

const MIN_INTERVAL_MS = 8000;
const MAX_INTERVAL_MS = 15000;

/**
 * A shuffled sequence of every index into {@link RUNNING_COMMENTARY_LINES}, drawn one at a time.
 * `avoid`, when given, keeps that index out of the first slot of a freshly shuffled bag — the only
 * moment a repeat could otherwise slip in, since within one shuffle every index appears exactly
 * once. Exported on its own, apart from any React/timer concerns, so the no-immediate-repeat and
 * full-coverage guarantees are checkable without fake timers.
 */
export function createCommentaryBag(length: number) {
  let queue: number[] = [];
  let last: number | null = null;

  function refill() {
    queue = shuffledIndices(length, last);
  }

  return {
    next(): number {
      if (queue.length === 0) refill();
      const index = queue.shift() as number;
      last = index;
      return index;
    },
  };
}

function shuffledIndices(length: number, avoid: number | null): number[] {
  const indices = Array.from({ length }, (_, i) => i);
  for (let i = indices.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [indices[i], indices[j]] = [indices[j], indices[i]];
  }
  if (avoid !== null && indices.length > 1 && indices[0] === avoid) {
    const swapWith = 1 + Math.floor(Math.random() * (indices.length - 1));
    [indices[0], indices[swapWith]] = [indices[swapWith], indices[0]];
  }
  return indices;
}

/**
 * The current commentary line, rotating on its own timer for as long as the calling component
 * stays mounted — a caller only ever needs to mount this while a run is actually in flight, so
 * "stop when execution ends" falls out of unmounting rather than needing an `active` flag here.
 *
 * <p>A fresh, randomized delay (8–15s) is drawn for every tick rather than using `setInterval`, so
 * the rotation never lands on a suspiciously round cadence. State lives entirely in this hook's own
 * component instance, so its re-renders never propagate to whatever mounts it.
 */
export function useRunningCommentary(): string {
  // Cheap to construct (it only allocates a shuffled array once `.next()` is first called), so
  // seeding the ref this way — rather than a null-check inside the render body — never reads
  // `.current` during render at all.
  const bagRef = useRef(createCommentaryBag(RUNNING_COMMENTARY_LINES.length));
  const [index, setIndex] = useState(() => bagRef.current.next());

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    function scheduleNext() {
      const delay = MIN_INTERVAL_MS + Math.random() * (MAX_INTERVAL_MS - MIN_INTERVAL_MS);
      timer = setTimeout(() => {
        setIndex(bagRef.current.next());
        scheduleNext();
      }, delay);
    }
    scheduleNext();
    return () => clearTimeout(timer);
  }, []);

  return RUNNING_COMMENTARY_LINES[index];
}
