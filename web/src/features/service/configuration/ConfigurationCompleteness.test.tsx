import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/renderWithProviders';
import type { Configuration } from '../../../api/configuration';
import { ConfigurationCompleteness } from './ConfigurationCompleteness';

function aConfiguration(overrides: Partial<Configuration> = {}): Configuration {
  return {
    name: 'checkout-service',
    serviceVersion: null,
    environments: [],
    environmentTypes: [],
    dependencyModes: [],
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

describe('ConfigurationCompleteness', () => {
  it('states real facts rather than a fake completion percentage', () => {
    renderWithProviders(<ConfigurationCompleteness configuration={aConfiguration()} />);

    expect(screen.getByText(/0 operations/)).toBeInTheDocument();
    expect(screen.getByText(/0 environments/)).toBeInTheDocument();
    expect(screen.getByText(/production not recorded/)).toBeInTheDocument();
    expect(screen.getByText(/objectives not set/)).toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });

  it('singularizes a count of one', () => {
    renderWithProviders(
      <ConfigurationCompleteness
        configuration={aConfiguration({
          catalog: { imported: true, title: 't', sourceRef: null, operationCount: 1, mutatingCount: 0, operations: [] },
        })}
      />,
    );

    expect(screen.getByText(/1 operation(?!s)/)).toBeInTheDocument();
  });

  it('reflects what is actually configured', () => {
    renderWithProviders(
      <ConfigurationCompleteness
        configuration={aConfiguration({
          production: {
            peakRate: '35 req/s',
            averageRate: null,
            p95ObservedRate: null,
            source: null,
            attributed: false,
            fetched: false,
            observedWindow: null,
            note: null,
            qualityFacts: [],
            observedMix: [],
            mixCoverage: null,
          },
          thresholds: { p95Millis: 500, p99Millis: 1000, errorPercent: 1, describe: ['p95 latency below 500ms'] },
        })}
      />,
    );

    expect(screen.getByText(/production calibrated/)).toBeInTheDocument();
    expect(screen.getByText(/objectives configured/)).toBeInTheDocument();
  });
});
