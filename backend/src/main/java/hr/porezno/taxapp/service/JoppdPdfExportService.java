package hr.porezno.taxapp.service;

import hr.porezno.taxapp.dto.JoppdEntryDto;
import hr.porezno.taxapp.dto.JoppdYearSummaryDto;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Generira PDF sazetak pripremljen za JOPPD - jedna ili vise stranica po
 * godini, s tablicom oporezivih transakcija i ukupnim iznosima. Namijenjeno
 * za predaju knjigovodi/poreznom savjetniku ili kao osobna evidencija, NE kao
 * sluzbeni format za e-Poreznu (vidi napomenu u JoppdEntryDto).
 *
 * Koristi ugradeni Liberation Sans font (vidi resources/fonts/LICENSE.txt)
 * umjesto PDFBox-ovih Base14 fontova, jer Base14 (Helvetica i sl.) NE
 * podrzava hrvatske dijakriticke znakove (č, ć, đ, š, ž) i bacio bi gresku
 * pri pokusaju ispisa.
 */
@Service
public class JoppdPdfExportService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final float MARGIN = 40f;
    private static final float ROW_HEIGHT = 16f;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CELL_PADDING_RIGHT = 4f;

    // Razmak izmedu bazlinija dva retka teksta - priblizno 1.3x velicine fonta,
    // dovoljno da sprijeci vizualno preklapanje uzlaznih/silaznih dijelova slova
    // (ranija verzija je koristila premale fiksne vrijednosti pa su redovi
    // djelovali zbijeno/preklopljeno).
    private static final float TITLE_SIZE = 16f;
    private static final float TITLE_LEADING = 22f;
    private static final float SUBTITLE_SIZE = 9f;
    private static final float TOTALS_SIZE = 11f;
    private static final float TOTALS_LEADING = 16f;
    private static final float NOTE_SIZE = 8f;

    private static final float GAP_BEFORE_TABLE = 18f;
    private static final float GAP_AFTER_TABLE = 20f;
    private static final float GAP_BEFORE_NOTE = 14f;

    // Sirine kolona (u tockama) - moraju zbrojiti na (PAGE_WIDTH - 2*MARGIN)
    private static final float[] COL_WIDTHS = {63, 58, 33, 72, 58, 73, 73, 73};
    private static final String[] HEADERS = {"Datum", "Broker", "Ticker", "ISIN", "Kol.", "Nabavno", "Prodano", "Dobit/gubitak"};
    // Stupci koji se prikazuju desno poravnati (brojcane vrijednosti) - ostali (datum/broker/ticker/isin) ostaju lijevo.
    private static final Set<Integer> RIGHT_ALIGNED_COLUMNS = Set.of(4, 5, 6, 7);

    public byte[] generate(List<JoppdYearSummaryDto> summaries) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDFont regular = loadFont(document, "/fonts/LiberationSans-Regular.ttf");
            PDFont bold = loadFont(document, "/fonts/LiberationSans-Bold.ttf");

            if (summaries.isEmpty()) {
                PageCursor cursor = newPage(document);
                cursor.stream.beginText();
                cursor.stream.setFont(regular, 11);
                cursor.stream.newLineAtOffset(MARGIN, PAGE_HEIGHT - MARGIN);
                cursor.stream.showText("Nema realiziranih oporezivih prodaja u ucitanim podacima.");
                cursor.stream.endText();
                cursor.stream.close();
            }

            for (JoppdYearSummaryDto summary : summaries) {
                renderYear(document, regular, bold, summary);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private void renderYear(PDDocument document, PDFont regular, PDFont bold, JoppdYearSummaryDto summary) throws IOException {
        PageCursor cursor = newPage(document);

        cursor.text(bold, TITLE_SIZE, "Porez na kapitalnu dobit - " + summary.year() + ". godina");
        cursor.newLine(TITLE_LEADING);
        cursor.text(regular, SUBTITLE_SIZE, "Okvirni rok placanja: " + fmt(summary.rokPlacanja()) + " (provjeriti sluzbeni kalendar Porezne uprave)");
        cursor.newLine(GAP_BEFORE_TABLE);

        drawTableHeader(cursor, bold);

        for (JoppdEntryDto entry : summary.entries()) {
            if (cursor.y < MARGIN + 90) {
                cursor.stream.close();
                cursor = newPage(document);
                drawTableHeader(cursor, bold);
            }
            drawRow(cursor, regular, new String[]{
                    entry.sellDate() != null ? entry.sellDate().format(DATE_FORMAT) : "-",
                    nz(entry.broker()),
                    nz(entry.ticker()),
                    nz(entry.isin()),
                    entry.quantity() != null ? trimNum(entry.quantity()) : "-",
                    money(entry.costBasisEur()),
                    money(entry.proceedsEur()),
                    money(entry.gainEur())
            });
        }

        if (cursor.y < MARGIN + 110) {
            cursor.stream.close();
            cursor = newPage(document);
        }

        cursor.newLine(GAP_AFTER_TABLE);
        cursor.text(bold, TOTALS_SIZE, "Ukupna porezna osnovica: " + money(summary.ukupnaOsnovicaEur()) + " EUR");
        cursor.newLine(TOTALS_LEADING);
        cursor.text(bold, TOTALS_SIZE, "Porez (12%): " + money(summary.ukupanPorezEur()) + " EUR");
        cursor.newLine(GAP_BEFORE_NOTE);
        cursor.text(regular, NOTE_SIZE, wrapText(summary.napomena(), 100));

        cursor.stream.close();
    }

    private void drawTableHeader(PageCursor cursor, PDFont bold) throws IOException {
        for (int i = 0; i < HEADERS.length; i++) {
            drawCell(cursor, bold, 8, i, HEADERS[i]);
        }
        cursor.y -= ROW_HEIGHT;
        drawLine(cursor);
    }

    private void drawRow(PageCursor cursor, PDFont font, String[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            drawCell(cursor, font, 8, i, truncate(values[i], COL_WIDTHS[i]));
        }
        cursor.y -= ROW_HEIGHT;
    }

    /** Ispisuje jednu celiju tablice, lijevo ili desno poravnatu ovisno o stupcu. */
    private void drawCell(PageCursor cursor, PDFont font, float size, int columnIndex, String text) throws IOException {
        float colStart = MARGIN;
        for (int i = 0; i < columnIndex; i++) {
            colStart += COL_WIDTHS[i];
        }

        float x;
        if (RIGHT_ALIGNED_COLUMNS.contains(columnIndex)) {
            float textWidth = font.getStringWidth(text) / 1000f * size;
            x = colStart + COL_WIDTHS[columnIndex] - textWidth - CELL_PADDING_RIGHT;
        } else {
            x = colStart;
        }

        cursor.stream.beginText();
        cursor.stream.setFont(font, size);
        cursor.stream.newLineAtOffset(x, cursor.y);
        cursor.stream.showText(text);
        cursor.stream.endText();
    }

    private void drawLine(PageCursor cursor) throws IOException {
        cursor.stream.setLineWidth(0.5f);
        cursor.stream.moveTo(MARGIN, cursor.y + 4);
        cursor.stream.lineTo(PAGE_WIDTH - MARGIN, cursor.y + 4);
        cursor.stream.stroke();
    }

    private PageCursor newPage(PDDocument document) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(document, page);
        return new PageCursor(stream, PAGE_HEIGHT - MARGIN);
    }

    private PDFont loadFont(PDDocument document, String classpathLocation) throws IOException {
        try (InputStream is = new ClassPathResource(classpathLocation).getInputStream()) {
            return PDType0Font.load(document, is);
        }
    }

    private String fmt(java.time.LocalDate date) {
        return date != null ? date.format(DATE_FORMAT) : "-";
    }

    private String money(BigDecimal value) {
        if (value == null) return "-";
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String trimNum(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String nz(String v) {
        return v != null ? v : "-";
    }

    /** Gruba procjena maksimalnog broja znakova koji stane u stupac (za font velicine 8pt). */
    private String truncate(String text, float columnWidth) {
        int maxChars = (int) (columnWidth / 4.2f);
        if (text.length() <= maxChars) return text;
        return text.substring(0, Math.max(0, maxChars - 1)) + ".";
    }

    private String wrapText(String text, int maxLineLength) {
        if (text == null) return "";
        return text.length() > maxLineLength ? text.substring(0, maxLineLength - 1) + "..." : text;
    }

    /** Prati trenutnu Y poziciju pisanja na stranici (PDPageContentStream ne prati to sam). */
    private static class PageCursor {
        final PDPageContentStream stream;
        float y;

        PageCursor(PDPageContentStream stream, float y) {
            this.stream = stream;
            this.y = y;
        }

        void text(PDFont font, float size, String content) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(MARGIN, y);
            stream.showText(content);
            stream.endText();
        }

        void newLine(float amount) {
            y -= amount;
        }
    }
}
