import { Link } from 'react-router-dom';
import { ActionIcon, Tooltip, Kbd } from '@mantine/core';
import { IconSettings } from '@tabler/icons-react';
import { ServiceSwitcher } from './ServiceSwitcher';
import { ThemeToggle } from './ThemeToggle';
import classes from './Topbar.module.css';

interface TopbarProps {
  onOpenPalette: () => void;
}

export function Topbar({ onOpenPalette }: TopbarProps) {
  return (
    <header className={classes.bar}>
      <Link to="/" className={classes.brand}>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M12 3a9 9 0 1 1-6.37 2.63"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
          />
          <path
            d="M12 6.75a5.25 5.25 0 1 1-3.71 1.54"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
          />
          <circle cx="12" cy="12" r="1.8" fill="currentColor" />
        </svg>
        Vortex
      </Link>

      <ServiceSwitcher />

      <div className={classes.gap} />

      <Tooltip label="Command palette" openDelay={400} withArrow>
        <button
          type="button"
          className={classes.paletteTrigger}
          onClick={onOpenPalette}
          aria-label="Open command palette"
        >
          <Kbd size="xs">⌘</Kbd>K
        </button>
      </Tooltip>

      <Tooltip label="Settings" openDelay={400} withArrow>
        <ActionIcon component="a" href="/settings" variant="subtle" color="gray" size={32} aria-label="Settings">
          <IconSettings size={18} stroke={1.6} />
        </ActionIcon>
      </Tooltip>

      <ThemeToggle />
    </header>
  );
}
