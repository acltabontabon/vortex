import { initialsFor, badgeVariantFor } from '../lib/identity';
import classes from './ServiceBadge.module.css';

export function ServiceBadge({ id, name }: { id: string; name: string }) {
  const variant = badgeVariantFor(id);
  return (
    <span
      className={classes.badge}
      style={{ background: variant.bg, color: variant.fg }}
      aria-hidden="true"
    >
      {initialsFor(name)}
    </span>
  );
}
