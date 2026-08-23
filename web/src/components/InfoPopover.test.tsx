import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/renderWithProviders';
import { InfoPopover } from './InfoPopover';

describe('an info popover', () => {
  it('shows the trigger label but not its content before it is opened', () => {
    renderWithProviders(
      <InfoPopover label="Applies under 3 conditions ›">
        <p>Environment local</p>
      </InfoPopover>,
    );

    expect(screen.getByText('Applies under 3 conditions ›')).toBeInTheDocument();
    expect(screen.queryByText('Environment local')).not.toBeInTheDocument();
  });

  it('reveals its content once the trigger is clicked', async () => {
    renderWithProviders(
      <InfoPopover label="Why?" ariaLabel="Why headroom is not established">
        <p>This capacity was measured in an isolated test.</p>
      </InfoPopover>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Why headroom is not established' }));

    expect(
      await screen.findByText('This capacity was measured in an isolated test.'),
    ).toBeInTheDocument();
  });
});
