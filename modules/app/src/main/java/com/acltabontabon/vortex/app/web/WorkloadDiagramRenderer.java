package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.app.web.WorkloadView.Composition;
import com.acltabontabon.vortex.app.web.WorkloadView.Row;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Draws one service workload converging on the service and fanning out to its operations.
 *
 * <p>This is the single picture the product needs. The thing newcomers got wrong about Vortex was
 * the relationship between an operation and a workload — whether a workload was one endpoint, or one
 * user's journey, or the whole service — and no amount of table-writing fixed it. A diagram that
 * shows one total dividing into proportional streams says it in a glance.
 *
 * <p>Used in exactly three places: the workload page, the resolution step of an evaluation, and
 * preflight. Anywhere else it would be decoration, and a signature that appears everywhere stops
 * being one.
 *
 * <h2>Rendered on the server, like every other chart here</h2>
 * Inline SVG with colours from CSS custom properties, so it follows the theme and needs no runtime
 * (ADR-021). It is always accompanied by the numbers rather than replacing them: the picture shows
 * the shape, the table is what somebody checks.
 */
@Component("diagram")
public class WorkloadDiagramRenderer {

    private static final int WIDTH = 620;
    private static final int HUB_X = 190;
    private static final int OP_X = 300;
    private static final int ROW_HEIGHT = 46;
    private static final int TOP = 34;
    private static final int MIN_STROKE = 2;
    private static final int MAX_STROKE = 14;

    /** Beyond this the fan becomes a comb, and the table is the better artefact anyway. */
    private static final int MAX_OPERATIONS = 8;

    public boolean isRenderable(Composition composition) {
        return composition != null
                && !composition.rows().isEmpty()
                && composition.rows().size() <= MAX_OPERATIONS;
    }

    /**
     * @param total   the offered load, already formatted with its unit
     * @param service the service under test, named because it is the thing being evaluated
     */
    public String render(Composition composition, String total, String service) {
        if (!isRenderable(composition)) {
            return "";
        }

        List<Row> rows = composition.rows();
        int height = TOP + rows.size() * ROW_HEIGHT + 14;
        int hubY = TOP + (rows.size() * ROW_HEIGHT) / 2 - ROW_HEIGHT / 2;

        StringBuilder svg = new StringBuilder(1024);
        svg.append("<svg class=\"converge\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(height)
                .append("\" role=\"img\" aria-label=\"")
                .append(escape(ariaLabel(composition, total, service))).append("\">");

        // The total arriving, and the service it arrives at.
        svg.append("<text class=\"total\" x=\"0\" y=\"14\">").append(escape(total)).append("</text>");
        svg.append("<text class=\"sub\" x=\"0\" y=\"").append(hubY + 4).append("\">")
                .append(escape(service)).append("</text>");
        svg.append("<line class=\"flow\" x1=\"0\" y1=\"22\" x2=\"0\" y2=\"")
                .append(hubY - 12).append("\" stroke-width=\"2\"/>");

        svg.append("<circle class=\"hub\" cx=\"").append(HUB_X).append("\" cy=\"").append(hubY)
                .append("\" r=\"7\" stroke-width=\"2\"/>");
        svg.append("<line class=\"flow\" x1=\"120\" y1=\"").append(hubY)
                .append("\" x2=\"").append(HUB_X - 9).append("\" y2=\"").append(hubY)
                .append("\" stroke-width=\"3\"/>");

        // One stream per operation, its thickness proportional to its share of the traffic.
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = TOP + i * ROW_HEIGHT;
            svg.append("<path class=\"flow\" d=\"M").append(HUB_X + 9).append(' ').append(hubY)
                    .append(" C ").append(HUB_X + 50).append(' ').append(hubY)
                    .append(", ").append(OP_X - 40).append(' ').append(y)
                    .append(", ").append(OP_X - 8).append(' ').append(y)
                    .append("\" stroke-width=\"").append(stroke(row)).append("\"/>");

            svg.append("<text class=\"label\" x=\"").append(OP_X).append("\" y=\"").append(y + 1)
                    .append("\">").append(escape(row.label())).append("</text>");
            svg.append("<text class=\"sub\" x=\"").append(OP_X).append("\" y=\"").append(y + 16)
                    .append("\">").append(escape(caption(row, composition))).append("</text>");
        }

        return svg.append("</svg>").toString();
    }

    /**
     * Stroke width proportional to share, floored so a 1% operation is still visibly present.
     *
     * <p>A stream too thin to see would say the operation is not part of the workload, which is a
     * different claim from "a small part of it".
     */
    private int stroke(Row row) {
        BigDecimal share = row.shareFraction();
        int scaled = share.multiply(BigDecimal.valueOf(MAX_STROKE))
                .setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.max(MIN_STROKE, Math.min(MAX_STROKE, scaled));
    }

    private String caption(Row row, Composition composition) {
        if (composition.concurrency()) {
            return row.method().isBlank() ? "" : row.method() + " " + row.path();
        }
        return row.sharePercent() + " · " + row.rateDisplay() + "/sec";
    }

    /**
     * What a screen reader is told.
     *
     * <p>The same facts as the picture, in a sentence. The accompanying table carries the detail, so
     * this only has to establish the relationship the diagram exists to show.
     */
    private String ariaLabel(Composition composition, String total, String service) {
        StringBuilder label = new StringBuilder();
        label.append(total).append(" offered to ").append(service)
                .append(", divided across ").append(composition.rows().size())
                .append(composition.rows().size() == 1 ? " operation: " : " operations: ");
        for (int i = 0; i < composition.rows().size(); i++) {
            Row row = composition.rows().get(i);
            if (i > 0) {
                label.append("; ");
            }
            label.append(row.label());
            if (!composition.concurrency()) {
                label.append(' ').append(row.sharePercent());
            }
        }
        return label.toString();
    }

    /** Operation labels come from an imported specification, so they are never trusted as markup. */
    private String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
