import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/renderWithProviders';
import { DiscoveryReviewPanel } from './DiscoveryReviewPanel';
import type { DiscoveryScanResponse } from '../api/discovery';

function aProposal(overrides: Partial<DiscoveryScanResponse> = {}): DiscoveryScanResponse {
  return {
    ok: true,
    error: null,
    proposedServiceName: 'checkout-service',
    proposedServiceDescription: '',
    proposedOpenApiSourceFile: 'openapi.yaml',
    proposedEnvironment: null,
    proposedLocalLabComposeFile: 'compose.yaml',
    findings: [
      {
        kind: 'OPENAPI_SPEC',
        label: 'OpenAPI specification',
        sourceFile: 'openapi.yaml',
        evidence: ['42 operation(s) found, 6 mutating'],
        confidence: 'HIGH',
        confidenceExplanation: 'Vortex could parse this document.',
        attributes: {},
      },
      {
        kind: 'DEPENDENCY_POSTGRESQL',
        label: 'PostgreSQL',
        sourceFile: 'compose.yaml',
        evidence: ["image: postgres:17 (service 'postgres')"],
        confidence: 'HIGH',
        confidenceExplanation: 'An explicit, structural declaration.',
        attributes: { composeService: 'postgres' },
      },
    ],
    conflicts: [],
    partialFailures: [],
    ...overrides,
  };
}

describe('DiscoveryReviewPanel', () => {
  it('shows a plain message when nothing was found, and no apply button', () => {
    renderWithProviders(
      <DiscoveryReviewPanel proposal={aProposal({ findings: [], proposedOpenApiSourceFile: null, proposedLocalLabComposeFile: null })} onApply={vi.fn()} />,
    );

    expect(screen.getByText(/nothing to apply yet/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /apply setup/i })).not.toBeInTheDocument();
  });

  it('checks a proposed selection by default when nothing conflicts with it', () => {
    renderWithProviders(<DiscoveryReviewPanel proposal={aProposal()} onApply={vi.fn()} />);

    expect(screen.getByRole('checkbox', { name: /import operations from openapi\.yaml/i })).toBeChecked();
    expect(
      screen.getByRole('checkbox', { name: /use compose\.yaml as the local lab file/i }),
    ).toBeChecked();
  });

  it('leaves a conflicting selection unchecked, defaulting to "keep existing"', () => {
    const proposal = aProposal({
      conflicts: [
        {
          field: 'OPENAPI_SOURCE',
          existingDescription: 'file: docs/openapi.yaml',
          discoveredDescription: 'file: openapi.yaml',
        },
      ],
    });

    renderWithProviders(<DiscoveryReviewPanel proposal={proposal} onApply={vi.fn()} />);

    expect(screen.getByRole('checkbox', { name: /import operations from openapi\.yaml/i })).not.toBeChecked();
    expect(screen.getByText(/different from what's already saved/i)).toBeInTheDocument();
    expect(screen.getByText(/file: docs\/openapi\.yaml/i)).toBeInTheDocument();
  });

  it('reports exactly what was selected when Apply setup is clicked', async () => {
    const user = userEvent.setup();
    const onApply = vi.fn();
    renderWithProviders(<DiscoveryReviewPanel proposal={aProposal()} onApply={onApply} />);

    // Uncheck the local lab selection before applying, so the callback reflects the edit.
    await user.click(screen.getByRole('checkbox', { name: /use compose\.yaml as the local lab file/i }));
    await user.click(screen.getByRole('button', { name: /apply setup/i }));

    expect(onApply).toHaveBeenCalledWith({
      includeOpenApiSource: true,
      includeEnvironment: false,
      includeLocalLab: false,
    });
  });

  it('surfaces partial failures without hiding the rest of the proposal', () => {
    renderWithProviders(
      <DiscoveryReviewPanel
        proposal={aProposal({ partialFailures: ["compose.yaml contains a field Vortex ignored."] })}
        onApply={vi.fn()}
      />,
    );

    expect(screen.getByText(/1 warning/i)).toBeInTheDocument();
    expect(screen.getByText(/compose\.yaml contains a field vortex ignored/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /apply setup/i })).toBeInTheDocument();
  });

  it('discloses a finding\'s evidence on click', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DiscoveryReviewPanel proposal={aProposal()} onApply={vi.fn()} />);

    const toggle = screen.getByText('OpenAPI specification').closest('button')!;
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
  });
});
