package hr.porezno.taxapp.parser;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import hr.porezno.taxapp.service.HnbExchangeRateService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;

/**
 * Parsira Interactive Brokers "Flex Query" CSV export (Trades sekcija).
 *
 * Za razliku od Trading212/Revolut, IBKR Flex Query je KONFIGURABILAN -
 * korisnik sam bira koje kolone ukljuciti pri kreiranju upita (Reports ->
 * Flex Queries -> Trades). Zato ovaj parser trazi VISE mogucih naziva za
 * svako polje (umjesto jednog fiksnog), i baca jasnu gresku ako nedostaje
 * neko obavezno polje - da korisnik zna koje polje dodati u Flex Query.
 *
 * Preporucena minimalna konfiguracija Flex Querya (Trades -> Execution):
 * Symbol, ISIN, TradeDate (ili Date/Time), Buy/Sell, Quantity, TradePrice,
 * CurrencyPrimary, IBCommission, AssetCategory, TradeID.
 *
 * NAPOMENA: ovaj parser NIJE testiran na stvarnom IBKR exportu - baziran
 * je na dokumentiranom Flex Query formatu. Opcije/futures (AssetCategory
 * != STK) se trenutno PRESKACU (drugaciji porezni tretman i multiplier
 * komplikacije) - obrada samo dionica/ETF-ova (STK).
 */
@Component
public class IbkrCsvParser implements StatementParser {

    private static final DateTimeFormatter DATE_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("yyyyMMdd")
            .toFormatter();

    private static final List<String> SYMBOL_COLUMNS = List.of("Symbol", "Ticker");
    private static final List<String> ISIN_COLUMNS = List.of("ISIN");
    private static final List<String> DATE_COLUMNS = List.of("TradeDate", "Trade Date", "Date/Time", "DateTime");
    private static final List<String> SIDE_COLUMNS = List.of("Buy/Sell", "Buy Sell");
    private static final List<String> QUANTITY_COLUMNS = List.of("Quantity");
    private static final List<String> PRICE_COLUMNS = List.of("TradePrice", "Trade Price");
    private static final List<String> CURRENCY_COLUMNS = List.of("CurrencyPrimary", "Currency");
    private static final List<String> COMMISSION_COLUMNS = List.of("IBCommission", "Commission");
    private static final List<String> ASSET_CATEGORY_COLUMNS = List.of("AssetCategory", "Asset Category");
    private static final List<String> TRADE_ID_COLUMNS = List.of("TradeID", "IBOrderID", "OrderID", "TransactionID");

    private final HnbExchangeRateService exchangeRateService;

