import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';

afterEach(() => {
  vi.unstubAllGlobals();
});

/**
 * A `void`-returning Spring handler (delete a test, dismiss a lab) answers 200 with an empty
 * body, not 204. Getting this wrong once meant `response.json()` threw on that empty body, the
 * mutation's promise silently rejected, and `onSuccess` — the thing that invalidates the query
 * cache and refreshes the screen — never ran. The only symptom was a stale UI a manual reload
 * fixed, which is exactly the kind of bug these tests exist to make impossible to reintroduce.
 */
describe('apiClient', () => {
  it('resolves undefined for a 200 with an empty body, the shape a void endpoint answers with', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 200 })));

    await expect(apiClient.post('/api/services/x/tests/y/delete')).resolves.toBeUndefined();
  });

  it('still resolves undefined for a 204 with no body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

    await expect(apiClient.delete('/api/x')).resolves.toBeUndefined();
  });

  it('parses a real JSON body on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ name: 'x' }), { status: 200 })),
    );

    await expect(apiClient.get('/api/x')).resolves.toEqual({ name: 'x' });
  });

  it('surfaces the server\'s own detail on a non-2xx response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ detail: "A project named 'x' already exists." }), {
          status: 400,
        }),
      ),
    );

    await expect(apiClient.post('/api/x')).rejects.toMatchObject({
      status: 400,
      detail: "A project named 'x' already exists.",
    });
  });

  it('falls back to a generic message when a failed response carries no parseable detail', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 500 })));

    await expect(apiClient.get('/api/x')).rejects.toMatchObject({
      status: 500,
      detail: undefined,
    });
  });
});
