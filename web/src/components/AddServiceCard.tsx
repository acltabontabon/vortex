import classes from './AddServiceCard.module.css';

/**
 * The shelf's last stop — an intentional empty slot, not a service. Same footprint as a real card
 * (the flex row stretches every child to match), dashed and quiet so it never reads as one.
 */
export function AddServiceCard() {
  return (
    <a className={classes.card} href="/services/new">
      <span className={classes.icon} aria-hidden="true">
        +
      </span>
      <span className={classes.title}>Add a service</span>
      <span className={classes.subtitle}>Bring another service into Vortex</span>
    </a>
  );
}
