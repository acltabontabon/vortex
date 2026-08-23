package dev.vortex.report.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import dev.vortex.core.evidence.RunEvidence;

/**
 * Draws the running header and footer, and resolves the page count.
 *
 * <p>Every page after the first repeats what the document is about. A report that has been printed
 * and had its pages separated — which is what happens to a document in a release review — should
 * still say on page four which service, workload and environment it describes, and what the verdict
 * was.
 *
 * <p>"Page 3 of 7" needs a total nobody knows until the document is closed, so the total is drawn
 * into a reserved template that is filled in at the end. That is the standard technique for this
 * library and the only way to get it in a single pass.
 */
final class ReportPageEvents extends PdfPageEventHelper {

    private final String heading;
    private final String verdict;
    private final String footer;

    private PdfTemplate pageCount;

    ReportPageEvents(RunEvidence evidence) {
        var identity = evidence.identity();
        this.heading = PdfText.winAnsi(identity.describe());
        this.verdict = PdfText.winAnsi(evidence.verdict().label());
        this.footer = PdfText.winAnsi(
                identity.shortId() + "  ·  " + evidence.provenance().shortHash());
    }

    @Override
    public void onOpenDocument(PdfWriter writer, Document document) {
        pageCount = writer.getDirectContent().createTemplate(40, 12);
    }

    @Override
    public void onEndPage(PdfWriter writer, Document document) {
        // Page one carries the title block, which already says all of this. Repeating it there
        // would be noise on the one page that needs none.
        if (writer.getPageNumber() > 1) {
            drawHeader(writer, document);
        }
        drawFooter(writer, document);
    }

    private void drawHeader(PdfWriter writer, Document document) {
        PdfContentByte canvas = writer.getDirectContent();
        float top = document.top() + 22;

        ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT,
                new Phrase(heading, PdfStyle.SMALL), document.left(), top, 0);
        ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT,
                new Phrase(verdict, PdfStyle.SMALL), document.right(), top, 0);

        canvas.saveState();
        canvas.setColorStroke(PdfStyle.RULE);
        canvas.setLineWidth(0.4f);
        canvas.moveTo(document.left(), top - 6);
        canvas.lineTo(document.right(), top - 6);
        canvas.stroke();
        canvas.restoreState();
    }

    private void drawFooter(PdfWriter writer, Document document) {
        PdfContentByte canvas = writer.getDirectContent();
        float bottom = document.bottom() - 22;

        ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT,
                new Phrase(footer, PdfStyle.SMALL), document.left(), bottom, 0);

        String prefix = "Page " + writer.getPageNumber() + " of  ";
        float width = PdfStyle.SMALL.getCalculatedBaseFont(true)
                .getWidthPoint(prefix, PdfStyle.SMALL.getCalculatedSize());

        ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT,
                new Phrase(prefix, PdfStyle.SMALL), document.right() - width - 14, bottom, 0);
        canvas.addTemplate(pageCount, document.right() - 14, bottom);
    }

    @Override
    public void onCloseDocument(PdfWriter writer, Document document) {
        pageCount.beginText();
        pageCount.setFontAndSize(PdfStyle.SMALL.getCalculatedBaseFont(true),
                PdfStyle.SMALL.getCalculatedSize());
        pageCount.setColorFill(PdfStyle.MUTED);
        pageCount.showText(String.valueOf(writer.getPageNumber() - 1));
        pageCount.endText();
    }
}
