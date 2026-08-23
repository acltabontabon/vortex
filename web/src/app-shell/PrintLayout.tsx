import { Outlet } from 'react-router-dom';
import classes from './PrintLayout.module.css';

/**
 * Chrome for a page meant to be shared or printed on its own — no topbar, service switcher,
 * status bar, or command palette. Used by the run report, which stands in for what report.html
 * was: a page whose only audience is whoever it was sent to, not someone navigating the app.
 */
export function PrintLayout() {
  return (
    <div className={classes.wrap}>
      <Outlet />
    </div>
  );
}
