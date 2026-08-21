package hr.porezno.taxapp.parser;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Set;

/**
 * Parsira Trading212 "Export" CSV (Menu -> History -> Export) u listu internih
 * Transaction objekata.
 *
 * Trading212 export moze imati blago razlicite nazive kolona ovisno o verziji
 * aplikacije i odabranim kategorijama, npr.:
 *   Action, Time, ISIN, Ticker, Name, No. of shares, Price / share,
 *   Currency (Price / share), Exchange rate, Total, Currency (Total),
 *   Charge amount, Currency (Charge amount), ...
 *
 * Parser namjerno cita kolone po IMENU (ne po poziciji) i tolerira da neke
 * kolone nedostaju, kako bi bio otporniji na manje razlike izmedu verzija.
 */
@Component
public class Trading212CsvParser implements StatementParser {

    private static final DateTimeFormatter TIME_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .toFormatter();

    private static final Set<String> BUY_ACTIONS = Set.of(
            "market buy", "limit buy", "stop buy", "buy"
    );
    private static final Set<String> SELL_ACTIONS = Set.of(
            "market sell", "limit sell", "stop sell", "sell"
    );
    private static final Set<String> DIVIDEND_ACTIONS = Set.of(
            "dividend (ordinary)", "dividend", "dividend (dividend)", "dividend (tax exempted)"
    );

