import { useMemo, useState, useEffect, useRef } from 'react';
import { Modal, TextInput, Kbd } from '@mantine/core';
import { usePaletteQuery, type PaletteEntry } from './api';
import classes from './CommandPalette.module.css';

interface CommandPaletteProps {
  opened: boolean;
  onClose: () => void;
}

function matches(entry: PaletteEntry, query: string): boolean {
  const q = query.toLowerCase();
  return (
    entry.label.toLowerCase().includes(q) ||
    entry.detail.toLowerCase().includes(q) ||
    entry.kind.toLowerCase().includes(q)
  );
}

/**
 * The global "go anywhere" dialog — everything it lists is a URL already reachable by clicking
 * elsewhere (see PaletteController's own doc comment), so this is an accelerator, not the
 * interface. Navigates with a full page load rather than a router Link: most entries still point
 * at pages this migration hasn't reached yet, and a plain navigation is correct for both cases.
 */
export function CommandPalette({ opened, onClose }: CommandPaletteProps) {
  const { data: entries } = usePaletteQuery(opened);
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (opened) {
      setQuery('');
      setActiveIndex(0);
    }
  }, [opened]);

  const filtered = useMemo(() => {
    if (!entries) return [];
    if (!query.trim()) return entries;
    return entries.filter((entry) => matches(entry, query));
  }, [entries, query]);

  useEffect(() => {
    setActiveIndex(0);
  }, [query]);

  const groups = useMemo(() => {
    const byKind = new Map<string, PaletteEntry[]>();
    for (const entry of filtered) {
      const group = byKind.get(entry.kind) ?? [];
      group.push(entry);
      byKind.set(entry.kind, group);
    }
    return byKind;
  }, [filtered]);

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      withCloseButton={false}
      padding={0}
      size="lg"
      radius="md"
      keepMounted
      transitionProps={{ duration: 0 }}
      aria-label="Command palette"
    >
      <TextInput
        ref={inputRef}
        className={classes.input}
        size="lg"
        variant="unstyled"
        placeholder="Search services, workloads and runs…"
        aria-label="Search"
        value={query}
        onChange={(event) => setQuery(event.currentTarget.value)}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown') {
            event.preventDefault();
            setActiveIndex((i) => Math.min(filtered.length - 1, i + 1));
          } else if (event.key === 'ArrowUp') {
            event.preventDefault();
            setActiveIndex((i) => Math.max(0, i - 1));
          } else if (event.key === 'Enter') {
            const target = filtered[activeIndex];
            if (target) window.location.assign(target.href);
          }
        }}
      />
      <div className={classes.results} role="listbox">
        {filtered.length === 0 && <div className={classes.empty}>No matches</div>}
        {[...groups.entries()].map(([kind, items]) => (
          <div key={kind}>
            <div className={classes.group}>{kind}</div>
            {items.map((entry) => {
              const index = filtered.indexOf(entry);
              return (
                <a
                  key={`${entry.kind}-${entry.label}-${entry.href}`}
                  href={entry.href}
                  className={classes.item}
                  data-active={index === activeIndex}
                  role="option"
                  aria-selected={index === activeIndex}
                  onMouseEnter={() => setActiveIndex(index)}
                >
                  <span className={classes.itemLabel}>{entry.label}</span>
                  {entry.detail && <span className={classes.itemDetail}>{entry.detail}</span>}
                </a>
              );
            })}
          </div>
        ))}
      </div>
      <p className={classes.hint}>
        <span>
          <Kbd>↑</Kbd> <Kbd>↓</Kbd> to move
        </span>
        <span>
          <Kbd>↵</Kbd> to open
        </span>
        <span>
          <Kbd>esc</Kbd> to close
        </span>
      </p>
    </Modal>
  );
}
