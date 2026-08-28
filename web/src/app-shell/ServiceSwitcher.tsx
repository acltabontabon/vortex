import { Menu, UnstyledButton } from '@mantine/core';
import { useLocation } from 'react-router-dom';
import { useServicesQuery } from './api';
import { useRunQuery } from '../api/run';
import classes from './Topbar.module.css';

/**
 * A menu to reach a service, not a navigation destination in its own right — "the list of
 * services" is a way of getting somewhere, not a place anyone wants to linger. Mirrors the old
 * `<details class="switcher">` in shape, just as a Mantine Menu.
 *
 * <p>Doubles as the breadcrumb once you're inside one: `Vortex / checkout-service ▾` says where you
 * are, not just where you could go, which the permanent `Vortex / Services` it replaces never did.
 * The service name comes from the same list this menu already fetches for its own entries — no
 * second request, just a lookup against the route's own `:id`.
 *
 * <p>The name and the chevron are two separate controls rather than one combined button: the name
 * is a real link straight back to this service's own Overview, and the chevron is the only thing
 * that opens the switcher. Configuration, Runs and Evidence are still standalone routes (see
 * {@code ServiceLayout}'s doc comment) with no subnav of their own, so before this split, the only
 * way back to Overview from one of them was opening a menu of every service and finding the one
 * already named in the breadcrumb — a search for something already on screen.
 *
 * <p>Every entry still points at a Thymeleaf-rendered page (only "/" is React-owned so far), so
 * these stay plain anchors — a full navigation — rather than router Links, matching how every
 * other still-unmigrated link in the app behaves today.
 */
export function ServiceSwitcher() {
  const { data: services } = useServicesQuery();
  const { pathname } = useLocation();
  // A plain regex rather than useMatch('/services/:id/*'): splat-matching an id with nothing
  // after it (the index route, no trailing segment) is exactly the ambiguous case worth not
  // relying on. "/services/new" matches too, with id "new" — harmless, since no real service ever
  // has that id, so the lookup below just falls through to the generic label.
  const currentId = pathname.match(/^\/services\/([^/]+)/)?.[1];
  const byService = services?.find((service) => service.id === currentId);

  // A run outlives edits to the service it tested, so `/runs/:id` (and its `/report` variant)
  // deliberately isn't nested under `/services/:id` — but that means the regex above finds nothing
  // there. Resolve the service from the run itself instead, so the breadcrumb doesn't just revert
  // to the generic label once you're inside a run. `/runs` (the list) and `/runs/compare` have no
  // single associated service, so they're excluded rather than treated as a run id.
  const runIdMatch = pathname.match(/^\/runs\/([^/]+)/)?.[1];
  const runId = runIdMatch && runIdMatch !== 'compare' ? runIdMatch : null;
  const { data: run } = useRunQuery(currentId ? null : runId);
  const current = byService ?? (run ? { id: run.plan.projectId, name: run.plan.projectName } : undefined);

  if (!services || services.length === 0) return null;

  return (
    <span className={classes.brand} style={{ fontWeight: 500 }}>
      <span aria-hidden="true" style={{ opacity: 0.6 }}>
        /
      </span>
      {current ? (
        <a href={`/services/${current.id}`} className={classes.brandLink}>
          {current.name}
        </a>
      ) : (
        'Services'
      )}
      <Menu shadow="md" width={220} position="bottom-start">
        <Menu.Target>
          <UnstyledButton className={classes.brandChevron} aria-label="Switch service">
            <span aria-hidden="true" style={{ fontSize: '0.7em', opacity: 0.6 }}>
              ▾
            </span>
          </UnstyledButton>
        </Menu.Target>
        <Menu.Dropdown>
          <Menu.Label>Services</Menu.Label>
          {services.map((service) => (
            <Menu.Item key={service.id} component="a" href={`/services/${service.id}`}>
              {service.name}
            </Menu.Item>
          ))}
          <Menu.Divider />
          <Menu.Item component="a" href="/services/new">
            Add a service…
          </Menu.Item>
          <Menu.Item component="a" href="/runs">
            All evidence
          </Menu.Item>
        </Menu.Dropdown>
      </Menu>
    </span>
  );
}
