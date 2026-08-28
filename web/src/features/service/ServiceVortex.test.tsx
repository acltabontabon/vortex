import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Readiness, ReadinessItem, ServiceHeader as Header } from '../../api/workspace';
import { ServiceVortex } from './ServiceVortex';

/**
 * A fixed-order pipeline, not a settings form — these assert that the sequence is always contract,
 * target, workload, objectives, whatever order they were actually satisfied in; that the "current"
 * step is the first unsatisfied one in that order, not whichever is "most useful"; that a satisfied
 * node shows a real confirmation rather than the question it answered; that a blocked node explains
 * itself and jumps to its prerequisite rather than dead-ending; that production traffic never
 * appears in the mandatory sequence; and that the whole thing transforms once every unavoidable
 * signal is in place. Every call to action is asserted by its `href`, never by mounting a form —
 * this page hands off to the Configuration page rather than restating it.
 */

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

const API = anItem({
  key: 'API_IMPORTED',
  kind: 'ENRICHMENT',
  label: 'API imported',
  requiredToRun: false,
  effectivelyRequired: true,
  nextStep: 'Import an OpenAPI document so Vortex knows which operations exist.',
  href: '/services/checkout/configuration#operations',
});
const TARGET = anItem();
const WORKLOAD = anItem({
  key: 'WORKLOAD',
  label: 'Workload defined',
  available: false,
  blockedBy: ['API_IMPORTED'],
  blockedReason:
    'A workload spreads traffic across the things a service can do, so Vortex has to know what '
    + 'those are first.',
  nextStep: 'Describe a workload to apply — one operation is enough to start.',
  href: '/services/checkout/configuration#workload',
});
const OBJECTIVES = anItem({
  key: 'OBJECTIVES',
  label: 'Objectives configured',
  requiredToRun: false,
  effectivelyRequired: true,
  nextStep: 'State the latency and error objectives this service is expected to meet.',
  href: '/services/checkout/configuration#objectives',
});
const PRODUCTION = anItem({
  key: 'PRODUCTION_TRAFFIC',
  label: 'Production traffic recorded',
  requiredToRun: false,
  effectivelyRequired: false,
  nextStep: 'Record what the service actually receives.',
  href: '/services/checkout/configuration#production',
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

function aReadiness(items: ReadinessItem[]): Readiness {
  const satisfied = items.filter((item) => item.satisfied).length;
  return {
    canRun: items.every((item) => item.satisfied || !item.requiredToRun),
    satisfiedCount: satisfied,
    totalCount: items.length,
    blockerCount: items.filter((item) => item.requiredToRun && !item.satisfied).length,
    nextStepText: null,
    items,
  };
}

function aHeader(items: ReadinessItem[], overrides: Partial<Header> = {}): Header {
  return {
    id: 'checkout',
    name: 'checkout',
    description: null,
    target: null,
    environmentCount: 0,
    release: null,
    readiness: aReadiness(items),
    operationCount: 0,
    testCount: 0,
    runCount: 0,
    running: null,
    ...overrides,
  };
}

function render(items: ReadinessItem[], overrides: Partial<Header> = {}) {
  const header = aHeader(items, overrides);
  return renderWithProviders(<ServiceVortex header={header} serviceId="checkout" />);
}

describe('the "prepare your first experiment" pipeline', () => {
  it('lists the mandatory sequence in the fixed, domain-independent order', () => {
    // Listed here in scrambled order — the pipeline's own order never follows the domain array's.
    const { container } = render([OBJECTIVES, WORKLOAD, TARGET, API]);

    // API is unsatisfied and first in the fixed order, so it holds the stage — its content is the
    // active card's own copy, not its bare label, unlike the three compact rows behind it.
    const steps = Array.from(container.querySelectorAll('li[data-status]')).map((li) => li.textContent);
    expect(steps[0]).toContain('What can this service actually do?');
    expect(steps[1]).toContain('Environment configured');
    expect(steps[2]).toContain('Workload defined');
    expect(steps[3]).toContain('Objectives configured');
  });

  it('never lists production traffic among the mandatory sequence', () => {
    render([API, TARGET, WORKLOAD, OBJECTIVES, PRODUCTION]);

    const pipeline = screen.getByRole('list');
    expect(within(pipeline).queryByRole('button', { name: /Production traffic/ })).not.toBeInTheDocument();
    // It still renders, just outside the mandatory list.
    expect(screen.getByRole('button', { name: /Production traffic/ })).toBeInTheDocument();
  });

  it('never offers to configure a result, because nobody configures one', () => {
    render([API, TARGET, RESULT]);

    expect(screen.queryByRole('button', { name: /Test executed/ })).not.toBeInTheDocument();
  });

  it('holds the stage on the first unsatisfied signal in fixed order, even if a later one is already done', () => {
    // OBJECTIVES (rank 3) is satisfied but TARGET (rank 1) is not — the fixed order still wins.
    render([anItem({ ...API, satisfied: true }), TARGET, anItem({ ...OBJECTIVES, satisfied: true })]);

    expect(screen.getByRole('heading', { name: 'Where should the traffic go?' })).toBeInTheDocument();
  });

  it('advances to the next unsatisfied signal once the current one is satisfied', () => {
    render([anItem({ ...API, satisfied: true }), TARGET]);

    expect(screen.getByRole('heading', { name: 'Where should the traffic go?' })).toBeInTheDocument();
  });

  it('brings a step forward on click, whatever its status', async () => {
    render([API, TARGET, WORKLOAD, OBJECTIVES]);

    await userEvent.click(screen.getByRole('button', { name: 'Objectives configured' }));

    expect(screen.getByRole('heading', { name: 'What counts as acceptable?' })).toBeInTheDocument();
  });

  it('shows a satisfied signal as a confirmation with the domain\'s own figures, not its question', async () => {
    render(
      [anItem({ ...API, satisfied: true }), TARGET],
      { operationCount: 23 },
    );

    await userEvent.click(screen.getByRole('button', { name: /^API imported/ }));

    expect(screen.queryByText('What can this service actually do?')).not.toBeInTheDocument();
    expect(screen.getByText('23 operations discovered.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /View/ })).toHaveAttribute(
      'href',
      '/services/checkout/configuration#operations',
    );
  });

  /*
   * Required, done and possible are three separate questions. A workload with no imported API is
   * required and impossible at once, and a screen that renders only two of the three either greys
   * out the thing somebody came for or offers a form that cannot be filled in.
   */
  it('marks a blocked signal without disabling it, and explains why', async () => {
    render([API, TARGET, WORKLOAD]);

    const workload = screen.getByRole('button', { name: 'Workload defined, not available yet' });
    expect(workload).not.toBeDisabled();
    expect(workload).not.toHaveAttribute('aria-disabled');

    await userEvent.click(workload);
    expect(screen.getByText(/^A workload spreads traffic across the things a service can do/)).toBeInTheDocument();
  });

  it('jumps a blocked signal straight to its prerequisite rather than dead-ending', async () => {
    render([API, TARGET, WORKLOAD]);

    await userEvent.click(screen.getByRole('button', { name: /^Workload defined/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Do that first' }));

    expect(screen.getByRole('heading', { name: 'What can this service actually do?' })).toBeInTheDocument();
  });

  it('hands the CTA off to the real configuration experience rather than mounting a form', () => {
    render([API, TARGET]);

    expect(screen.getByRole('link', { name: /Import OpenAPI/ })).toHaveAttribute(
      'href',
      '/services/checkout/configuration#operations',
    );
    // Nothing that looks like a live form field belongs on this page.
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('never blocks or gates on the optional production-traffic signal', () => {
    render([anItem({ ...API, satisfied: true }), anItem({ ...TARGET, satisfied: true }), anItem({ ...WORKLOAD, available: true, satisfied: true }), anItem({ ...OBJECTIVES, satisfied: true })]);

    expect(screen.getByText('Vortex has what it needs.')).toBeInTheDocument();
  });

  it('shows the readiness panel grouped into known, still needed and optional', () => {
    render([anItem({ ...API, satisfied: true }), TARGET, WORKLOAD, OBJECTIVES, PRODUCTION]);

    const panel = screen.getByLabelText('Service readiness');
    expect(within(panel).getByText('Known')).toBeInTheDocument();
    expect(within(panel).getByText('API imported')).toBeInTheDocument();
    expect(within(panel).getByText('Still needed')).toBeInTheDocument();
    expect(within(panel).getByText('Optional')).toBeInTheDocument();
    expect(within(panel).getByText('Production traffic recorded')).toBeInTheDocument();
  });

  it('transforms the readiness panel once every mandatory signal is satisfied', () => {
    render([
      anItem({ ...API, satisfied: true }),
      anItem({ ...TARGET, satisfied: true }),
      anItem({ ...WORKLOAD, available: true, satisfied: true }),
      anItem({ ...OBJECTIVES, satisfied: true }),
    ]);

    const panel = screen.getByLabelText('Service readiness');
    expect(within(panel).getByText('Ready for an experiment')).toBeInTheDocument();
    expect(within(panel).getByRole('link', { name: /Run first test/ })).toHaveAttribute(
      'href',
      '/services/checkout?compose=new',
    );
  });

  it('never treats an unsatisfied optional signal as a reason the panel is not ready', () => {
    render([
      anItem({ ...API, satisfied: true }),
      anItem({ ...TARGET, satisfied: true }),
      anItem({ ...WORKLOAD, available: true, satisfied: true }),
      anItem({ ...OBJECTIVES, satisfied: true }),
      PRODUCTION,
    ]);

    expect(within(screen.getByLabelText('Service readiness')).getByText('Ready for an experiment')).toBeInTheDocument();
  });

  it('never dresses "not yet" as a problem', () => {
    render([API, TARGET]);

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/need attention/)).not.toBeInTheDocument();
    expect(screen.queryByText(/incomplete/i)).not.toBeInTheDocument();
  });
});
