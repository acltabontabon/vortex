import type { ReactNode } from 'react';
import classes from '../ConfigurationPage.module.css';

/**
 * One Configuration section: collapsed to its current state by default, open only while that
 * state is incomplete — the same rule `understand.html` used, so a service with everything set up
 * reads as a short list of facts rather than eight permanently-open forms.
 */
export function SectionDisclosure({
  id,
  title,
  state,
  openByDefault,
  children,
}: {
  id?: string;
  title: string;
  state: ReactNode;
  openByDefault: boolean;
  children: ReactNode;
}) {
  return (
    <details id={id} className={classes.section} open={openByDefault}>
      <summary className={classes.sectionSummary}>
        <span>{title}</span>
        <span className={classes.sectionState}>{state}</span>
      </summary>
      <div className={classes.sectionBody}>{children}</div>
    </details>
  );
}
