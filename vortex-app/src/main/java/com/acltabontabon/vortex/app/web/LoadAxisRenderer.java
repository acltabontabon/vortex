package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.core.evidence.LoadAxis;
import com.acltabontabon.vortex.core.evidence.RunEvidence;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import org.springframework.stereotype.Component;

/**
 * Projects a {@link LoadAxis} into inline SVG.
 *
 * <p>Vortex's one distinctive picture: the levels a run actually held, whether each met its
 * objectives, and where — if anywhere — that stopped being true. Every other load-testing tool can
 * draw latency over time; almost none of them can draw this, because drawing it honestly requires
 * keeping an SLO breakpoint apart from system saturation and being willing to say that neither was
 * established.
 *
 * <p>This class does no arithmetic and reaches no conclusions. Which levels exist, whether a
 * boundary may be drawn at all, and whether a saturation range can be placed on this scale are all
 * decided in {@link LoadAxis} in the domain, for the same reason {@code PdfChartRenderer} defers to
 * {@code SeriesPlot}: a second renderer — the PDF, a comparison view, a terminal summary — must
 * project the same conclusions rather than derive its own.
 *
 * <h2>There are no bands</h2>
 *
 * <p>An earlier sketch of this shaded the axis into safe, approaching and failed regions. It looked
 * good and it was a lie: Vortex has no rule that determines when a service is "approaching" its
 * limit, so two-thirds of that gradient would have been an invented conclusion rendered more
 * persuasively than any of the measured ones. What is drawn is what was measured — one mark per
 * stage — plus a boundary only where the domain permits one.
 *
 * <p>Colours come from CSS custom properties, so the picture follows the light and dark themes and
 * carries the same verdict palette as the rest of the interface. Every axis is accompanied in the
 * templates by the table of the same numbers: a shape on a screen is not something anyone can quote
 * in a review.
 */
@Component("loadAxis")
public class LoadAxisRenderer {

    private static final int WIDTH = 720;
    private static final int HEIGHT = 96;
    private static final int PADDING_LEFT = 16;
    private static final int PADDING_RIGHT = 16;

    /** The vertical position of the axis line itself. */
    private static final int AXIS_Y = 52;
    private static final int POINT_RADIUS = 5;

    private static final int TRACK_WIDTH = WIDTH - PADDING_LEFT - PADDING_RIGHT;

    /**
     * The axis for one run.
     *
     * <p>Assembled here rather than stored on {@link RunEvidence} because it is a reading of that
     * evidence rather than another piece of it: every input is already in the record, and adding a
     * derived view to the exported schema would mean the JSON carrying both the stages and a second
     * account of what they mean.
     *
     * <p>Objectives are the gate. A run with none cannot report any level as compliant, so the axis
     * shows where the run went without colouring any of it as a verdict.
     */
    public LoadAxis axisFor(RunEvidence evidence) {
        if (evidence == null) {
            return LoadAxis.empty();
        }
        return LoadAxis.from(
                evidence.timeline().stages(),
                evidence.acceptance().hasObjectives(),
                evidence.performance().sloBreakpointIfPresent().orElse(null),
                evidence.performance().systemSaturationIfPresent().orElse(null));
    }

