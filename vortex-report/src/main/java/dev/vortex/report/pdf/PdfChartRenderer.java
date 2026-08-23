package dev.vortex.report.pdf;

import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import dev.vortex.core.evidence.SeriesPlot;
import java.util.List;

/**
 * Draws a {@link SeriesPlot} with PDF line primitives.
 *
 * <p>The SVG on screen is not reused, because no PDF library renders SVG and the only route that
 * could — pulling in a rasteriser or a headless browser — would be a far larger and more fragile
 * dependency than the PDF library itself, for one polyline. Instead both renderers project the same
 * normalised geometry, which is the arrangement that keeps the printed chart and the on-screen chart
 * the same chart.
 *
 * <p>PDF's y axis grows upwards, unlike SVG's. The inversion happens here and nowhere else;
 * {@link SeriesPlot} stays neutral about which way is up.
 */
final class PdfChartRenderer {

    /** How many horizontal gridlines, matching the SVG. */
    private static final int DIVISIONS = 4;

    private final PdfWriter writer;

    PdfChartRenderer(PdfWriter writer) {
        this.writer = writer;
    }

    /**
     * Renders a plot as an image that can be placed in the document flow.
     *
     * <p>A template rather than direct canvas drawing, so the chart participates in pagination
     * instead of being stamped at absolute coordinates onto whichever page happens to be current.
     */
    Image render(SeriesPlot plot, float width, float height) {
        PdfTemplate template = writer.getDirectContent().createTemplate(width, height);

        float labelGutter = 34;
        float plotLeft = labelGutter;
        float plotWidth = width - labelGutter - 4;
        float plotBottom = 14;
        float plotHeight = height - plotBottom - 10;

        grid(template, plot, plotLeft, plotBottom, plotWidth, plotHeight);

        // The offered rate, drawn first so the achieved line sits on top of it.
        template.setLineWidth(0.6f);
        template.setColorStroke(PdfStyle.RULE);
        for (SeriesPlot.Segment segment : plot.reference()) {
            polyline(template, segment.points(), plotLeft, plotBottom, plotWidth, plotHeight);
        }

        plot.referenceLevelIfPresent().ifPresent(level -> {
            template.saveState();
            template.setColorStroke(PdfStyle.WARN);
            template.setLineWidth(0.6f);
            template.setLineDash(3, 2, 0);
            float y = plotBottom + (float) (plotHeight * level);
            template.moveTo(plotLeft, y);
            template.lineTo(plotLeft + plotWidth, y);
            template.stroke();
            template.restoreState();
        });

        // Each segment is its own path. A period where nothing was measured stays a gap; bridging it
        // would draw a measurement that was never taken.
        template.setLineWidth(1.1f);
        template.setColorStroke(PdfStyle.INK);
        for (SeriesPlot.Segment segment : plot.segments()) {
            polyline(template, segment.points(), plotLeft, plotBottom, plotWidth, plotHeight);
        }

        axis(template, plot, plotLeft, plotBottom, plotWidth, plotHeight);

        return Image.getInstance(template);
    }

    private void grid(PdfTemplate template, SeriesPlot plot, float left, float bottom,
            float width, float height) {

        List<String> labels = plot.axisLabels(DIVISIONS);
        template.saveState();
        template.setLineWidth(0.4f);
        template.setColorStroke(PdfStyle.RULE);

        for (int i = 0; i <= DIVISIONS; i++) {
            float y = bottom + height * (i / (float) DIVISIONS);
            template.moveTo(left, y);
            template.lineTo(left + width, y);
            template.stroke();

            ColumnText.showTextAligned(template, Element.ALIGN_RIGHT,
                    new Phrase(PdfText.winAnsi(labels.get(i)), PdfStyle.SMALL),
                    left - 5, y - 2.5f, 0);
        }
        template.restoreState();
    }

    private void axis(PdfTemplate template, SeriesPlot plot, float left, float bottom,
            float width, float height) {
        ColumnText.showTextAligned(template, Element.ALIGN_LEFT,
                new Phrase("0:00", PdfStyle.SMALL), left, bottom - 11, 0);
        ColumnText.showTextAligned(template, Element.ALIGN_RIGHT,
                new Phrase(PdfText.winAnsi(plot.spanLabel()), PdfStyle.SMALL),
                left + width, bottom - 11, 0);
        // Above the plot rather than at its foot, where it sat on top of the zero gridline label.
        ColumnText.showTextAligned(template, Element.ALIGN_LEFT,
                new Phrase(PdfText.winAnsi(plot.unitSymbol()), PdfStyle.SMALL),
                left, bottom + height + 3, 0);
    }

    private void polyline(PdfTemplate template, List<SeriesPlot.PlotPoint> points,
            float left, float bottom, float width, float height) {
        if (points.size() < 2) {
            return;
        }
        boolean started = false;
        for (SeriesPlot.PlotPoint point : points) {
            float x = left + (float) (width * point.x());
            // The one inversion: PDF measures y upwards from the bottom of the page.
            float y = bottom + (float) (height * point.y());
            if (started) {
                template.lineTo(x, y);
            } else {
                template.moveTo(x, y);
                started = true;
            }
        }
        template.stroke();
    }
}
