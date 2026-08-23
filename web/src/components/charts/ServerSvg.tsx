/**
 * Embeds a chart the server already rendered to an SVG string.
 *
 * <p>The load axis and the timeline plots stay server-rendered — see {@code RunDtos}'s javadoc in
 * vortex-app for why: the drawing math lives once, in `LoadAxisRenderer`/`SvgChartRenderer`, and
 * re-deriving it in React would be a second implementation of the same geometry for no reader-
 * facing benefit. The markup is produced by trusted Java code from measured evidence, never from
 * user input, so rendering it directly is safe here in a way it would not be for arbitrary HTML.
 */
export function ServerSvg({ svg, className }: { svg: string; className?: string }) {
  return <div className={className} dangerouslySetInnerHTML={{ __html: svg }} />;
}
