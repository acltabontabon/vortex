import { createTheme, type CSSVariablesResolver, type MantineColorsTuple } from '@mantine/core';

/*
 * Every hue here is lifted from the existing token set in vortex.css (the `:root` block, one
 * light-dark() pair per token) rather than invented — this theme is a translation, not a fresh
 * palette. Each 10-shade tuple places the real light-mode value at index 6 and the real dark-mode
 * value at index 8, matching Mantine's own default `primaryShade` (light: 6, dark: 8); the other
 * shades are interpolated so Mantine's hover/subtle/filled variants have somewhere to draw from.
 */

// Brand / primary action. Never used for a verdict — see vortex.css's own comment on this.
const brand: MantineColorsTuple = [
  '#e6f6ee', '#c8ecda', '#a9dec5', '#7fd3ac', '#55e0a1',
  '#34d68c', '#00a05a', '#008a4d', '#0a7a4b', '#05240f',
];

const pass: MantineColorsTuple = [
  '#eaf4ee', '#d3e9dc', '#b9d8c6', '#96c9ac', '#7fbf9b',
  '#64c996', '#1f6b46', '#1a5a3c', '#154a31', '#0f2117',
];

const fail: MantineColorsTuple = [
  '#fdecea', '#fad3ce', '#f4c2bc', '#f8a89e', '#f58b80',
  '#e6685a', '#b42318', '#932018', '#701812', '#2a1512',
];

const warn: MantineColorsTuple = [
  '#fdf4e3', '#f6e3bb', '#ecd7a4', '#e6c988', '#e0b45a',
  '#c99a3f', '#92600a', '#795009', '#5c3c08', '#241d0e',
];

const live: MantineColorsTuple = [
  '#e8f1fd', '#c9def8', '#bcd6f6', '#8fb9ee', '#6aa9f0',
  '#3c85dd', '#0b6bcb', '#0958a8', '#0a4680', '#0d1a2a',
];

const ai: MantineColorsTuple = [
  '#f4f0fe', '#e3d9fc', '#d8cbfa', '#c1adf7', '#a48ef5',
  '#8a6cf0', '#6741d9', '#5633b0', '#402877', '#1c1631',
];

// Shades 0-5 are the light-mode ramp (unchanged). Shades 6-9 are the dark-mode surface/border
// ramp: graphite, not the green-tinted values this used to carry (#56635b/#3d473f/#242b26/#121a15
// all leaned visibly green — G noticeably above both R and B at every one of those stops). The
// replacement values are within a couple of units of true neutral, with at most a hair of cool
// (blue) lean rather than green, so the "one quiet dark family" surfaces/borders/dividers all draw
// from now reads as graphite instead of as several dark palettes stacked on top of each other.
const neutral: MantineColorsTuple = [
  '#f7f8f6', '#eef0ec', '#dfe3dd', '#c4cbc1', '#a1aea7',
  '#8d9a93', '#5c5e5d', '#3a3c40', '#292a2d', '#212226',
];

const MOTION_QUICK_MS = 120;
const MOTION_MS = 200;

/*
 * The repeated `light-dark(var(--mantine-color-neutral-N), var(--mantine-color-neutral-M))` pairs
 * scattered across ServiceHeader/TestRow/TestResult/OverviewPage/RecentRunsRail were four call
 * sites independently re-deriving the same two ideas — "this element's own surface" and "a
 * structural divider on it" — from raw shade numbers, with nothing tying their choices together.
 * Naming those two ideas once, here, via Mantine's own CSS-variables mechanism (not a parallel
 * styling system) is what makes "card surface" and "card border" mean one consistent thing
 * everywhere instead of four coincidentally-matching ones.
 */
const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: {
    '--surface-card': 'var(--mantine-color-neutral-0)',
    '--border-subtle': 'var(--mantine-color-neutral-1)',
    '--border-default': 'var(--mantine-color-neutral-2)',
  },
  dark: {
    '--surface-card': 'var(--mantine-color-neutral-9)',
    '--border-subtle': 'var(--mantine-color-neutral-8)',
    '--border-default': 'var(--mantine-color-neutral-7)',
  },
});

export const theme = createTheme({
  primaryColor: 'brand',
  primaryShade: { light: 6, dark: 8 },
  colors: { brand, pass, fail, warn, live, ai, neutral },

  fontFamily: 'ui-sans-serif, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
  fontFamilyMonospace: 'ui-monospace, "SF Mono", "JetBrains Mono", Menlo, Consolas, monospace',

  defaultRadius: 'md',
  radius: { sm: '5px', md: '8px', lg: '12px' },

  spacing: { xs: '0.25rem', sm: '0.5rem', md: '0.75rem', lg: '1rem', xl: '1.5rem' },

  shadows: {
    sm: '0 1px 2px rgb(12 26 18 / 6%), 0 1px 3px rgb(12 26 18 / 4%)',
    md: '0 1px 3px rgb(12 26 18 / 8%), 0 4px 12px rgb(12 26 18 / 5%)',
  },

  // Reachable via theme.other for anything outside Mantine's 5-key spacing/radius scales —
  // --space-7 and --content-max don't map onto xs..xl cleanly, so they live here instead of
  // being forced into a slot that doesn't mean the same thing.
  other: {
    space7: '3rem',
    contentMax: '1560px',
    motionQuick: `${MOTION_QUICK_MS}ms`,
    motion: `${MOTION_MS}ms`,
  },

  components: {
    Button: { defaultProps: { radius: 'md' } },
    Card: { defaultProps: { withBorder: true, radius: 'md', shadow: 'sm' } },
    Badge: { defaultProps: { radius: 'sm' } },
    // One motion/shape language for every disclosure surface, instead of each call site (Popover,
    // HoverCard, Menu, Drawer) repeating its own radius/shadow/transition.
    Popover: {
      defaultProps: { radius: 'md', shadow: 'md', transitionProps: { duration: MOTION_QUICK_MS } },
    },
    HoverCard: {
      defaultProps: { radius: 'md', shadow: 'md', transitionProps: { duration: MOTION_QUICK_MS } },
    },
    Menu: {
      defaultProps: { radius: 'md', shadow: 'md', transitionProps: { duration: MOTION_QUICK_MS } },
    },
    Drawer: {
      defaultProps: { transitionProps: { duration: MOTION_MS } },
    },
  },
});

export { cssVariablesResolver };
