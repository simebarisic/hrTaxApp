package hr.porezno.taxapp.parser;

import hr.porezno.taxapp.model.BrokerType;
import org.springframework.stereotype.Component;

/**
 * Bira ispravan StatementParser prema odabranom brokeru i ekstenziji
 * uploadane datoteke. Baca IllegalArgumentException s jasnom porukom za
 * nepodrzane kombinacije (npr. Revolut + PDF, jos nije implementirano).
 */
@Component
public class ParserResolver {

    private final Trading212CsvParser trading212CsvParser;
    private final Trading212PdfParser trading212PdfParser;
    private final RevolutCsvParser revolutCsvParser;
    private final IbkrCsvParser ibkrCsvParser;
    private final StrikeCsvParser strikeCsvParser;

    public ParserResolver(Trading212CsvParser trading212CsvParser,
                           Trading212PdfParser trading212PdfParser,
                           RevolutCsvParser revolutCsvParser,
                           IbkrCsvParser ibkrCsvParser,
                           StrikeCsvParser strikeCsvParser) {
        this.trading212CsvParser = trading212CsvParser;
        this.trading212PdfParser = trading212PdfParser;
        this.revolutCsvParser = revolutCsvParser;
        this.ibkrCsvParser = ibkrCsvParser;
        this.strikeCsvParser = strikeCsvParser;
    }

    public StatementParser resolve(BrokerType broker, String filenameLower) {
        boolean isCsv = filenameLower.endsWith(".csv");
        boolean isPdf = filenameLower.endsWith(".pdf");

        if (!isCsv && !isPdf) {
            throw new IllegalArgumentException("Podrzane ekstenzije su .csv i .pdf (ovisno o brokeru).");
        }

        return switch (broker) {
            case TRADING212 -> {
                if (isCsv) yield trading212CsvParser;
                if (isPdf) yield trading212PdfParser;
                throw unsupported(broker, filenameLower);
            }
            case REVOLUT -> {
                if (isCsv) yield revolutCsvParser;
                throw new IllegalArgumentException(
                        "Za Revolut je trenutno podrzan samo CSV export (Trading/Crypto -> Statements -> "
                                + "Profit and Loss Statement). PDF podrska za Revolut jos nije implementirana.");
            }
            case IBKR -> {
                if (isCsv) yield ibkrCsvParser;
                throw new IllegalArgumentException(
                        "Za IBKR je trenutno podrzan samo CSV export (Flex Query). "
                                + "PDF/Activity Statement podrska jos nije implementirana.");
            }
            case STRIKE -> {
                if (isCsv) yield strikeCsvParser;
                throw new IllegalArgumentException("Za Strike je podrzan samo CSV export transakcija.");
            }
        };
    }

    private IllegalArgumentException unsupported(BrokerType broker, String filenameLower) {
        return new IllegalArgumentException("Nepodrzana kombinacija brokera i formata: " + broker + " / " + filenameLower);
    }
}