    /** Returns an empty string when there is no range worth drawing. */
    public String render(LoadAxis axis) {
        if (axis == null || !axis.isRenderable()) {
            return "";
        }

        StringBuilder svg = new StringBuilder(2048);
        svg.append("<svg class=\"load-axis\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(HEIGHT)
                .append("\" role=\"img\" aria-label=\"").append(escape(describe(axis)))
                .append("\" xmlns=\"http://www.w3.org/2000/svg\">");

        // The saturation range, first so the stage marks sit above it. A band and never a point:
        // system saturation is inferred from noisy signals and is reported as bounded or not at all.
        if (axis.drawsSaturation()) {
            int from = x(axis.position(axis.saturation().lowerBound()));
            int to = x(axis.position(axis.saturation().upperBound()));
            svg.append("<rect class=\"axis-saturation\" x=\"").append(from)
                    .append("\" y=\"").append(AXIS_Y - 14)
                    .append("\" width=\"").append(Math.max(2, to - from))
                    .append("\" height=\"28\"/>");
        }

        svg.append("<line class=\"axis-track\" x1=\"").append(PADDING_LEFT)
                .append("\" y1=\"").append(AXIS_Y)
                .append("\" x2=\"").append(WIDTH - PADDING_RIGHT)
                .append("\" y2=\"").append(AXIS_Y).append("\"/>");

        // An open right-hand end when nothing was violated. The boundary is above what this run
        // reached, and a closed axis would draw a ceiling nobody found.
        if (axis.isOpenEnded()) {
            int tip = WIDTH - PADDING_RIGHT;
            svg.append("<path class=\"axis-open\" d=\"M").append(tip - 10).append(' ')
                    .append(AXIS_Y - 5).append("L").append(tip).append(' ').append(AXIS_Y)
                    .append("L").append(tip - 10).append(' ').append(AXIS_Y + 5).append("\"/>");
        }

        if (axis.drawsBoundary()) {
            int marker = x(axis.position(axis.highestCompliant()));
            svg.append("<line class=\"axis-boundary\" x1=\"").append(marker)
                    .append("\" y1=\"").append(AXIS_Y - 22)
                    .append("\" x2=\"").append(marker)
                    .append("\" y2=\"").append(AXIS_Y + 22).append("\"/>");
        }

        for (LoadAxis.Point point : axis.points()) {
            int cx = x(axis.position(point.level()));
            svg.append("<circle class=\"axis-point ").append(pointClass(point))
                    .append("\" cx=\"").append(cx).append("\" cy=\"").append(AXIS_Y)
                    .append("\" r=\"").append(POINT_RADIUS).append("\"/>");
            svg.append("<text class=\"axis-level\" x=\"").append(cx)
                    .append("\" y=\"").append(AXIS_Y - 16)
                    .append("\" text-anchor=\"middle\">")
                    .append(escape(point.level().display())).append("</text>");
        }

        svg.append("<text class=\"axis-end\" x=\"").append(PADDING_LEFT)
                .append("\" y=\"").append(AXIS_Y + 26).append("\">0</text>");
        svg.append("<text class=\"axis-end\" x=\"").append(WIDTH - PADDING_RIGHT)
                .append("\" y=\"").append(AXIS_Y + 26)
                .append("\" text-anchor=\"end\">").append(escape(axis.unit())).append("</text>");

        svg.append("</svg>");
        return svg.toString();
    }

    /**
     * The picture in words, for anyone who is not receiving the picture.
     *
     * <p>Not a caption for the sighted reader — the page states the same conclusions in text
     * regardless — but the axis still has to describe itself rather than announcing "chart".
     */
    private String describe(LoadAxis axis) {
        StringBuilder text = new StringBuilder();
        text.append(axis.points().size()).append(" levels tested, up to ")
                .append(axis.testedTo().displayWithUnit()).append('.');

        if (!axis.objectivesEvaluated()) {
            text.append(" No objectives were evaluated, so no level is reported as compliant.");
            return text.toString();
        }
        if (axis.drawsBoundary()) {
            text.append(" Met every objective through ")
                    .append(axis.highestCompliant().displayWithUnit()).append('.');
            axis.firstNonCompliantIfPresent().ifPresent(level ->
                    text.append(" First violated at ").append(level.displayWithUnit()).append('.'));
        } else {
            text.append(" Tested capacity boundary ").append(axis.boundaryStatement()).append('.');
        }
        axis.saturationIfPresent().ifPresent(saturation ->
                text.append(" System saturation: ").append(saturation.describe()).append('.'));
        return text.toString();
    }

    private String pointClass(LoadAxis.Point point) {
        String verdict = switch (point.compliance()) {
            case COMPLIANT -> "compliant";
            case NON_COMPLIANT -> "non-compliant";
            case NOT_EVALUATED -> "unevaluated";
        };
        // A stage whose interval Vortex computed rather than measured is marked as such, so a claim
        // resting on it can be read at the confidence it deserves.
        return point.isObserved() ? verdict : verdict + " derived";
    }

    private int x(double fraction) {
        return PADDING_LEFT + (int) Math.round(TRACK_WIDTH * fraction);
    }

    /** Whether there is an axis worth putting on the page. */
    public boolean isRenderable(LoadAxis axis) {
        return axis != null && axis.isRenderable();
    }

    /** Where a level sits, for anything the template needs to place alongside the picture. */
    public double position(LoadAxis axis, LoadLevel level) {
        return axis == null ? 0 : axis.position(level);
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