    @Override
    public ParseResult parse(InputStream inputStream) throws IOException {
        ParseResult result = new ParseResult();

        try (var reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             CSVParser csvParser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreHeaderCase(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {

            int rowIndex = 1;
            for (CSVRecord record : csvParser) {
                mapRecord(record, rowIndex, result);
                rowIndex++;
            }
        }
        return result;
    }

    private void mapRecord(CSVRecord record, int rowIndex, ParseResult result) {
        String action = get(record, "Action");
        if (action == null) {
            result.addSkipped(rowIndex, rawContent(record), "Nedostaje 'Action' stupac ili je prazan.");
            return;
        }

        TransactionType type = resolveType(action);
        // Deposit/withdrawal/interest/tax i sl. redovi nisu relevantni za FIFO obracun -
        // ovo je NAMJERNO filtriranje (ocekivano), ne kvaliteta-podataka problem, pa se
        // ne stavlja u karantenu.
        if (type == TransactionType.OTHER) {
            return;
        }

        String isin = get(record, "ISIN");
        String ticker = get(record, "Ticker");
        String name = get(record, "Name");
        String currency = firstNonNull(
                get(record, "Currency (Price / share)"),
                get(record, "Currency (Total)"),
                "EUR"
        );

        BigDecimal quantity = parseDecimal(get(record, "No. of shares"));
        BigDecimal pricePerShare = parseDecimal(get(record, "Price / share"));
        BigDecimal exchangeRate = parseDecimalOrDefault(get(record, "Exchange rate"), BigDecimal.ONE);
        BigDecimal fees = sumFees(record);
        BigDecimal totalOriginal = parseDecimal(get(record, "Total"));

        LocalDateTime dateTime = parseDateTime(firstNonNull(get(record, "Time"), get(record, "Time (UTC)")));
        if (dateTime == null) {
            // Bez datuma se transakcija ne moze pouzdano ukljuciti u FIFO obracun
            // (sortiranje/usporedba datuma bi pukla) - red ide u karantenu.
            result.addSkipped(rowIndex, rawContent(record), "Ne mogu parsirati datum/vrijeme (stupac 'Time' ili 'Time (UTC)').");
            return;
        }
        if (quantity == null) {
            result.addSkipped(rowIndex, rawContent(record), "Nedostaje ili je nevaljana kolicina ('No. of shares').");
            return;
        }
        if (totalOriginal == null) {
            result.addSkipped(rowIndex, rawContent(record), "Nedostaje ili je nevaljan ukupan iznos ('Total').");
            return;
        }

        BigDecimal totalEur = toEur(totalOriginal, exchangeRate, currency);
        BigDecimal feesEur = toEur(fees, exchangeRate, currency);

        String externalId = get(record, "ID");
        if (externalId == null) {
            // Bez stabilnog vanjskog ID-a ne mozemo pouzdano deduplicirati pri
            // ponovnom uploadu - koristimo kombinaciju polja kao fallback.
            externalId = String.join("|",
                    String.valueOf(dateTime), String.valueOf(isin), String.valueOf(type),
                    String.valueOf(quantity), String.valueOf(totalEur));
        }
        externalId = "TRADING212:" + externalId;

        result.addTransaction(Transaction.builder()
                .externalId(externalId)
                .broker(BrokerType.TRADING212)
                .sourceRowIndex(rowIndex)
                .dateTime(dateTime)
                .type(type)
                .isin(isin)
                .ticker(ticker)
                .name(name)
                .quantity(quantity)
                .pricePerShare(pricePerShare)
                .currency(currency)
                .exchangeRateToEur(exchangeRate)
                .feesEur(feesEur)
                .totalEur(totalEur)
                .build());
    }

    private String rawContent(CSVRecord record) {
        return String.join(", ", record);
    }

    private TransactionType resolveType(String action) {
        String normalized = action.trim().toLowerCase();
        if (BUY_ACTIONS.contains(normalized)) return TransactionType.BUY;
        if (SELL_ACTIONS.contains(normalized)) return TransactionType.SELL;
        if (DIVIDEND_ACTIONS.contains(normalized) || normalized.startsWith("dividend")) {
            return TransactionType.DIVIDEND;
        }
        return TransactionType.OTHER;
    }

    /** Zbraja sve moguce naknade (charge/transaction/currency conversion fee) ako postoje kolone. */
    private BigDecimal sumFees(CSVRecord record) {
        BigDecimal total = BigDecimal.ZERO;
        for (String col : List.of("Charge amount", "Transaction fee", "Finra fee", "Stamp duty reserve tax", "Currency conversion fee")) {
            BigDecimal val = parseDecimal(get(record, col));
            if (val != null) {
                total = total.add(val.abs());
            }
        }
        return total;
    }

    /**
     * Trading212 export "Total" je vec u valuti racuna (obicno EUR) kad postoji
     * Exchange rate kolona koja pokazuje konverziju original->racun valuta.
     * Ako Exchange rate nije prisutan (1.0 default) i currency nije EUR,
     * ne mozemo pouzdano konvertirati bez dodatnog izvora tecaja (HNB) -
     * u tom slucaju vracamo originalni iznos i oznacavamo ga kao "best effort".
     */
    private BigDecimal toEur(BigDecimal amount, BigDecimal exchangeRate, String currency) {
        if (amount == null) return null;
        if ("EUR".equalsIgnoreCase(currency) || exchangeRate == null) {
            return amount.setScale(4, RoundingMode.HALF_UP);
        }
        // Trading212 "Total" stupac je vec preracunat u valutu racuna u praksi;
        // ako se ipak treba eksplicitna konverzija, ovdje je mjesto za to.
        return amount.setScale(4, RoundingMode.HALF_UP);
    }

    private LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();

        // Trading212 export koristi "yyyy-MM-dd HH:mm:ss+00:00" (offset, ne 'Z').
        try {
            return OffsetDateTime.parse(trimmed.replace(" ", "T")).toLocalDateTime();
        } catch (Exception ignored) {
            // probaj dalje
        }
        try {
            return LocalDateTime.parse(trimmed, TIME_FORMAT);
        } catch (Exception ignored) {
            // probaj dalje
        }
        try {
            return LocalDateTime.parse(trimmed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseDecimalOrDefault(String raw, BigDecimal fallback) {
        BigDecimal parsed = parseDecimal(raw);
        return parsed != null ? parsed : fallback;
    }

    private String get(CSVRecord record, String column) {
        try {
            if (!record.isMapped(column)) return null;
            String value = record.get(column);
            return (value == null || value.isBlank()) ? null : value.trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }
}
