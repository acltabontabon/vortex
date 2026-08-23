import { Outlet } from 'react-router-dom';
import { useDisclosure, useHotkeys } from '@mantine/hooks';
import { Topbar } from './Topbar';
import { StatusBar } from './StatusBar';
import { CommandPalette } from './CommandPalette';
import classes from './AppShell.module.css';

/**
 * The persistent frame every route renders inside — replaces layout.html's chrome (top bar,
 * service switcher, runtime status, command palette) for every React-owned route. Routes this
 * migration hasn't reached yet still get Thymeleaf's own copy of this chrome; there are
 * necessarily two implementations side by side until Thymeleaf is fully removed.
 */
export function AppShell() {
  const [paletteOpened, paletteHandlers] = useDisclosure(false);
  useHotkeys([['mod+K', paletteHandlers.open]]);

  return (
    <div className={classes.wrap}>
      <Topbar onOpenPalette={paletteHandlers.open} />
      <main id="main-content" className={classes.main}>
        <Outlet />
      </main>
      <StatusBar />
      <CommandPalette opened={paletteOpened} onClose={paletteHandlers.close} />
    </div>
  );
}
