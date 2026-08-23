import { useRef } from 'react';
import type { ServiceCard } from '../api/home';
import { ShelfCard } from './ShelfCard';
import { AddServiceCard } from './AddServiceCard';
import classes from './ServiceShelf.module.css';

interface ServiceShelfProps {
  services: ServiceCard[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}

/**
 * A wrapping grid, not a carousel: cards flow onto as many rows as the window actually has room
 * for, so a wide desktop window shows more services at once rather than hiding them behind scroll
 * affordances. Unused space below and beside the cards is left empty on purpose — room for the
 * grid to grow into as services are added, not a gap to be filled with decoration.
 */
export function ServiceShelf({ services, selectedId, onSelect }: ServiceShelfProps) {
  const cardRefs = useRef(new Map<string, HTMLElement>());

  const moveSelection = (direction: 1 | -1) => {
    const currentIndex = selectedId ? services.findIndex((s) => s.id === selectedId) : -1;
    // Nothing selected yet: either arrow key just starts at the first card.
    const nextIndex = currentIndex === -1 ? 0 : Math.min(services.length - 1, Math.max(0, currentIndex + direction));
    const next = services[nextIndex];
    // At either edge, direction clamps to the same card — nothing actually moved, so don't call
    // onSelect at all: Home's onSelect toggles a repeated id off, which would deselect on an
    // edge-of-shelf arrow press that the user never meant as "deselect".
    if (!next || next.id === selectedId) return;
    onSelect(next.id);
    const el = cardRefs.current.get(next.id);
    el?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    el?.focus();
  };

  return (
    <div
      className={classes.grid}
      onKeyDown={(e) => {
        if (e.key === 'ArrowRight') {
          e.preventDefault();
          moveSelection(1);
        } else if (e.key === 'ArrowLeft') {
          e.preventDefault();
          moveSelection(-1);
        }
      }}
    >
      {services.map((service) => (
        <ShelfCard
          key={service.id}
          service={service}
          selected={service.id === selectedId}
          onSelect={() => onSelect(service.id)}
          cardRef={(el) => {
            if (el) cardRefs.current.set(service.id, el);
            else cardRefs.current.delete(service.id);
          }}
        />
      ))}
      <AddServiceCard />
    </div>
  );
}
