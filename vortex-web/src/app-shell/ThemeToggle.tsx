import { useMantineColorScheme, useComputedColorScheme, Tooltip } from '@mantine/core';
import classes from './Topbar.module.css';

/**
 * Replaces vortex.js's theme toggle button + the old ThemeSync bridge outright. Mantine owns
 * `data-mantine-color-scheme` on `<html>` directly now, which is what `light-dark()` resolves
 * against — there's no second actor to stay in sync with any more.
 */
export function ThemeToggle() {
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme('light');

  return (
    <Tooltip label="Switch theme" openDelay={400} withArrow>
      <button
        type="button"
        className={classes.iconBtn}
        aria-label="Switch theme"
        onClick={() => setColorScheme(computed === 'dark' ? 'light' : 'dark')}
      >
        <span aria-hidden="true">◐</span>
      </button>
    </Tooltip>
  );
}
