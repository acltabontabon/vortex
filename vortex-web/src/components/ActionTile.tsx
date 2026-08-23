import { Card } from '@mantine/core';
import type { ComponentType } from 'react';
import classes from './ActionTile.module.css';

interface ActionTileProps {
  icon: ComponentType<{ size?: number; stroke?: number }>;
  title: string;
  description: string;
  href: string;
}

/**
 * One of Vortex's core jobs, as a real primitive rather than a differently-sized text box: fixed
 * dimensions, an icon, a title, one line of description, and one consistent hover/focus treatment
 * every instance shares.
 */
export function ActionTile({ icon: Icon, title, description, href }: ActionTileProps) {
  return (
    <Card component="a" href={href} withBorder radius="md" className={classes.tile}>
      <span className={classes.icon}>
        <Icon size={20} stroke={1.6} />
      </span>
      <span className={classes.title}>{title}</span>
      <span className={classes.description}>{description}</span>
    </Card>
  );
}
