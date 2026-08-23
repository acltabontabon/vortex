import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Readiness, ReadinessItem } from '../../api/workspace';
import { ServiceVortex } from './ServiceVortex';

/**
 * The funnel is decoration; the signals around it are the component. These assert the signals —
 * that each open one is a control that configures it without leaving the page, that the domain's
 * own three kinds decide which ring it sits on, and that what has just been satisfied is drawn in
 * rather than simply vanishing. The drawer's contents are the Configuration sections' own tests.
 */

let reducedMotion = false;

vi.mock('motion/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('motion/react')>();
  return { ...actual, useReducedMotion: () => reducedMotion };
});

// The drawer mounts real configuration forms, which fetch. Those are their own files' business —
// here it only matters that the right one was asked for, and that nothing navigated.
vi.mock('./SignalDrawer', () => ({
  SignalDrawer: ({ item, opened }: { item: ReadinessItem | null; opened: boolean }) =>
    opened && item ? (
      <div data-testid="signal-drawer" data-available={item.available ? 'true' : 'false'}>
        {item.label}
      </div>
    ) : null,
}));

beforeEach(() => {
  reducedMotion = false;
});

function anItem(overrides: Partial<ReadinessItem> = {}): ReadinessItem {
  return {
    key: 'ENVIRONMENT',
    kind: 'REQUIRED',
    label: 'Environment configured',
    satisfied: false,
    requiredToRun: true,
    effectivelyRequired: true,
    available: true,
    distinct: true,
    blockedBy: [],
    blockedReason: null,
    nextStep: 'Add a target so Vortex knows where to send traffic.',
    href: '/services/checkout/configuration#environments',
    ...overrides,
  };
}

const REQUIRED = anItem();
// Genuinely optional: an answer survives without a production baseline, it is just weaker.
const OPTIONAL = anItem({
  key: 'PRODUCTION_TRAFFIC',
  kind: 'ENRICHMENT',
  label: 'Production traffic recorded',
  requiredToRun: false,
  effectivelyRequired: false,
  nextStep: 'Record what the service actually receives.',
  href: '/services/checkout/configuration#production',
});
// Enrichment by kind, unavoidable in fact — the workload below cannot be defined without it.
const CATALOG = anItem({
  key: 'API_IMPORTED',
  kind: 'ENRICHMENT',
  label: 'API imported',
  requiredToRun: false,
  effectivelyRequired: true,
  nextStep: 'Import an OpenAPI document so Vortex knows which operations exist.',
  href: '/services/checkout/configuration#operations',
});
const BLOCKED_WORKLOAD = anItem({
  key: 'WORKLOAD',
  label: 'Workload defined',
  available: false,
  blockedBy: ['API_IMPORTED'],
  blockedReason:
    'A workload spreads traffic across the things a service can do, so Vortex has to know what '
    + 'those are first.',
  nextStep: 'Describe a workload to apply — one operation is enough to start.',
});
const RESULT = anItem({
  key: 'TEST_EXECUTED',
  kind: 'RESULT',
  label: 'Test executed',
  requiredToRun: false,
  effectivelyRequired: false,
  nextStep: 'Run a smoke test to confirm Vortex can reach your service.',
  href: '/services/checkout/tests',
});

function aReadiness(items: ReadinessItem[], overrides: Partial<Readiness> = {}): Readiness {
  const satisfied = items.filter((item) => item.satisfied).length;
  return {
    canRun: items.every((item) => item.satisfied || !item.requiredToRun),
    satisfiedCount: satisfied,
    totalCount: items.length,
    blockerCount: items.filter((item) => item.requiredToRun && !item.satisfied).length,
    nextStepText: 'Add a target so Vortex knows where to send traffic.',
    items,
    ...overrides,
  };
}

function render(items: ReadinessItem[]) {
  return renderWithProviders(<ServiceVortex readiness={aReadiness(items)} serviceId="checkout" />);
}

