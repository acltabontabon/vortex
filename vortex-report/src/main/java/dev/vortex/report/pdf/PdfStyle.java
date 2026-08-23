package dev.vortex.report.pdf;

import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import dev.vortex.core.threshold.Verdict;
import java.awt.Color;

/**
 * Typography, colour and table construction for the report.
 *
 * <p>Base-14 Helvetica and Courier only. No embedded fonts: nothing to license, nothing to subset,
 * no font file in the repository, and the same bytes on every machine. Courier appears only where a
 * value is meant to be copied — hashes, identifiers, commands.
 *
 * <p>Colour is used for one thing: the verdict. Everything else is black text and grey rules, so the
 * document prints legibly in mono and reads as an engineering record rather than a dashboard.
 */
public final class PdfStyle {

    public static final float MARGIN = 56;
    public static final float MARGIN_TOP = 76;

    public static final Color INK = new Color(17, 17, 17);
    public static final Color MUTED = new Color(102, 102, 102);
    public static final Color RULE = new Color(214, 214, 214);
    public static final Color ZEBRA = new Color(248, 248, 248);

    public static final Color PASS = new Color(21, 115, 71);
    public static final Color FAIL = new Color(176, 32, 40);
    public static final Color WARN = new Color(150, 95, 10);

    public static final Font TITLE = helvetica(20, Font.BOLD, INK);
    public static final Font VERDICT = helvetica(26, Font.BOLD, INK);
    public static final Font H1 = helvetica(13, Font.BOLD, INK);
    public static final Font H2 = helvetica(10.5f, Font.BOLD, INK);
    public static final Font BODY = helvetica(9.5f, Font.NORMAL, INK);
    public static final Font BODY_BOLD = helvetica(9.5f, Font.BOLD, INK);
    public static final Font SMALL = helvetica(8, Font.NORMAL, MUTED);
    public static final Font LEDE = helvetica(11, Font.ITALIC, MUTED);
    public static final Font MONO = FontFactory.getFont(FontFactory.COURIER, 8.5f, Font.NORMAL, INK);

    private PdfStyle() {
    }

    private static Font helvetica(float size, int style, Color colour) {
        return FontFactory.getFont(FontFactory.HELVETICA, size, style, colour);
    }

    public static Color colourFor(Verdict verdict) {
        return switch (verdict) {
            case PASS -> PASS;
            case FAIL -> FAIL;
            case NOT_EVALUATED -> WARN;
        };
    }

    /**
     * A full-width table with a repeating header row.
     *
     * <p>{@code setHeaderRows(1)} is what makes a long operation breakdown readable: the column
     * names reappear at the top of every page it spans, so a reader who turns over is not looking at
     * an unlabelled grid of numbers.
     */
    public static PdfPTable table(float... relativeWidths) {
        PdfPTable table = new PdfPTable(relativeWidths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSpacingBefore(8);
        table.setSpacingAfter(4);
        // Rows may split across pages rather than pushing a whole table over; a table that refuses
        // to split leaves half-empty pages in a long report.
        table.setSplitLate(true);
        return table;
    }

    public static PdfPCell header(String text) {
        PdfPCell cell = cell(text, BODY_BOLD, Element.ALIGN_LEFT);
        cell.setBorderWidthBottom(0.8f);
        cell.setBorderColorBottom(INK);
        return cell;
    }

    public static PdfPCell headerRight(String text) {
        PdfPCell cell = header(text);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    public static PdfPCell text(String value) {
        return cell(value, BODY, Element.ALIGN_LEFT);
    }

    public static PdfPCell number(String value) {
        return cell(value, BODY, Element.ALIGN_RIGHT);
    }

    /** For a value that is absent rather than zero. */
    public static PdfPCell muted(String value) {
        return cell(value, SMALL, Element.ALIGN_RIGHT);
    }

    public static PdfPCell mono(String value) {
        return cell(value, MONO, Element.ALIGN_LEFT);
    }

    public static PdfPCell verdict(Verdict verdict) {
        PdfPCell cell = cell(verdict.label(),
                FontFactory.getFont(FontFactory.HELVETICA, 9.5f, Font.BOLD, colourFor(verdict)),
                Element.ALIGN_LEFT);
        return cell;
    }

    private static PdfPCell cell(String value, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(PdfText.winAnsi(value), font));
        cell.setHorizontalAlignment(alignment);
        cell.setPaddingTop(4);
        cell.setPaddingBottom(5);
        cell.setPaddingLeft(6);
        cell.setPaddingRight(6);
        cell.setBorder(com.lowagie.text.Rectangle.BOTTOM);
        cell.setBorderWidthBottom(0.4f);
        cell.setBorderColorBottom(RULE);
        return cell;
    }
}
