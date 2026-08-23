import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { StatusBar } from './StatusBar';
import { renderWithProviders } from '../test/renderWithProviders';
import type { RuntimeSummary } from './api';

function mockRuntime(summary: RuntimeSummary) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(new Response(JSON.stringify(summary), { status: 200 })),
  );
}

describe('StatusBar', () => {
  it('reads "Runtime ready" and shows the k6/Docker/AI glance when requirements are met', async () => {
    mockRuntime({
      checks: [
        { name: 'Java', required: true, ok: true, mark: '✓', detail: '25', remedy: '' },
        { name: 'Load generator', required: true, ok: true, mark: '✓', detail: 'k6 v1.2.0', remedy: '' },
        { name: 'Workspace', required: true, ok: true, mark: '✓', detail: '/home/.vortex', remedy: '' },
        { name: 'Docker', required: false, ok: true, mark: '✓', detail: '24.0', remedy: '' },
        { name: 'Local AI', required: false, ok: false, mark: '○', detail: 'not found', remedy: 'Install it.' },
      ],
      satisfied: 4,
      total: 5,
      requirementsMet: true,
    });

    renderWithProviders(<StatusBar />);

    await waitFor(() => expect(screen.getByText('Runtime ready')).toBeInTheDocument());
    expect(screen.getByText('k6')).toBeInTheDocument();
    expect(screen.getByText('Docker')).toBeInTheDocument();
    expect(screen.getByText('AI')).toBeInTheDocument();
  });

  it('reads "Runtime needs attention" when a required check fails', async () => {
    mockRuntime({
      checks: [
        { name: 'Load generator', required: true, ok: false, mark: '✗', detail: 'not found', remedy: 'Install k6.' },
      ],
      satisfied: 0,
      total: 1,
      requirementsMet: false,
    });

    renderWithProviders(<StatusBar />);

    await waitFor(() => expect(screen.getByText('Runtime needs attention')).toBeInTheDocument());
  });
});
