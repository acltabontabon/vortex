import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

// RTL's automatic afterEach-cleanup detection looks for a true global `afterEach`, which isn't
// present since this project imports test functions explicitly rather than enabling Vitest's
// `globals` option — so cleanup is registered by hand instead.
afterEach(cleanup);

// jsdom doesn't implement matchMedia; Mantine's color-scheme detection calls it on every mount.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

// jsdom doesn't implement ResizeObserver either; Mantine's ScrollArea (used by Select, Combobox
// and friends) observes size on mount.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
vi.stubGlobal('ResizeObserver', ResizeObserverStub);

// jsdom doesn't implement scrollIntoView either; Mantine's Combobox (Select and friends) calls it
// when keyboard/pointer interaction moves the active option into view.
Element.prototype.scrollIntoView = vi.fn();
