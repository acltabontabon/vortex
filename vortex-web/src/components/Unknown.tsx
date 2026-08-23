import { Button } from '@mantine/core';
import classes from './Unknown.module.css';

/**
 * Not known, and why.
 *
 * <p>Vortex never renders absence as emptiness where it knows the reason for it. A dash, an `N/A`
 * or an empty card all say the same unhelpful thing — that there is nothing here — when the domain
 * usually knows something far more useful: that headroom was refused because the capacity was
 * measured in VUs while production is recorded in requests/sec, or that saturation was not
 * established because the service absorbed every level the run offered.
 *
 * <p>Those are answers, and one of them — *not established by this test* — is documented as a
 * feature. So the surface is deliberately neutral: not styled as an error, which would say
 * something went wrong, and not styled as an empty state, which would say nothing is here.
 * Something is here. It is the reason.
 *
 * <p>Where a deterministic next action exists it is offered. Where one does not, none is invented.
 *
 * @param what   the thing that is not known, in the domain's own words
 * @param reason why it is not known — from the domain, never paraphrased in the client
 */
export function Unknown({
  what,
  reason,
  actionLabel,
  actionHref,
  compact,
}: {
  what: string;
  reason?: string | null;
  actionLabel?: string;
  actionHref?: string;
  compact?: boolean;
}) {
  return (
    <div className={`${classes.unknown} ${compact ? classes.compact : ''}`}>
      <span className={classes.mark} aria-hidden="true">
        ?
      </span>
      <div className={classes.body}>
        <p className={classes.what}>{what}</p>
        {reason && <p className={classes.reason}>{reason}</p>}
        {actionLabel && actionHref && (
          <Button
            component="a"
            href={actionHref}
            size="xs"
            variant="default"
            mt="xs"
          >
            {actionLabel}
          </Button>
        )}
      </div>
    </div>
  );
}

/** The same idea inline, for a value position inside a fact row. */
export function UnknownInline({ children }: { children: string }) {
  return <span className={classes.inline}>{children}</span>;
}
