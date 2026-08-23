import type { ReactNode } from 'react';
import classes from './Fact.module.css';

/**
 * One labelled fact, as a row.
 *
 * <p>Facts are rows, not cards. A card drawn around a single sentence is a box for its own sake,
 * and four of them in a line is the generic dashboard this workbench is deliberately not. The label
 * column is fixed so a stack of these reads as a table without being one.
 *
 * @param note supporting detail — a source, a window, a caveat — always dimmer than the value and
 *             never a substitute for it
 */
export function Fact({
  label,
  children,
  note,
}: {
  label: string;
  children: ReactNode;
  note?: ReactNode;
}) {
  return (
    <div className={classes.fact}>
      <div className={classes.label}>{label}</div>
      <div className={classes.body}>
        <div className={classes.value}>{children}</div>
        {note && <div className={classes.note}>{note}</div>}
      </div>
    </div>
  );
}

/** A stack of facts with hairlines between them. */
export function Facts({ children }: { children: ReactNode }) {
  return <div className={classes.facts}>{children}</div>;
}
