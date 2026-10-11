package hr.porezno.taxapp.parser;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import hr.porezno.taxapp.service.HnbExchangeRateService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsira Strike CSV export transakcija (Bitcoin kupnje/prodaje) u BUY/SELL transakcije.
 *
 * Format je verificiran na stvarnom Strike "Account statement" exportu (zaglavlje: Reference, Date & Time (UTC),
 * Transaction Type, Amount EUR, Fee EUR, Amount BTC, ..., Cost Basis (EUR), Destination, ...). Parser trazi stupce po NAZIVU (regex), tolerira razlicite nazive/redoslijed, a sve sto ne
 * prepozna a izgleda kao kupnja/prodaja ide u karantenu s razlogom. Ako se stvarni export
 * razlikuje, dovoljno je poslati zaglavlje + 2-3 retka i prilagodi se regex.
 *
 * Prepoznavanje:
 *  - vrsta: "purchase"/"buy" -> BUY, "sale"/"sell" -> SELL, "exchange"/"trade" -> po predznaku BTC iznosa
 *  - deposit/withdrawal/send/receive/transfer -> NAMJERNO preskoceno: prijenos na vlastiti cold wallet
 *    (i natrag) NIJE porezni dogadjaj. Slanje BTC TRECOJ osobi/placanje bi bio (ovdje se ne razlikuje).
 *  - iznos u fiatu: stupac "Amount EUR" / "Amount USD" ...; USD se pretvara HNB tecajem na dan transakcije
 *  - naknada: "Fee EUR"/"Fee USD"; app.strike.fee-included-in-amount (default true) kaze je li naknada
 *    VEC sadrzana u iznosu (true) ili se kod kupnje dodaje, a kod prodaje oduzima (false)
 *  - vrijeme: pretpostavlja se UTC (osim ako ima offset) pa se pretvara u Europe/Zagreb
 */
@Component
public class StrikeCsvParser implements StatementParser {

    static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");

    private static final Pattern ID_HEADER = Pattern.compile("(?i)^(transaction\\s*id|id|reference|txid)$");
    private static final Pattern TIME_HEADER = Pattern.compile(
            "(?i)^(time(\\s*\\(utc\\))?|date(\\s*(&|and)\\s*time)?(\\s*\\(utc\\))?|timestamp|(completed|initiated)\\s*date(\\s*\\(utc\\))?)$");
    private static final Pattern TYPE_HEADER = Pattern.compile("(?i)^(transaction\\s*type|type)$");
    private static final Pattern STATUS_HEADER = Pattern.compile("(?i)^status$");
    private static final Pattern CRYPTO_AMOUNT_HEADER = Pattern.compile("(?i)^(amount\\s*\\(?(btc)\\)?|btc\\s*amount|btc)$");
    private static final Pattern FIAT_AMOUNT_HEADER = Pattern.compile("(?i)^amount\\s*\\(?(eur|usd|gbp|chf)\\)?$");
    private static final Pattern FEE_HEADER = Pattern.compile("(?i)^fee\\s*\\(?(eur|usd|gbp|chf)\\)?$");

    private static final Set<String> COMPLETED = Set.of("completed", "complete", "success", "successful", "settled", "done");
    private static final List<String> TRANSFER_KEYWORDS = List.of(
            "deposit", "withdraw", "send", "sent", "receive", "transfer", "on-chain", "onchain", "lightning", "top up", "top-up");

    private static final List<DateTimeFormatter> LOCAL_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("MMM dd yyyy HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d yyyy HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm:ss a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));

    private final HnbExchangeRateService exchangeRateService;
    private final boolean feeIncludedInAmount;

    public StrikeCsvParser(HnbExchangeRateService exchangeRateService,
                           @Value("${app.strike.fee-included-in-amount:true}") boolean feeIncludedInAmount) {
        this.exchangeRateService = exchangeRateService;
        this.feeIncludedInAmount = feeIncludedInAmount;
    }

    @Override
    public ParseResult parse(InputStream inputStream) throws IOException {
        ParseResult result = new ParseResult();

        try (var reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             CSVParser csv = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true).setTrim(true).setIgnoreEmptyLines(true)
                     .build().parse(reader)) {

            List<String> headers = csv.getHeaderNames();
            String idCol = findHeader(headers, ID_HEADER);
            String timeCol = findHeader(headers, TIME_HEADER);
            String typeCol = findHeader(headers, TYPE_HEADER);
            String statusCol = findHeader(headers, STATUS_HEADER);
            String btcCol = findHeader(headers, CRYPTO_AMOUNT_HEADER);
            String fiatCol = findHeader(headers, FIAT_AMOUNT_HEADER);
            String feeCol = findHeader(headers, FEE_HEADER);

            if (timeCol == null || typeCol == null || btcCol == null || fiatCol == null) {
                throw new IllegalArgumentException("Strike CSV: nisam pronasao obavezne stupce (vrijeme, vrsta transakcije, "
                        + "iznos u BTC, iznos u fiatu). Pronadjeni stupci: " + headers
                        + ". Posalji zaglavlje + par redaka pa prilagodimo parser.");
            }
            String currency = currencyOf(fiatCol);

            int rowIndex = 1;
            for (CSVRecord record : csv) {
                mapRecord(record, rowIndex++, result, idCol, timeCol, typeCol, statusCol, btcCol, fiatCol, feeCol, currency);
            }
        }
        return result;
    }

