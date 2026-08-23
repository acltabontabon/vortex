import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../test/renderWithProviders';
import { HeaderRows } from './HeaderRows';
import { rowsFromMasked, rowsToWire, SECRET_MASK, type HeaderRow } from './headerRowUtils';

/** A minimal stateful host — `HeaderRows` is controlled, so exercising an edit needs somewhere for
 *  the changed rows to actually land. */
function Wrapper({ initial }: { initial: HeaderRow[] }) {
  const [rows, setRows] = useState(initial);
  return <HeaderRows rows={rows} onChange={setRows} />;
}

describe('rowsFromMasked / rowsToWire', () => {
  it('flags a row masked only when its value is exactly the mask string', () => {
    const rows = rowsFromMasked({ 'X-Api-Key': SECRET_MASK, 'X-Client': 'checkout-web' });

    expect(rows.find((r) => r.name === 'X-Api-Key')?.masked).toBe(true);
    expect(rows.find((r) => r.name === 'X-Client')?.masked).toBe(false);
    expect(rows.find((r) => r.name === 'X-Client')?.value).toBe('checkout-web');
  });

  it('round-trips to the same newline-joined wire format the textareas always produced', () => {
    const rows: HeaderRow[] = [
      { id: '1', name: 'X-Api-Key', value: '${API_KEY}', masked: false },
      { id: '2', name: 'X-Client', value: 'checkout-web', masked: false },
    ];

    expect(rowsToWire(rows)).toEqual({
      headerNames: 'X-Api-Key\nX-Client',
      headerValues: '${API_KEY}\ncheckout-web',
    });
  });

  it('drops a row with no name from the wire payload', () => {
    const rows: HeaderRow[] = [{ id: '1', name: '', value: 'orphaned', masked: false }];

    expect(rowsToWire(rows)).toEqual({ headerNames: '', headerValues: '' });
  });
});

describe('HeaderRows', () => {
  it('shows a masked row as a placeholder, not the mask string as an editable value', () => {
    renderWithProviders(<Wrapper initial={rowsFromMasked({ Authorization: SECRET_MASK })} />);

    expect(screen.getByText(SECRET_MASK)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Replace' })).toBeInTheDocument();
  });

  it('turns a masked row into an editable one once Replace is pressed', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Wrapper initial={rowsFromMasked({ Authorization: SECRET_MASK })} />);

    await user.click(screen.getByRole('button', { name: 'Replace' }));

    expect(screen.queryByRole('button', { name: 'Replace' })).not.toBeInTheDocument();
    expect(screen.getByPlaceholderText(/Value, or/)).toBeInTheDocument();
  });

  it('says none are configured when there are no rows', () => {
    renderWithProviders(<Wrapper initial={[]} />);

    expect(screen.getByText('None configured.')).toBeInTheDocument();
  });
});
