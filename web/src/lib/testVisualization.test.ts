import { describe, expect, it } from 'vitest';
import { chooseVisualization } from './testVisualization';

/**
 * One assertion per `TestType`, locking in the rule the whole file exists to enforce: the primary
 * visualization must visually answer that kind's own question. These are the guard rails — a future
 * change that quietly routes a temporal kind back through the magnitude-scale branch (or vice versa)
 * fails here before it ever reaches a screen.
 */
describe('choosing a visualization for each TestType', () => {
  it('gives Smoke the time-series primitive, annotated at the compliance breakpoint', () => {
    expect(chooseVisualization({ testType: 'SMOKE', hasTimeline: true, hasRange: true })).toEqual({
      primitive: 'time-series',
      annotation: 'breakpoint',
    });
  });

  it('gives Soak the time-series primitive, annotated at the compliance breakpoint', () => {
    expect(chooseVisualization({ testType: 'SOAK', hasTimeline: true, hasRange: true })).toEqual({
      primitive: 'time-series',
      annotation: 'breakpoint',
    });
  });

  it('gives Spike the time-series primitive, annotated at the jump — never the magnitude scale', () => {
    // This is the specific regression the rule exists to prevent: Spike used to route through
    // `saturating` straight into the wide range figure, which erased its temporal nature.
    expect(chooseVisualization({ testType: 'SPIKE', hasTimeline: true, hasRange: true })).toEqual({
      primitive: 'time-series',
      annotation: 'jump',
    });
  });

  it('gives Average load the load-summary primitive — never a scale, whether or not one would render', () => {
    expect(chooseVisualization({ testType: 'AVERAGE_LOAD', hasTimeline: true, hasRange: true })).toEqual({
      primitive: 'load-summary',
    });
    expect(chooseVisualization({ testType: 'AVERAGE_LOAD', hasTimeline: false, hasRange: true })).toEqual({
      primitive: 'load-summary',
    });
  });

  it('gives Stress the wide range figure with the stage ladder shown — pressure progressing through stages', () => {
    expect(chooseVisualization({ testType: 'STRESS', hasTimeline: true, hasRange: true })).toEqual({
      primitive: 'range-wide',
      emphasis: 'pressure',
      showStageLadder: true,
    });
  });

  it('gives Breakpoint the wide range figure with the boundary emphasized and no stage ladder', () => {
    expect(chooseVisualization({ testType: 'BREAKPOINT', hasTimeline: true, hasRange: true })).toEqual({
      primitive: 'range-wide',
      emphasis: 'breakpoint',
      showStageLadder: false,
    });
  });

  it('never gives Stress or Breakpoint the stage ladder from Spike/Soak\'s own timeline branch', () => {
    // A different kind of regression: the wide-figure kinds must stay on `range-wide` even when a
    // timeline happens to be present, since their question is still a magnitude one.
    const stress = chooseVisualization({ testType: 'STRESS', hasTimeline: true, hasRange: true });
    const breakpoint = chooseVisualization({ testType: 'BREAKPOINT', hasTimeline: true, hasRange: true });
    expect(stress.primitive).toBe('range-wide');
    expect(breakpoint.primitive).toBe('range-wide');
  });
});

describe('falling back gracefully when the preferred data is not there yet', () => {
  it('drops a temporal kind to the compact range when it has no timeline yet, not straight to nothing', () => {
    expect(chooseVisualization({ testType: 'SOAK', hasTimeline: false, hasRange: true })).toEqual({
      primitive: 'range-compact',
    });
    expect(chooseVisualization({ testType: 'SPIKE', hasTimeline: false, hasRange: true })).toEqual({
      primitive: 'range-compact',
    });
  });

  it('says unavailable, never invents a figure, once nothing at all is renderable', () => {
    expect(chooseVisualization({ testType: 'SOAK', hasTimeline: false, hasRange: false })).toEqual({
      primitive: 'unavailable',
    });
    expect(chooseVisualization({ testType: 'STRESS', hasTimeline: false, hasRange: false })).toEqual({
      primitive: 'unavailable',
    });
    expect(chooseVisualization({ testType: 'BREAKPOINT', hasTimeline: true, hasRange: false })).toEqual({
      primitive: 'unavailable',
    });
    expect(chooseVisualization({ testType: 'AVERAGE_LOAD', hasTimeline: false, hasRange: false })).toEqual({
      primitive: 'unavailable',
    });
  });

  it('treats an unrecognised TestType the same as any kind whose preferred data is missing', () => {
    expect(chooseVisualization({ testType: 'SOMETHING_FUTURE', hasTimeline: true, hasRange: true })).toEqual({
      primitive: 'range-compact',
    });
    expect(chooseVisualization({ testType: 'SOMETHING_FUTURE', hasTimeline: true, hasRange: false })).toEqual({
      primitive: 'unavailable',
    });
  });
});
