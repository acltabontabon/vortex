import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { Topbar } from './Topbar';
import { renderWithProviders } from '../test/renderWithProviders';

describe('Topbar', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify([]), { status: 200 })),
    );
  });

  it('links the brand to the workbench root', () => {
    renderWithProviders(<Topbar onOpenPalette={vi.fn()} />);
    expect(screen.getByRole('link', { name: /vortex/i })).toHaveAttribute('href', '/');
  });

  it('opens the command palette when its trigger is clicked', () => {
    const onOpenPalette = vi.fn();
    renderWithProviders(<Topbar onOpenPalette={onOpenPalette} />);
    fireEvent.click(screen.getByRole('button', { name: /open command palette/i }));
    expect(onOpenPalette).toHaveBeenCalledOnce();
  });

  it('links Settings to /settings', () => {
    renderWithProviders(<Topbar onOpenPalette={vi.fn()} />);
    expect(screen.getByRole('link', { name: /settings/i })).toHaveAttribute('href', '/settings');
  });
});
