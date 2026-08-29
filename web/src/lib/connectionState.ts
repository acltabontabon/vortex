/**
 * Maps a classified connection-test state (`ConfigurationApiController.connectionState` /
 * `SettingsApiController`'s equivalent) onto the pass/warn/fail vocabulary the settings UI already
 * uses. `null` covers a pre-flight refusal that never reached a connection — falls back to the plain
 * succeeded/failed split so it still reads sensibly.
 */
export function connectionStateAppearance(
  state: string | null,
  succeeded: boolean
): { color: string; title: string } {
  switch (state) {
    case 'CONNECTED':
      return { color: 'pass', title: 'Connected' };
    case 'CONNECTED_NO_DATA':
      return { color: 'warn', title: 'Connected — no data' };
    case 'AUTHENTICATION_FAILED':
      return { color: 'fail', title: 'Authentication failed' };
    case 'UNREACHABLE':
      return { color: 'fail', title: 'Unreachable' };
    case 'INVALID_RESPONSE':
      return { color: 'warn', title: 'Invalid response' };
    default:
      return { color: succeeded ? 'pass' : 'warn', title: 'Test connection' };
  }
}
