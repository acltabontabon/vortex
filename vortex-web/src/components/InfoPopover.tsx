import type { ReactNode } from 'react';
import { Popover, type PopoverProps } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconInfoCircle } from '@tabler/icons-react';
import classes from './InfoPopover.module.css';

/**
 * The one mechanism this app uses for "reveal explanation on demand" — a small ghost-button trigger,
 * never a dotted-underline link and never a pill, so a row of these never reads as web-1.0 hypertext
 * or as another rank of KPI tiles. The same `useDisclosure` + `Popover.Target`/`Popover.Dropdown`
 * shape `ReadinessPill` already proved out, pulled out once there were enough call sites (production
 * detail, headroom's "Why?", the conditions list) to be worth sharing rather than repeating.
 *
 * <p>What goes in `label` is never itself a refusal or a qualification — those stay in the
 * always-visible text beside this trigger. Only the *elaboration* behind a qualification belongs in
 * `children`. Set `icon` where the trigger sits right next to the value it elaborates (a bare "ⓘ"
 * glyph reads as a typo; a small `IconInfoCircle` reads as an affordance).
 */
export function InfoPopover({
  label,
  children,
  width = 320,
  position = 'bottom-start',
  ariaLabel,
  icon = false,
}: {
  label?: ReactNode;
  children: ReactNode;
  width?: number;
  position?: PopoverProps['position'];
  ariaLabel?: string;
  icon?: boolean;
}) {
  const [opened, handlers] = useDisclosure(false);

  return (
    <Popover opened={opened} onChange={handlers.toggle} position={position} width={width} withArrow>
      <Popover.Target>
        <button
          type="button"
          className={`${classes.trigger} ${icon ? classes.iconTrigger : ''}`}
          onClick={handlers.toggle}
          aria-expanded={opened}
          aria-label={ariaLabel}
        >
          {icon ? <IconInfoCircle size={13} stroke={1.75} /> : label}
        </button>
      </Popover.Target>
      <Popover.Dropdown>{children}</Popover.Dropdown>
    </Popover>
  );
}
