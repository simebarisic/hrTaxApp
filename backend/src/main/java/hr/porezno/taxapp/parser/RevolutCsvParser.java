package hr.porezno.taxapp.parser;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import hr.porezno.taxapp.service.HnbExchangeRateService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsira Revolut "Tax Report" CSV export (Trading -> Statements -> Tax Report).
 *
 * Za razliku od Trading212/IBKR, ovaj export NE daje sirov niz pojedinacnih
 * BUY/SELL transakcija - Revolut vec sam interno upari kupnje i prodaje pa
 * izvjestaj sadrzi VISE SEKCIJA odvojenih praznim retkom, svaka s vlastitim
 * naslovom i zaglavljem kolona, npr.:
 *
 *   Income from Sells
 *   Date acquired,Date sold,Symbol,Security name,ISIN,Country,Quantity,Cost basis,Gross proceeds,Gross PnL,Currency
 *   2024-12-04,2025-12-15,TSLA,Tesla,US88160R1014,US,0.00886585,3.13,4.16,1.03,USD
 *   ...
 *
 *   Other income & fees
 *   Date,Symbol,Security name,ISIN,Country,Gross amount,Withholding tax,Net Amount,Currency
 *   2026-03-27,GRMN,Garmin dividend,CH0114405324,CH,0.01,$0,€0.01,EUR
 *
 * Za "Income from Sells" retke, svaki redak vec IMA tocan datum stjecanja i
 * datum prodaje - pretvaramo ga u sintetski BUY (na datum stjecanja, iznos =
 * Cost basis) + SELL (na datum prodaje, iznos = Gross proceeds) par, s
 * zajednickim "lotGroupKey" koji garantira da FIFO ovaj par upari SAMO sam sa
 * sobom (vidi Transaction.lotGroupKey i FifoMatchingService).
 *
 * NAPOMENA O VALUTI: iznosi (Cost basis, Gross proceeds, Net Amount) su cesto
 * u IZVORNOJ valuti (npr. USD), ne EUR - pretvaraju se u EUR preko
 * HnbExchangeRateService (HNB srednji tecaj na dan transakcije). Ako
 * konverzija ne uspije (HNB nedostupan i sl.), koristi se sirov iznos kao
 * fallback, a Transaction.exchangeRateToEur se postavlja na ZERO kao signal
 * "konverzija nije uspjela" - ReportController to koristi za upozorenje.
 *
 * NAPOMENA: format je poznat samo iz ovog jednog stvarnog uzorka (djelomican
 * export) - ako puni godisnji export ima dodatne sekcije (npr. "Income from
 * Interest", "Custody fee" i sl.), nepoznate sekcije se trenutno TIHO
 * PRESKACU (ne bacaju gresku) dok se ne vide njihovi stvarni nazivi/kolone.
 */
@Component
public class RevolutCsvParser implements StatementParser {

    private final HnbExchangeRateService exchangeRateService;

    public RevolutCsvParser(HnbExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    @Override
    public ParseResult parse(InputStream inputStream) throws IOException {
        ParseResult result = new ParseResult();

        List<List<String>> blocks = splitIntoBlocks(inputStream);

        int rowIndex = 1;
        for (List<String> block : blocks) {
            if (block.isEmpty()) continue;
            String title = block.get(0).trim();
            if (block.size() < 2) continue; // nema ni zaglavlja ni podataka

            String normalizedTitle = title.toLowerCase();
            List<String> rest = block.subList(1, block.size());

            if (normalizedTitle.startsWith("income from sells")) {
                rowIndex = parseIncomeFromSells(rest, result, rowIndex);
            } else if (normalizedTitle.startsWith("other income")) {
                rowIndex = parseOtherIncome(rest, result, rowIndex);
            } else {
                // Nepoznata sekcija (npr. "Income from Interest", "Custody fee") -
                // nije jos videna u stvarnom exportu pa se ne obraduje, ali
                // korisnik treba znati da postoji i da je preskocena.
                result.addSkipped(rowIndex, title, "Nepoznata sekcija u Revolut izvjestaju - preskocena (" + rest.size() + " redaka).");
            }
        }

        return result;
    }

    /** Datoteku dijeli na blokove odvojene praznim retkom; svaki blok pocinje naslovom sekcije. */
    private List<List<String>> splitIntoBlocks(InputStream inputStream) throws IOException {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    if (!current.isEmpty()) {
                        blocks.add(current);
                        current = new ArrayList<>();
                    }
                } else {
                    current.add(line);
                }
            }
        }
        if (!current.isEmpty()) {
            blocks.add(current);
        }
        return blocks;
    }

    private int parseIncomeFromSells(List<String> lines, ParseResult result, int rowIndex) throws IOException {
        for (CSVRecord record : parseCsvLines(lines)) {
            LocalDate acquired = parseIsoDate(get(record, "Date acquired"));
            LocalDate sold = parseIsoDate(get(record, "Date sold"));
            String isin = get(record, "ISIN");
            String ticker = get(record, "Symbol");
            String name = get(record, "Security name");
            String currency = firstNonNull(get(record, "Currency"), "EUR");
            BigDecimal quantity = parseDecimal(get(record, "Quantity"));
            BigDecimal costBasis = parseDecimal(get(record, "Cost basis"));
            BigDecimal grossProceeds = parseDecimal(get(record, "Gross proceeds"));

            if (acquired == null || sold == null || quantity == null || costBasis == null || grossProceeds == null) {
                result.addSkipped(rowIndex, rawContent(record),
                        "Nedostaje jedno ili vise obaveznih polja (Date acquired/Date sold/Quantity/Cost basis/Gross proceeds).");
                rowIndex++;
                continue;
            }

            String lotGroupKey = "REV-LOT:" + firstNonNull(isin, ticker) + ":" + acquired + ":" + sold + ":" + quantity;

            ConversionResult buyConv = convert(costBasis, currency, acquired);
            ConversionResult sellConv = convert(grossProceeds, currency, sold);

            BigDecimal pricePerShareBuy = quantity.signum() != 0 ? costBasis.divide(quantity, 8, RoundingMode.HALF_UP) : null;
            BigDecimal pricePerShareSell = quantity.signum() != 0 ? grossProceeds.divide(quantity, 8, RoundingMode.HALF_UP) : null;

            result.addTransaction(Transaction.builder()
                    .externalId("REVOLUT:BUY:" + lotGroupKey)
                    .broker(BrokerType.REVOLUT)
                    .lotGroupKey(lotGroupKey)
                    .sourceRowIndex(rowIndex)
                    .dateTime(acquired.atStartOfDay())
                    .type(TransactionType.BUY)
                    .isin(isin)
                    .ticker(ticker)
                    .name(name)
                    .quantity(quantity)
                    .pricePerShare(pricePerShareBuy)
                    .currency(currency)
                    .exchangeRateToEur(buyConv.rateUsed())
                    .feesEur(BigDecimal.ZERO)
                    .totalEur(buyConv.amountEur())
                    .build());

            result.addTransaction(Transaction.builder()
                    .externalId("REVOLUT:SELL:" + lotGroupKey)
                    .broker(BrokerType.REVOLUT)
                    .lotGroupKey(lotGroupKey)
                    .sourceRowIndex(rowIndex)
                    .dateTime(sold.atStartOfDay())
                    .type(TransactionType.SELL)
                    .isin(isin)
                    .ticker(ticker)
                    .name(name)
                    .quantity(quantity)
                    .pricePerShare(pricePerShareSell)
                    .currency(currency)
                    .exchangeRateToEur(sellConv.rateUsed())
                    .feesEur(BigDecimal.ZERO)
                    .totalEur(sellConv.amountEur())
                    .build());

            rowIndex++;
        }
        return rowIndex;
    }

    private int parseOtherIncome(List<String> lines, ParseResult result, int rowIndex) throws IOException {
        // Dvije identicne dividende istog dana (isti ISIN i iznos) ne smiju se spojiti u jednu pri deduplikaciji -
        // redni broj pojavljivanja unutar datoteke je stabilan i pri ponovnom uploadu iste datoteke.
        java.util.Map<String, Integer> occurrences = new java.util.HashMap<>();
        for (CSVRecord record : parseCsvLines(lines)) {
            LocalDate date = parseIsoDate(get(record, "Date"));
            String isin = get(record, "ISIN");
            String ticker = get(record, "Symbol");
            String name = get(record, "Security name");
            String currency = firstNonNull(get(record, "Currency"), "EUR");
            BigDecimal netAmount = parseMoneyWithSymbol(get(record, "Net Amount"));

            if (date == null || netAmount == null) {
                result.addSkipped(rowIndex, rawContent(record), "Nedostaje 'Date' ili 'Net Amount'.");
                rowIndex++;
                continue;
            }

            ConversionResult conv = convert(netAmount, currency, date);
            String baseId = "REVOLUT:DIV:" + date + ":" + firstNonNull(isin, ticker) + ":" + netAmount;
            int occurrence = occurrences.merge(baseId, 1, Integer::sum);
            String externalId = occurrence == 1 ? baseId : baseId + "#" + occurrence;

            result.addTransaction(Transaction.builder()
                    .externalId(externalId)
                    .broker(BrokerType.REVOLUT)
                    .sourceRowIndex(rowIndex)
                    .dateTime(date.atStartOfDay())
                    .type(TransactionType.DIVIDEND)
                    .isin(isin)
                    .ticker(ticker)
                    .name(name)
                    .currency(currency)
                    .exchangeRateToEur(conv.rateUsed())
                    .feesEur(BigDecimal.ZERO)
                    .totalEur(conv.amountEur())
                    .build());

            rowIndex++;
        }
        return rowIndex;
    }

    /**
     * Pretvara iznos u EUR preko HNB tecaja na dani datum. Ako konverzija ne
     * uspije, vraca SIROV iznos (fallback, bolje netocan broj u UI-ju nego
     * potpuno izgubljen redak) uz rateUsed=ZERO kao signal "nije konvertirano"
     * (ZERO je siguran sentinel jer stvarni tecaj nikad nije 0).
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

    private String rawContent(CSVRecord record) {
        return String.join(", ", record);
    }

    private Iterable<CSVRecord> parseCsvLines(List<String> lines) throws IOException {
        String joined = String.join("\n", lines);
        CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .build()
                .parse(new StringReader(joined));
        return parser.getRecords();
    }

    private LocalDate parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
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

    /** Skida simbole valute (€, $ i sl.) prije parsiranja - Revolut ih ponekad ulijepi u sam iznos. */
    private BigDecimal parseMoneyWithSymbol(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim().replaceAll("[^0-9,.\\-]", "");
        return parseDecimal(cleaned);
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
