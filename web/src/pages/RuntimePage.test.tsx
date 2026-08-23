import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../test/renderWithProviders';
import type { RuntimeSummary } from '../app-shell/api';
import { RuntimePage } from './RuntimePage';

let queryResult: { data: RuntimeSummary | undefined; isError: boolean } = {
  data: undefined,
  isError: false,
};

vi.mock('../app-shell/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../app-shell/api')>();
  return { ...actual, useRuntimeRefreshQuery: () => queryResult };
});

function aSummary(overrides: Partial<RuntimeSummary> = {}): RuntimeSummary {
  return {
    checks: [
      { name: 'Java', required: true, ok: true, mark: '✓', detail: '25.0.3', remedy: '' },
      {
        name: 'k6',
        required: true,
        ok: false,
        mark: '✗',
        detail: 'Not found on PATH.',
        remedy: 'Install it with `brew install k6`.',
      },
      { name: 'Docker', required: false, ok: false, mark: '–', detail: 'Not installed.', remedy: '' },
    ],
    satisfied: 1,
    total: 3,
    requirementsMet: false,
    ...overrides,
  };
}

describe('the runtime page', () => {
  it('states plainly when something required is missing', () => {
    queryResult = { data: aSummary(), isError: false };
    renderWithProviders(<RuntimePage />);

    expect(screen.getByText('Something Vortex requires is missing')).toBeInTheDocument();
    expect(screen.getByText('Install it with `brew install k6`.')).toBeInTheDocument();
  });

  it('states plainly when every requirement is met', () => {
    queryResult = {
      data: aSummary({
        checks: [{ name: 'Java', required: true, ok: true, mark: '✓', detail: '25.0.3', remedy: '' }],
        requirementsMet: true,
      }),
      isError: false,
    };
    renderWithProviders(<RuntimePage />);

    expect(screen.getByText('Everything Vortex requires is present')).toBeInTheDocument();
  });

  it('separates required checks from optional ones', () => {
    queryResult = { data: aSummary(), isError: false };
    renderWithProviders(<RuntimePage />);

    expect(screen.getByText('Required to run a test')).toBeInTheDocument();
    expect(screen.getByText('Optional')).toBeInTheDocument();
    expect(screen.getByText('Docker')).toBeInTheDocument();
  });

  it('surfaces a failed check rather than a silent empty page', () => {
    queryResult = { data: undefined, isError: true };
    renderWithProviders(<RuntimePage />);

    expect(screen.getByText('Could not check the runtime')).toBeInTheDocument();
  });
});
