// Shared fetch wrapper every feature's api.ts builds on. Kept deliberately thin — relative URLs
// (proxied to Spring Boot by Vite in dev, same-origin in prod), a consistent error on a non-2xx
// response, and JSON in/out. Query/mutation orchestration (caching, retries, invalidation) is
// TanStack Query's job, not this file's.

export class ApiError extends Error {
  method: string;
  path: string;
  status: number;
  /**
   * The server's own explanation, when the failed response carried a ProblemDetail `detail`
   * field (Spring's default shape for a `ResponseStatusException`, e.g. "A project named 'x'
   * already exists."). Undefined for a response with no parseable detail — callers fall back to
   * a generic message in that case rather than inventing one.
   */
  detail?: string;

  constructor(method: string, path: string, status: number, detail?: string) {
    super(detail ?? `${method} ${path} failed: ${status}`);
    this.name = 'ApiError';
    this.method = method;
    this.path = path;
    this.status = status;
    this.detail = detail;
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    let detail: string | undefined;
    try {
      const problem = await response.clone().json();
      detail = typeof problem?.detail === 'string' ? problem.detail : undefined;
    } catch {
      // Not JSON, or no body — no detail to surface, the generic message stands.
    }
    throw new ApiError(method, path, response.status, detail);
  }
  // A `void`-returning Spring handler (delete, dismiss) answers 200 with an empty body, not 204 —
  // only checking the status code here left `response.json()` to throw on that empty body, which
  // silently rejected the mutation's promise. `onSuccess` (and the query invalidation it triggers)
  // never ran, so the UI only caught up on a manual reload. Checking the body itself, not the
  // status, catches every empty-body success rather than special-casing 204.
  const text = await response.text();
  return (text.length === 0 ? undefined : JSON.parse(text)) as T;
}

export const apiClient = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  delete: <T>(path: string) => request<T>('DELETE', path),
};
