import type { ReactNode } from 'react';
import { Alert } from '@mantine/core';
import { ApiError } from '../api/client';

/**
 * The isError-then-Alert block every page starts with, before it has anything worth rendering.
 * Returns the Alert to return early with, or `null` once there's no error to show.
 */
export function errorFallback(isError: boolean, title: string, message?: ReactNode): ReactNode {
  if (!isError) return null;
  return (
    <Alert color="fail" title={title}>
      {message ?? 'Reload the page to try again.'}
    </Alert>
  );
}

/**
 * The `mutation.isError && mutation.error instanceof ApiError ? … : …` chain repeated at every
 * mutation call site, collapsed to the one thing it was ever deciding: is there a server-supplied
 * reason, or does the fallback stand?
 */
export function extractErrorMessage(
  mutation: { isError: boolean; error: unknown },
  fallback: string,
): string | null {
  if (!mutation.isError) return null;
  return mutation.error instanceof ApiError ? (mutation.error.detail ?? fallback) : fallback;
}
