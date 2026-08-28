import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { Configuration } from '../../api/configuration';
import type { Readiness, ReadinessItem, ServiceHeader as Header } from '../../api/workspace';
import { ServiceVortex } from './ServiceVortex';

/**
 * A fixed-order pipeline, not a settings form — these assert that the sequence is always contract,
 * target, workload, objectives, reality, whatever order they were actually satisfied in; that the
 * "current" step is the first unsatisfied one in that order, not whichever is "most useful"; that a
 * satisfied node shows a real confirmation rather than the question it answered; that a blocked node
 * explains itself and jumps to its prerequisite rather than dead-ending; that production traffic
 * gates readiness like the rest of the mandatory sequence now that it is `Kind.GROUNDING`, while an
 * average-load workload — genuinely nice-to-have, not unavoidable — is what still renders as the
 * optional branch; and that the whole thing transforms once every unavoidable signal is in place.
 * Most calls to action are asserted by their `href`, never by mounting a form — this page hands off
 * to the Configuration page rather than restating it. The exceptions are API_IMPORTED, ENVIRONMENT,
 * OBJECTIVES and PRODUCTION_TRAFFIC: each of those forms is small enough that a scoped Drawer beats
 * a full page hand-off, so those CTAs are asserted as opening a drawer instead. WORKLOAD is
 * deliberately not one of them — its composer is a page-sized form, not a quick-add one — so it
 * keeps navigating to its own page.
 */

let configQueryResult: { data: Configuration | undefined } = { data: undefined };

vi.mock('../../api/configuration', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/configuration')>();
  return { ...actual, useConfigurationQuery: () => configQueryResult };
});