    private void mapRecord(CSVRecord record, int rowIndex, ParseResult result, String idCol, String timeCol,
                           String typeCol, String statusCol, String btcCol, String fiatCol, String feeCol, String currency) {
        String typeRaw = get(record, typeCol);
        String type = typeRaw == null ? "" : typeRaw.toLowerCase();
        BigDecimal btc = parseDecimal(get(record, btcCol));

        TransactionType txType = resolveType(type, btc);
        if (txType == TransactionType.OTHER) {
            boolean transfer = TRANSFER_KEYWORDS.stream().anyMatch(type::contains);
            // Prijenosi (cold wallet) su namjerno izvan obracuna; sve ostalo s BTC iznosom je sumnjivo -> karantena.
            if (!transfer && btc != null && btc.signum() != 0) {
                result.addSkipped(rowIndex, raw(record), "Nepoznata vrsta transakcije '" + typeRaw + "' - ne mogu odrediti je li kupnja ili prodaja.");
            }
            return;
        }

        if (statusCol != null) {
            String status = get(record, statusCol);
            if (status != null && !COMPLETED.contains(status.toLowerCase())) {
                result.addSkipped(rowIndex, raw(record), "Status transakcije je '" + status + "' (nije zavrsena) - preskoceno.");
                return;
            }
        }

        LocalDateTime dateTime = parseDateTime(get(record, timeCol));
        if (dateTime == null) {
            result.addSkipped(rowIndex, raw(record), "Ne mogu parsirati datum/vrijeme.");
            return;
        }
        BigDecimal fiat = parseDecimal(get(record, fiatCol));
        if (btc == null || btc.signum() == 0 || fiat == null) {
            result.addSkipped(rowIndex, raw(record), "Nedostaje ili je nevaljan iznos u BTC ili u " + currency + ".");
            return;
        }
        btc = btc.abs();
        fiat = fiat.abs();
        BigDecimal fee = feeCol == null ? BigDecimal.ZERO : parseDecimalOrZero(get(record, feeCol)).abs();

        BigDecimal totalOriginal = feeIncludedInAmount ? fiat
                : (txType == TransactionType.BUY ? fiat.add(fee) : fiat.subtract(fee));

        BigDecimal rate;
        BigDecimal totalEur;
        BigDecimal feeEur;
        if ("EUR".equals(currency)) {
            rate = BigDecimal.ONE;
            totalEur = totalOriginal;
            feeEur = fee;
        } else {
            BigDecimal hnb = exchangeRateService.getRate(currency, dateTime.toLocalDate());
            if (hnb == null || hnb.signum() <= 0) {
                rate = BigDecimal.ZERO; // sentinel: ReportController salje u karantenu
                totalEur = totalOriginal;
                feeEur = fee;
            } else {
                rate = hnb;
                totalEur = totalOriginal.divide(hnb, 4, RoundingMode.HALF_UP);
                feeEur = fee.divide(hnb, 4, RoundingMode.HALF_UP);
            }
        }

        String id = idCol == null ? null : get(record, idCol);
        String externalId = "STRIKE:" + (id != null ? id
                : String.join("|", String.valueOf(dateTime), String.valueOf(txType), btc.toPlainString(), fiat.toPlainString()));

        result.addTransaction(Transaction.builder()
                .externalId(externalId)
                .broker(BrokerType.STRIKE)
                .sourceRowIndex(rowIndex)
                .dateTime(dateTime)
                .type(txType)
                .ticker("BTC")
                .name("Bitcoin")
                .quantity(btc)
                .pricePerShare(totalOriginal.divide(btc, 8, RoundingMode.HALF_UP))
                .currency(currency)
                .exchangeRateToEur(rate)
                .feesEur(feeEur)
                .totalEur(totalEur)
                .build());
    }

    private TransactionType resolveType(String type, BigDecimal btc) {
        if (type.contains("purchase") || type.contains("buy")) return TransactionType.BUY;
        if (type.contains("sale") || type.contains("sell")) return TransactionType.SELL;
        if ((type.contains("exchange") || type.contains("trade") || type.contains("convert")) && btc != null && btc.signum() != 0) {
            return btc.signum() > 0 ? TransactionType.BUY : TransactionType.SELL;
        }
        return TransactionType.OTHER;
    }

    private LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        try {
            return OffsetDateTime.parse(t.replace(" ", "T")).atZoneSameInstant(ZAGREB).toLocalDateTime();
        } catch (Exception ignored) { /* probaj dalje */ }
        try {
            return LocalDateTime.parse(t.replace(" ", "T")).atOffset(ZoneOffset.UTC).atZoneSameInstant(ZAGREB).toLocalDateTime();
        } catch (Exception ignored) { /* probaj dalje */ }
        for (DateTimeFormatter f : LOCAL_FORMATS) {
            try {
                return LocalDateTime.parse(t, f).atOffset(ZoneOffset.UTC).atZoneSameInstant(ZAGREB).toLocalDateTime();
            } catch (Exception ignored) { /* probaj sljedeci */ }
        }
        return null;
    }

    private String findHeader(List<String> headers, Pattern p) {
        for (String h : headers) {
            if (h != null && p.matcher(h.trim()).matches()) return h;
        }
        return null;
    }

    private String currencyOf(String fiatHeader) {
        Matcher m = FIAT_AMOUNT_HEADER.matcher(fiatHeader.trim());
        return m.matches() ? m.group(1).toUpperCase() : "EUR";
    }

    private String get(CSVRecord record, String column) {
        try {
            String v = record.get(column);
            return v == null || v.isBlank() ? null : v.trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private BigDecimal parseDecimal(String raw) {
        if (raw == null) return null;
        try {
            return new BigDecimal(raw.replace(",", "").replaceAll("[^0-9.\\-+eE]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseDecimalOrZero(String raw) {
        BigDecimal v = parseDecimal(raw);
        return v == null ? BigDecimal.ZERO : v;
    }

    private String raw(CSVRecord record) {
        return String.join(", ", record);
    }
}