describe('the service vortex', () => {
  it('offers every open signal as a control rather than a way off the page', async () => {
    render([REQUIRED, OPTIONAL]);

    expect(screen.getAllByRole('button')).toHaveLength(2);
    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });

  it('opens the configuration for the signal that was clicked, in place', async () => {
    render([REQUIRED, OPTIONAL]);

    await userEvent.click(screen.getByRole('button', { name: /^Production traffic recorded/ }));

    expect(screen.getByTestId('signal-drawer')).toHaveTextContent('Production traffic recorded');
  });

  /*
   * State belongs in the name, the reason in the description. The tooltip carries the reason to
   * everyone — it is linked by `aria-describedby` and, unlike Mantine's default, opens on focus —
   * so putting it in the name too would have a screen reader say the sentence twice.
   */
  it('says in a blocked signal\'s own name that it is not possible yet', () => {
    render([BLOCKED_WORKLOAD, CATALOG]);

    expect(
      screen.getByRole('button', { name: 'Workload defined, not available yet' }),
    ).toBeInTheDocument();
  });

  /*
   * The domain classifies an OpenAPI import as enrichment because it does not itself gate a run.
   * On this screen that is beside the point: the required workload cannot be defined without it, so
   * calling it optional here would be telling the reader something untrue.
   */
  it('never calls a signal optional when a required one cannot be done without it', () => {
    render([BLOCKED_WORKLOAD, CATALOG, OPTIONAL]);

    const catalog = screen.getByRole('button', { name: 'API imported' });
    expect(within(catalog).queryByText('optional')).not.toBeInTheDocument();
    expect(catalog).toHaveAttribute('data-required', 'true');

    const objectives = screen.getByRole('button', { name: /^Production traffic recorded/ });
    expect(within(objectives).getByText('optional')).toBeInTheDocument();
  });

  /*
   * The hierarchy is on the nodes, not in a second ring: two rings of different radii read as
   * scattered points rather than as an orbit, which is the one thing the figure has to say.
   */
  it('marks what cannot be avoided as such, whatever its kind, without a second ring', () => {
    render([BLOCKED_WORKLOAD, CATALOG, OPTIONAL]);

    expect(screen.getAllByRole('list')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'API imported' })).toHaveAttribute(
      'data-required',
      'true',
    );
    expect(
      screen.getByRole('button', { name: /^Production traffic recorded/ }),
    ).not.toHaveAttribute('data-required');
  });

  it("keeps the ring in the domain's own order, so tab order follows it", () => {
    render([REQUIRED, OPTIONAL, RESULT]);

    const ring = screen.getByRole('list', { name: 'Signals this service still needs' });
    expect(
      within(ring)
        .getAllByRole('button')
        .map((button) => button.textContent?.replace(/optional/, '').trim()),
    ).toEqual(['Environment configured', 'Production traffic recorded']);
  });

  it('never offers to configure a result, because nobody configures one', () => {
    render([REQUIRED, RESULT]);

    expect(screen.queryByRole('button', { name: /Test executed/ })).not.toBeInTheDocument();
  });

  it('marks an advisory signal in the same word the readiness checklist uses', () => {
    render([REQUIRED, OPTIONAL]);

    const advisory = screen.getByRole('button', { name: /^Production traffic recorded/ });
    expect(within(advisory).getByText('optional')).toBeInTheDocument();

    const required = screen.getByRole('button', { name: /^Environment configured/ });
    expect(within(required).queryByText('optional')).not.toBeInTheDocument();
  });

  it('offers nothing to click for something already in place', () => {
    render([anItem({ satisfied: true }), OPTIONAL]);

    expect(screen.queryByRole('button', { name: /^Environment configured/ })).not.toBeInTheDocument();
  });

  it('draws a newly satisfied signal into the funnel before dropping it', async () => {
    vi.useFakeTimers();
    try {
      const { rerender, container } = renderWithProviders(
        <ServiceVortex readiness={aReadiness([REQUIRED, OPTIONAL])} serviceId="checkout" />,
      );

      rerender(
        <ServiceVortex
          readiness={aReadiness([anItem({ satisfied: true }), OPTIONAL])}
          serviceId="checkout"
        />,
      );

      // Still on screen, and being consumed — not simply gone the instant the server said so.
      expect(container.querySelector('[data-consuming="true"]')).toBeInTheDocument();
      expect(container.querySelector('[data-vortex-swirl]')).toHaveAttribute('data-disturbed', 'true');

      await act(async () => {
        vi.advanceTimersByTime(1000);
      });

      expect(container.querySelector('[data-consuming="true"]')).not.toBeInTheDocument();
      expect(
        screen.queryByRole('button', { name: /^Environment configured/ }),
      ).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('never replays what was already in place when the screen opened', () => {
    const { container } = renderWithProviders(
      <ServiceVortex readiness={aReadiness([anItem({ satisfied: true })])} serviceId="checkout" />,
    );

    expect(container.querySelector('[data-consuming="true"]')).not.toBeInTheDocument();
  });

  it('drops a satisfied signal outright when motion is not wanted, and still says so', async () => {
    reducedMotion = true;
    const { rerender, container } = renderWithProviders(
      <ServiceVortex readiness={aReadiness([REQUIRED, OPTIONAL])} serviceId="checkout" />,
    );

    rerender(
      <ServiceVortex
        readiness={aReadiness([anItem({ satisfied: true }), OPTIONAL])}
        serviceId="checkout"
      />,
    );

    await waitFor(() => {
      expect(container.querySelector('[data-reduced-motion="true"]')).toBeInTheDocument();
    });
    expect(container.querySelector('[data-consuming="true"]')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Environment configured/ })).not.toBeInTheDocument();
  });

  /*
   * The orbits are two `inset: 0` boxes stacked on each other, so without `pointer-events: none` the
   * upper one covers the whole stage and swallows every click aimed at a signal in the lower one —
   * which is exactly what happened to the required ring. Nothing in jsdom hit-tests, and `userEvent`
   * dispatches straight at the node, so no interaction test can catch this; the CSS contract is what
   * there is to assert.
   */
  /*
   * The ring is an `inset: 0` box covering the whole stage. Without `pointer-events: none` it
   * swallows clicks aimed at anything drawn under it — which is exactly what happened when there
   * were two of these stacked on each other and the upper one ate every click meant for the lower.
   * Nothing in jsdom hit-tests, and `userEvent` dispatches straight at the node, so no interaction
   * test can catch that; the CSS contract is what there is to assert.
   */
  it('lets a click through the ring box to the node itself', () => {
    const { container } = render([REQUIRED, OPTIONAL]);

    const rim = container.querySelector('ul[aria-label]');
    expect(getComputedStyle(rim!).pointerEvents).toBe('none');

    const signal = screen.getByRole('button', { name: /^Environment configured/ });
    expect(getComputedStyle(signal).pointerEvents).toBe('auto');
  });

  /*
   * Required, done and possible are three separate questions. A workload with no imported API is
   * required and impossible at once, and a screen that renders only two of the three either greys
   * out the thing somebody came for or offers a form that cannot be filled in.
   */
  it('marks a signal blocked without disabling it or taking it out of reach', () => {
    render([BLOCKED_WORKLOAD, CATALOG]);

    const workload = screen.getByRole('button', { name: /^Workload defined/ });
    expect(workload).toHaveAttribute('data-state', 'blocked');
    // Never dressed as disabled: it opens a real explanation, so it stays an ordinary, focusable,
    // clickable button. Distance and stillness say it is not reachable yet; the name says why.
    expect(workload).not.toBeDisabled();
    expect(workload).not.toHaveAttribute('aria-disabled');
    expect(workload).toHaveAttribute('data-required', 'true');
  });

  it("reaches the domain's reason from the keyboard, not only from a pointer", async () => {
    render([BLOCKED_WORKLOAD, CATALOG]);

    const workload = screen.getByRole('button', { name: /^Workload defined/ });
    await act(async () => {
      workload.focus();
    });

    await waitFor(() =>
      expect(
        screen.getByText(/^A workload spreads traffic across the things a service can do/),
      ).toBeInTheDocument(),
    );
    // Linked as the control's description rather than left as loose text beside it.
    expect(workload).toHaveAttribute('aria-describedby');
  });

  it('names the prerequisite only while the signal waiting on it is being asked about', async () => {
    render([BLOCKED_WORKLOAD, CATALOG]);

    const catalog = screen.getByRole('button', { name: /^API imported/ });
    expect(catalog).not.toHaveAttribute('data-prerequisite');

    await userEvent.hover(screen.getByRole('button', { name: /^Workload defined/ }));
    expect(catalog).toHaveAttribute('data-prerequisite', 'true');

    await userEvent.unhover(screen.getByRole('button', { name: /^Workload defined/ }));
    expect(catalog).not.toHaveAttribute('data-prerequisite');
  });

  /*
   * Explanation on demand, attached to the thing being explained — not a line under the funnel that
   * appears and disappears as the pointer crosses the composition.
   */
  it('explains a signal on its own node, and nothing until it is asked', async () => {
    render([BLOCKED_WORKLOAD, CATALOG]);

    expect(screen.queryByText(/A workload spreads traffic/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Import an OpenAPI document/)).not.toBeInTheDocument();

    await userEvent.hover(screen.getByRole('button', { name: /^API imported/ }));
    await waitFor(() =>
      expect(
        screen.getByText('Import an OpenAPI document so Vortex knows which operations exist.'),
      ).toBeInTheDocument(),
    );

    // A blocked signal explains why it cannot be done, not what doing it would achieve.
    await userEvent.unhover(screen.getByRole('button', { name: /^API imported/ }));
    await userEvent.hover(screen.getByRole('button', { name: /^Workload defined/ }));
    await waitFor(() =>
      expect(
        screen.getByText(/^A workload spreads traffic across the things a service can do/),
      ).toBeInTheDocument(),
    );
  });

  it('opens a blocked signal to the explanation rather than to a form it cannot fill in', async () => {
    render([BLOCKED_WORKLOAD, CATALOG]);

    await userEvent.click(screen.getByRole('button', { name: /^Workload defined/ }));

    const drawer = screen.getByTestId('signal-drawer');
    expect(drawer).toHaveTextContent('Workload defined');
    expect(drawer).toHaveAttribute('data-available', 'false');
  });

  it('moves a signal into the orbit when what it was waiting on is configured', async () => {
    vi.useFakeTimers();
    try {
      const { rerender, container } = renderWithProviders(
        <ServiceVortex readiness={aReadiness([BLOCKED_WORKLOAD, CATALOG])} serviceId="checkout" />,
      );
      expect(container.querySelector('[data-entering="true"]')).not.toBeInTheDocument();

      rerender(
        <ServiceVortex
          readiness={aReadiness([
            anItem({ ...BLOCKED_WORKLOAD, available: true, blockedBy: [], blockedReason: null }),
            anItem({ ...CATALOG, satisfied: true }),
          ])}
          serviceId="checkout"
        />,
      );

      expect(container.querySelector('[data-entering="true"]')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /^Workload defined/ })).toHaveAttribute(
        'data-state',
        'available',
      );

      await act(async () => {
        vi.advanceTimersByTime(800);
      });
      expect(container.querySelector('[data-entering="true"]')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps the funnel out of the accessibility tree', () => {
    const { container } = render([REQUIRED]);

    expect(container.querySelector('[data-vortex-swirl]')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.queryAllByRole('img')).toHaveLength(0);
  });

  it('renders without a ring when the domain listed no items', () => {
    render([]);

    expect(screen.getByRole('heading', { name: 'Nothing to measure yet' })).toBeInTheDocument();
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('never dresses "not yet" as a problem', () => {
    render([REQUIRED]);

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/in place/)).not.toBeInTheDocument();
    expect(screen.queryByText(/need attention/)).not.toBeInTheDocument();
  });
});