function aConfiguration(overrides: Partial<Configuration> = {}): Configuration {
  return {
    name: 'checkout',
    serviceVersion: null,
    environments: [],
    environmentTypes: [{ name: 'LOCAL_ISOLATED', label: 'Local (isolated)', description: '' }],
    dependencyModes: [{ name: 'MOCKED', label: 'Mocked', description: '' }],
    localLab: {
      configured: false,
      composeFileDisplay: null,
      status: { usable: true, dockerAvailable: true, daemonRunning: true, composeAvailable: true, version: '', remedy: '' },
      running: false,
      activity: null,
    },
    production: null,
    calibrationSuggestions: [],
    observationSource: null,
    thresholds: { p95Millis: null, p99Millis: null, errorPercent: null, describe: [] },
    catalog: { imported: false, title: null, sourceRef: null, operationCount: 0, mutatingCount: 0, operations: [] },
    file: { yaml: '', path: null },
    ...overrides,
  };
}

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
  kind: 'GROUNDING',
  label: 'Production traffic recorded',
  requiredToRun: false,
  effectivelyRequired: true,
  nextStep: 'Record what the service actually receives.',
  href: '/services/checkout/configuration#production',
});
// The one item still genuinely optional after production traffic became required — narrows
// WORKLOAD, so `distinct` only goes true once a workload already exists.
const AVERAGE_LOAD = anItem({
  key: 'AVERAGE_LOAD_WORKLOAD',
  kind: 'ENRICHMENT',
  label: 'Average-load workload defined',
  requiredToRun: false,
  effectivelyRequired: false,
  nextStep: 'Describe the traffic your service normally receives.',
  href: '/services/checkout/configuration#workload',
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
    const { container } = render([PRODUCTION, OBJECTIVES, WORKLOAD, TARGET, API]);

    // API is unsatisfied and first in the fixed order, so it holds the stage — its content is the
    // active card's own copy, not its bare label, unlike the four compact rows behind it.
    const steps = Array.from(container.querySelectorAll('li[data-status]')).map((li) => li.textContent);
    expect(steps[0]).toContain('What can this service actually do?');
    expect(steps[1]).toContain('Environment configured');
    expect(steps[2]).toContain('Workload defined');
    expect(steps[3]).toContain('Objectives configured');
    expect(steps[4]).toContain('Production traffic recorded');
  });

  it('lists production traffic among the mandatory sequence, last', () => {
    render([API, TARGET, WORKLOAD, OBJECTIVES, PRODUCTION]);

    const pipeline = screen.getByRole('list');
    expect(within(pipeline).getByText('Production traffic recorded')).toBeInTheDocument();
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

    // Nothing that looks like a live form field belongs on this page until asked for.
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('opens a scoped drawer to import the API rather than navigating away', async () => {
    render([API, TARGET]);

    const cta = screen.getByRole('button', { name: /Import OpenAPI/ });
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();

    await userEvent.click(cta);

    expect(await screen.findByRole('tab', { name: 'From a URL' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Paste it' })).toBeInTheDocument();
  });

  it('opens a scoped drawer to add an environment rather than navigating away', async () => {
    configQueryResult = { data: aConfiguration() };
    render([anItem({ ...API, satisfied: true }), TARGET]);

    await userEvent.click(screen.getByRole('button', { name: /Add a target/ }));

    expect(await screen.findByLabelText('Name')).toBeInTheDocument();
  });

  it('opens a scoped drawer to set objectives rather than navigating away', async () => {
    configQueryResult = { data: aConfiguration() };
    render([anItem({ ...API, satisfied: true }), anItem({ ...TARGET, satisfied: true }), OBJECTIVES]);

    await userEvent.click(screen.getByRole('button', { name: /Set objectives/ }));

    expect(await screen.findByLabelText(/p95 latency \(ms\)/)).toBeInTheDocument();
  });

  it('opens a scoped drawer to record production traffic rather than navigating away', async () => {
    configQueryResult = { data: aConfiguration() };
    render([API, TARGET, PRODUCTION]);

    await userEvent.click(screen.getByRole('button', { name: /Production traffic/ }));
    await userEvent.click(screen.getByRole('button', { name: /Record production traffic/ }));

    expect(await screen.findByLabelText(/Peak rate \(req\/sec\)/)).toBeInTheDocument();
  });

  it('withholds "ready" until production traffic is recorded too, now that it is required', () => {
    render([
      anItem({ ...API, satisfied: true }),
      anItem({ ...TARGET, satisfied: true }),
      anItem({ ...WORKLOAD, available: true, satisfied: true }),
      anItem({ ...OBJECTIVES, satisfied: true }),
      PRODUCTION,
    ]);

    expect(screen.queryByText('Vortex has what it needs.')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'What does reality look like?' })).toBeInTheDocument();
  });

  it('shows the readiness panel grouped into known, still needed and optional', () => {
    render([
      anItem({ ...API, satisfied: true }),
      TARGET,
      anItem({ ...WORKLOAD, available: true, satisfied: true }),
      OBJECTIVES,
      PRODUCTION,
      AVERAGE_LOAD,
    ]);

    const panel = screen.getByLabelText('Service readiness');
    expect(within(panel).getByText('Known')).toBeInTheDocument();
    expect(within(panel).getByText('API imported')).toBeInTheDocument();
    expect(within(panel).getByText('Still needed')).toBeInTheDocument();
    expect(within(panel).getByText('Production traffic recorded')).toBeInTheDocument();
    expect(within(panel).getByText('Optional')).toBeInTheDocument();
    expect(within(panel).getByText('Average-load workload defined')).toBeInTheDocument();
  });

  it('transforms the readiness panel once every mandatory signal is satisfied', () => {
    render([
      anItem({ ...API, satisfied: true }),
      anItem({ ...TARGET, satisfied: true }),
      anItem({ ...WORKLOAD, available: true, satisfied: true }),
      anItem({ ...OBJECTIVES, satisfied: true }),
      anItem({ ...PRODUCTION, satisfied: true }),
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
      anItem({ ...PRODUCTION, satisfied: true }),
      AVERAGE_LOAD,
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