    public IbkrCsvParser(HnbExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

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

            List<String> headers = csvParser.getHeaderNames();
            requireAnyColumn(headers, SYMBOL_COLUMNS, "Symbol");
            requireAnyColumn(headers, DATE_COLUMNS, "TradeDate / Date/Time");
            requireAnyColumn(headers, QUANTITY_COLUMNS, "Quantity");
            requireAnyColumn(headers, PRICE_COLUMNS, "TradePrice");

            int rowIndex = 1;
            for (CSVRecord record : csvParser) {
                mapRecord(record, rowIndex, result);
                rowIndex++;
            }
        }
        return result;
    }

    private void requireAnyColumn(List<String> headers, List<String> candidates, String humanName) {
        boolean found = candidates.stream().anyMatch(c -> containsIgnoreCase(headers, c));
        if (!found) {
            throw new IllegalArgumentException(
                    "IBKR CSV ne sadrzi obavezno polje '" + humanName + "' (probani nazivi: "
                            + String.join(", ", candidates) + "). Provjeri Flex Query konfiguraciju "
                            + "(Reports -> Flex Queries -> Trades) i ukljuci ovo polje.");
        }
    }

    private boolean containsIgnoreCase(List<String> headers, String name) {
        return headers.stream().anyMatch(h -> h.equalsIgnoreCase(name));
    }

    private void mapRecord(CSVRecord record, int rowIndex, ParseResult result) {
        String assetCategory = getFirst(record, ASSET_CATEGORY_COLUMNS);
        if (assetCategory != null && !assetCategory.equalsIgnoreCase("STK")) {
            // Opcije/futures/forex nisu jos podrzani (drugaciji porezni tretman u HR) -
            // NAMJERNO filtriranje, ne kvaliteta-podataka problem, pa se ne stavlja u karantenu.
            return;
        }

        String ticker = getFirst(record, SYMBOL_COLUMNS);
        String isin = getFirst(record, ISIN_COLUMNS);
        String currency = firstNonNull(getFirst(record, CURRENCY_COLUMNS), "EUR");

        BigDecimal quantity = parseDecimal(getFirst(record, QUANTITY_COLUMNS));
        if (quantity == null) {
            result.addSkipped(rowIndex, rawContent(record), "Nedostaje ili je nevaljana 'Quantity'.");
            return;
        }

        TransactionType type = resolveType(record, quantity);
        if (type == TransactionType.OTHER) {
            result.addSkipped(rowIndex, rawContent(record), "Ne mogu odrediti smjer transakcije (BUY/SELL) - nema 'Buy/Sell' stupca niti predznaka u Quantity.");
            return;
        }
        // Za FIFO racunamo s pozitivnom kolicinom (smjer je vec u "type").
        quantity = quantity.abs();

        BigDecimal pricePerShare = parseDecimal(getFirst(record, PRICE_COLUMNS));
        BigDecimal commission = parseDecimalOrDefault(getFirst(record, COMMISSION_COLUMNS), BigDecimal.ZERO).abs();

        LocalDateTime dateTime = parseDate(getFirst(record, DATE_COLUMNS));
        if (dateTime == null) {
            result.addSkipped(rowIndex, rawContent(record), "Ne mogu parsirati datum (TradeDate / Date/Time).");
            return;
        }

        BigDecimal grossValue = quantity.multiply(pricePerShare != null ? pricePerShare : BigDecimal.ZERO);
        // Total (u valuti trgovanja): za BUY trosak = gross + provizija, za SELL prihod = gross - provizija.
        BigDecimal totalOriginal = type == TransactionType.BUY
                ? grossValue.add(commission)
                : grossValue.subtract(commission);

        ConversionResult conv = convert(totalOriginal, currency, dateTime.toLocalDate());
        ConversionResult feeConv = convert(commission, currency, dateTime.toLocalDate());

        String tradeId = getFirst(record, TRADE_ID_COLUMNS);
        String externalId = "IBKR:" + (tradeId != null ? tradeId : String.join("|",
                String.valueOf(dateTime), String.valueOf(isin != null ? isin : ticker), String.valueOf(type),
                String.valueOf(quantity), String.valueOf(pricePerShare)));

        result.addTransaction(Transaction.builder()
                .externalId(externalId)
                .broker(BrokerType.IBKR)
                .sourceRowIndex(rowIndex)
                .dateTime(dateTime)
                .type(type)
                .isin(isin)
                .ticker(ticker)
                .name(ticker)
                .quantity(quantity)
                .pricePerShare(pricePerShare)
                .currency(currency)
                .exchangeRateToEur(conv.rateUsed())
                .feesEur(feeConv.amountEur())
                .totalEur(conv.amountEur())
                .build());
    }

    private String rawContent(CSVRecord record) {
        return String.join(", ", record);
    }

    /**
     * Pretvara iznos u EUR preko HNB tecaja na dani datum. Ako konverzija ne
     * uspije, vraca SIROV iznos (fallback) uz rateUsed=ZERO kao signal "nije
     * konvertirano" (ZERO je siguran sentinel jer stvarni tecaj nikad nije 0).
     */
    private ConversionResult convert(BigDecimal amount, String currency, LocalDate date) {
        if ("EUR".equalsIgnoreCase(currency)) {
            return new ConversionResult(amount, BigDecimal.ONE);
        }
        if (amount == null) {
            return new ConversionResult(null, BigDecimal.ZERO);
        }
        BigDecimal rate = exchangeRateService.getRate(currency.toUpperCase(), date);
        if (rate == null || rate.signum() <= 0) {
            return new ConversionResult(amount, BigDecimal.ZERO);
        }
        BigDecimal converted = amount.divide(rate, 6, RoundingMode.HALF_UP);
        return new ConversionResult(converted, rate);
    }

    private record ConversionResult(BigDecimal amountEur, BigDecimal rateUsed) {}

    private TransactionType resolveType(CSVRecord record, BigDecimal quantity) {
        String side = getFirst(record, SIDE_COLUMNS);
        if (side != null) {
            String normalized = side.trim().toLowerCase();
            if (normalized.startsWith("buy")) return TransactionType.BUY;
            if (normalized.startsWith("sell")) return TransactionType.SELL;
        }
        // Fallback: IBKR cesto ne salje posebnu Buy/Sell kolonu, nego predznak
        // u Quantity (negativno = prodaja).
        if (quantity.signum() > 0) return TransactionType.BUY;
        if (quantity.signum() < 0) return TransactionType.SELL;
        return TransactionType.OTHER;
    }

    private LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();

        // IBKR "TradeDate" je cesto samo datum u formatu yyyyMMdd (bez vremena).
        try {
            LocalDate date = LocalDate.parse(trimmed, DATE_FORMAT);
            return date.atStartOfDay();
        } catch (Exception ignored) {
            // probaj dalje
        }
        // "Date/Time" polje moze biti "2024-01-15;10:30:00" ili slicno - IBKR
        // cesto koristi ';' kao separator datuma i vremena unutar polja.
        try {
            String normalized = trimmed.replace(';', ' ').replace('/', '-');
            return LocalDateTime.parse(normalized.replace(" ", "T"));
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

    private String getFirst(CSVRecord record, List<String> candidateColumns) {
        for (String col : candidateColumns) {
            String value = get(record, col);
            if (value != null) return value;
        }
        return null;
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

    private String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
