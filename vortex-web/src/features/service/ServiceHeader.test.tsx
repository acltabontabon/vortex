import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { ServiceHeader as Header } from '../../api/workspace';
import { ServiceHeader } from './ServiceHeader';

/**
 * The header is the one canonical place identity, target and classification live — Overview and
 * Evidence never restate them. These assert the header keeps its side of that deal: compact, and
 * honest about what it doesn't know without occupying permanent space to say so.
 */
function aHeader(overrides: Partial<Header> = {}): Header {
  return {
    id: 'checkout',
    name: 'checkout-service',
    description: null,
    target: {
      environmentName: 'local',
      baseUrl: 'http://localhost:8080',
      environmentTypeLabel: 'Local',
      classification: 'ISOLATED',
      classificationLabel: 'Isolated performance test',
      classificationCaveat: 'Dependencies are simulated or controlled.',
      dependencyModeLabel: 'Mocked',
      targetKind: 'EXTERNAL_ENDPOINT',
      targetSummary: 'http://localhost:8080',
    },
    environmentCount: 1,
    release: null,
    readiness: {
      canRun: true,
      satisfiedCount: 7,
      totalCount: 7,
      blockerCount: 0,
      items: [],
      nextStepText: null,
    },
    operationCount: 4,
    testCount: 1,
    runCount: 5,
    running: null,
    ...overrides,
  };
}

describe('the service header', () => {
  it('shows the target without its scheme, and a compact classification label', () => {
    renderWithProviders(<ServiceHeader header={aHeader()} />);

    expect(screen.getByText('localhost:8080')).toBeInTheDocument();
    expect(screen.queryByText('http://localhost:8080')).not.toBeInTheDocument();
    expect(screen.getByText('Isolated')).toBeInTheDocument();
    expect(screen.queryByText('Isolated performance test')).not.toBeInTheDocument();
  });

  it('omits the release line entirely when no release is recorded', () => {
    renderWithProviders(<ServiceHeader header={aHeader({ release: null })} />);

    expect(screen.queryByText('Release not recorded')).not.toBeInTheDocument();
    expect(screen.queryByText(/^Release /)).not.toBeInTheDocument();
  });

  it('states the release only when one is known', () => {
    renderWithProviders(<ServiceHeader header={aHeader({ release: '2.17.0' })} />);

    expect(screen.getByText('Release 2.17.0')).toBeInTheDocument();
  });

  it('carries no run action of its own — each test\'s own row is the one place to press Run', () => {
    renderWithProviders(<ServiceHeader header={aHeader()} />);

    expect(screen.queryByText(/Run/)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Run/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Run/ })).not.toBeInTheDocument();
  });

  it('opens service configuration from a quiet settings action, not a full-size button', () => {
    renderWithProviders(<ServiceHeader header={aHeader()} />);

    expect(screen.getByRole('link', { name: 'Service configuration' })).toHaveAttribute(
      'href',
      '/services/checkout/configuration',
    );
  });

  it('omits the target-summary segment for an external endpoint — baseUrl already says it', () => {
    renderWithProviders(<ServiceHeader header={aHeader()} />);

    expect(screen.queryByText(/^Docker:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Compose:/)).not.toBeInTheDocument();
  });

  it('shows the target summary for a Docker-managed target, whose baseUrl is empty pre-run', () => {
    renderWithProviders(
      <ServiceHeader
        header={aHeader({
          target: {
            environmentName: 'docker-managed',
            baseUrl: '',
            environmentTypeLabel: 'Local',
            classification: 'ISOLATED',
            classificationLabel: 'Isolated performance test',
            classificationCaveat: 'Dependencies are simulated or controlled.',
            dependencyModeLabel: 'Mocked',
            targetKind: 'DOCKER_IMAGE',
            targetSummary: 'Docker: payment-service:1.4.2',
          },
        })}
      />,
    );

    expect(screen.getByText('Docker: payment-service:1.4.2')).toBeInTheDocument();
  });

  it('shows the target summary for a Compose target the same way', () => {
    renderWithProviders(
      <ServiceHeader
        header={aHeader({
          target: {
            environmentName: 'compose-attached',
            baseUrl: '',
            environmentTypeLabel: 'Local',
            classification: 'ISOLATED',
            classificationLabel: 'Isolated performance test',
            classificationCaveat: 'Dependencies are simulated or controlled.',
            dependencyModeLabel: 'Mocked',
            targetKind: 'DOCKER_COMPOSE',
            targetSummary: 'Compose: payment-service (compose.yaml)',
          },
        })}
      />,
    );

    expect(screen.getByText('Compose: payment-service (compose.yaml)')).toBeInTheDocument();
  });

  it('shows "No target configured" when no environment is set up yet', () => {
    renderWithProviders(<ServiceHeader header={aHeader({ target: null })} />);

    expect(screen.getByText('No target configured')).toBeInTheDocument();
  });

  it('shows a quiet, non-interactive running readout while a run is in flight', () => {
    renderWithProviders(
      <ServiceHeader
        header={aHeader({
          running: {
            id: 'exec-1',
            testName: 'capacity-check',
            testTypeLabel: 'Average load',
            stateLabel: 'Running',
          },
        })}
      />,
    );

    expect(screen.getByText('Running capacity-check')).toBeInTheDocument();
    // Not a button or link — starting a second run isn't offered, so there's nothing to press.
    expect(screen.queryByRole('link', { name: /Run/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Run/ })).not.toBeInTheDocument();
  });
});
