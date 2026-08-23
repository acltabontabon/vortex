import { Link } from 'react-router-dom';
import { Tooltip, Kbd } from '@mantine/core';
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
            d="M3 4.5c6 0 6.5 7.5 9 7.5M3 12h9M3 19.5c6 0 6.5-7.5 9-7.5"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
          />
          <circle cx="15" cy="12" r="2.6" stroke="currentColor" strokeWidth="1.7" />
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
        <a href="/settings" className={classes.iconBtn} aria-label="Settings">
          <span aria-hidden="true">⚙</span>
        </a>
      </Tooltip>

      <ThemeToggle />
    </header>
  );
}
