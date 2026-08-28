import { useMantineColorScheme, useComputedColorScheme, ActionIcon, Tooltip } from '@mantine/core';
import { IconSun, IconMoonStars } from '@tabler/icons-react';

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
      <ActionIcon
        variant="subtle"
        color="gray"
        size={32}
        aria-label="Switch theme"
        onClick={() => setColorScheme(computed === 'dark' ? 'light' : 'dark')}
      >
        {computed === 'dark' ? <IconSun size={18} stroke={1.6} /> : <IconMoonStars size={18} stroke={1.6} />}
      </ActionIcon>
    </Tooltip>
  );
}
