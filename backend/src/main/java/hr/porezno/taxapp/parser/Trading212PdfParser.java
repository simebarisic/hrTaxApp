package hr.porezno.taxapp.parser;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort parser za Trading212 "Izvod aktivnosti trgovanja" PDF (sekcija
 * "BURZOVNE I OTC TRANSAKCIJE"). Za razliku od CSV parsera, ovo je PDF -> tekst
 * ekstrakcija + regex na retke tablice, sto je po prirodi krhkije (vidi
 * napomenu u README-u o zasto je CSV prioritet).
 *
 * Ocekivani oblik retka (razmaci variraju, regex je tolerantan na to):
 *   2025-07-30 06:00:39   DN3   JP3481200008   36408536859   Kupi   83,32664756   6,98 €   581,62 €   Tržište   OTC   1   -   -   -   581,62 €
 *
 * Redoslijed polja: VRIJEME IZVRŠENJA, INSTRUMENT, ISIN, ID NALOGA, POMAK
 * (Kupi/Prodaj), KOLIČINA, CIJENA IZVRŠENJA, VRIJEDNOST (bruto), ... ostalo
 * (varijabilan broj kolona koje se ponekad izostavljaju kad su prazne) ...
 * zavrsna VRIJEDNOST (neto, nakon naknada) - uzima se POSLJEDNJI "iznos €"
 * na retku.
 *
 * NAPOMENA: NIJE testirano na stvarnom PDF-u (samo na strukturi teksta koju
 * je korisnik ranije podijelio). Ako PDFBox razlomi retke tablice drugacije
 * nego ocekivano (npr. jedan red tablice na vise linija teksta), parser nece
 * pronaci transakcije - u tom slucaju treba prilagoditi regex prema stvarnom
 * PDFBox izlazu.
 */
@Component
public class Trading212PdfParser implements StatementParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Pocetak retka: datum, instrument, ISIN, ID naloga, smjer (Kupi/Prodaj),
    // kolicina, cijena+eur, bruto vrijednost+eur. Ostatak retka se obraduje
    // posebno (varijabilan broj kolona).
    private static final Pattern ROW_START = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+" +   // 1: datum vrijeme
            "(\\S+)\\s+" +                                            // 2: instrument (ticker)
            "([A-Z]{2}[A-Z0-9]{9}\\d)\\s+" +                          // 3: ISIN
            "(\\d+)\\s+" +                                            // 4: ID naloga
            "(Kupi|Prodaj)\\s+" +                                     // 5: smjer
            "([\\d.,]+)\\s+" +                                        // 6: kolicina
            "([\\d.,]+)\\s*€\\s+" +                                   // 7: cijena izvrsenja
            "([\\d.,]+)\\s*€\\s+" +                                   // 8: bruto vrijednost
            "(.*)$"                                                   // 9: ostatak retka
    );

    // Zadnji "X,YY €" iznos na retku - koristi se kao NETO vrijednost (nakon naknada).
    private static final Pattern TRAILING_EUR_AMOUNT = Pattern.compile("([\\d.,]+)\\s*€");

    // Heuristika za "sumnjive" retke - izgledaju kao da su TREBALI biti
    // transakcija (pocinju datumom, ili sadrze "Kupi"/"Prodaj") ali ne
    // prolaze puni ROW_START regex. Ostali redci (zaglavlja, prazni redci,
    // fusnote) se NE stavljaju u karantenu jer bi zatrpali korisnika sumom.
    private static final Pattern LOOKS_LIKE_TRANSACTION = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}|\\bKupi\\b|\\bProdaj\\b"
    );

    @Override
    public ParseResult parse(InputStream inputStream) throws IOException {
        ParseResult result = new ParseResult();

        String text = extractText(inputStream);
        String[] lines = text.split("\\r?\\n");

        int rowIndex = 1;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                rowIndex++;
                continue;
            }

            Transaction tx = tryParseLine(line, rowIndex);
            if (tx != null) {
                result.addTransaction(tx);
            } else if (LOOKS_LIKE_TRANSACTION.matcher(line).find()) {
                // Redak "izgleda" kao transakcija (pocinje datumom ili sadrzi
                // Kupi/Prodaj) ali ne prolazi puni regex - vjerojatno
                // neocekivan format retka, korisnik treba znati za ovo.
                result.addSkipped(rowIndex, line, "Redak izgleda kao transakcija ali ne odgovara ocekivanom formatu (mozda drugaciji PDF layout).");
            }
            rowIndex++;
        }

        return result;
    }

    private String extractText(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        inputStream.transferTo(buffer);

        try (PDDocument document = Loader.loadPDF(buffer.toByteArray())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private Transaction tryParseLine(String line, int rowIndex) {
        Matcher m = ROW_START.matcher(line);
        if (!m.matches()) {
            return null;
        }

        LocalDateTime dateTime;
        try {
            dateTime = LocalDateTime.parse(m.group(1), DATE_FORMAT);
        } catch (Exception e) {
            return null;
        }

        String ticker = m.group(2);
        String isin = m.group(3);
        String orderId = m.group(4);
        TransactionType type = "Kupi".equals(m.group(5)) ? TransactionType.BUY : TransactionType.SELL;
        BigDecimal quantity = parseHrDecimal(m.group(6));
        BigDecimal pricePerShare = parseHrDecimal(m.group(7));
        BigDecimal grossValue = parseHrDecimal(m.group(8));
        String rest = m.group(9);

        BigDecimal netValue = extractTrailingEurAmount(rest);
        if (netValue == null) {
            netValue = grossValue;
        }

        BigDecimal fees = grossValue != null && netValue != null
                ? grossValue.subtract(netValue).abs()
                : BigDecimal.ZERO;

        if (quantity == null || netValue == null) {
            return null;
        }

        String externalId = "TRADING212:" + orderId;

        return Transaction.builder()
                .externalId(externalId)
                .broker(BrokerType.TRADING212)
                .sourceRowIndex(rowIndex)
                .dateTime(dateTime)
                .type(type)
                .isin(isin)
                .ticker(ticker)
                .name(ticker)
                .quantity(quantity)
                .pricePerShare(pricePerShare)
                .currency("EUR")
                .exchangeRateToEur(BigDecimal.ONE)
                .feesEur(fees.setScale(4, RoundingMode.HALF_UP))
                .totalEur(netValue.setScale(4, RoundingMode.HALF_UP))
                .build();
    }

    private BigDecimal extractTrailingEurAmount(String rest) {
        Matcher m = TRAILING_EUR_AMOUNT.matcher(rest);
        BigDecimal last = null;
        while (m.find()) {
            last = parseHrDecimal(m.group(1));
        }
        return last;
    }

    /** Hrvatski format broja: '.' je tisucski separator, ',' je decimalni zarez. */
    private BigDecimal parseHrDecimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String normalized = raw.trim().replace(".", "").replace(",", ".");
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
