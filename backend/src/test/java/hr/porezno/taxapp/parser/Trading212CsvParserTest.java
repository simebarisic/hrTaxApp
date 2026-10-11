package hr.porezno.taxapp.parser;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import hr.porezno.taxapp.model.ExchangeRate;
import hr.porezno.taxapp.repository.ExchangeRateRepository;
import hr.porezno.taxapp.service.HnbExchangeRateService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Trading212CsvParserTest {

    private final ExchangeRateRepository rateRepository = Mockito.mock(ExchangeRateRepository.class);
    private final Trading212CsvParser parser = new Trading212CsvParser(
            new HnbExchangeRateService(rateRepository, new com.fasterxml.jackson.databind.ObjectMapper()));

    private static final String HEADER = "Action,Time (UTC),ISIN,Ticker,Name,Notes,ID,No. of shares,"
            + "Price / share,Currency (Price / share),Exchange rate,Result,Currency (Result),Total,"
            + "Currency (Total),Withholding tax,Currency (Withholding tax),Currency conversion fee,"
            + "Currency (Currency conversion fee),Merchant name,Merchant category";

    private ParseResult parse(String csvBody) throws IOException {
        String csv = HEADER + "\n" + csvBody;
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsiraMarketBuyRedak() throws IOException {
        String row = "Market buy,2026-02-19 07:27:58+00:00,KYG9830T1067,3CP,\"Xiaomi\",,EOF46809354484,"
                + "0.2673663100,4.0020000000,EUR,1.00000000,,,1.07,\"EUR\",,,,,,";

        ParseResult result = parse(row);

        assertEquals(1, result.getTransactions().size());
        assertTrue(result.getSkippedRows().isEmpty());

        Transaction tx = result.getTransactions().get(0);
        assertEquals(TransactionType.BUY, tx.getType());
        assertEquals(BrokerType.TRADING212, tx.getBroker());
        assertEquals("KYG9830T1067", tx.getIsin());
        assertEquals("3CP", tx.getTicker());
        assertEquals(0, new java.math.BigDecimal("1.07").compareTo(tx.getTotalEur()));
        assertTrue(tx.getExternalId().startsWith("TRADING212:"));
    }

    @Test
    void filtriraDepositINeStavljaUKarantenu() throws IOException {
        String row = "Deposit,2026-02-19 06:30:40+00:00,,,,\"Bank Transfer\",019c7497-ee9f-774d-b850-747b2932c548,"
                + ",,,,,,10.00,\"EUR\",,,,,,";

        ParseResult result = parse(row);

        assertTrue(result.getTransactions().isEmpty());
        assertTrue(result.getSkippedRows().isEmpty(), "Deposit je namjerno filtriranje, ne karantena");
    }

    @Test
    void redakBezDatumaIdeUKarantenu() throws IOException {
        String row = "Market buy,,KYG9830T1067,3CP,\"Xiaomi\",,EOF46809354484,"
                + "0.2673663100,4.0020000000,EUR,1.00000000,,,1.07,\"EUR\",,,,,,";

        ParseResult result = parse(row);

        assertTrue(result.getTransactions().isEmpty());
        assertEquals(1, result.getSkippedRows().size());
        assertTrue(result.getSkippedRows().get(0).reason().toLowerCase().contains("datum"));
    }

    @Test
    void redakBezKolicineIdeUKarantenu() throws IOException {
        String row = "Market buy,2026-02-19 07:27:58+00:00,KYG9830T1067,3CP,\"Xiaomi\",,EOF46809354484,"
                + ",4.0020000000,EUR,1.00000000,,,1.07,\"EUR\",,,,,,";

        ParseResult result = parse(row);

        assertTrue(result.getTransactions().isEmpty());
        assertEquals(1, result.getSkippedRows().size());
        assertTrue(result.getSkippedRows().get(0).reason().toLowerCase().contains("kolic"));
    }

    @Test
    void deduplikacijskiIdSeRazlikujePoBrokeru() throws IOException {
        String row = "Market buy,2026-02-19 07:27:58+00:00,KYG9830T1067,3CP,\"Xiaomi\",,EOF46809354484,"
                + "0.2673663100,4.0020000000,EUR,1.00000000,,,1.07,\"EUR\",,,,,,";

        ParseResult result = parse(row);
        Transaction tx = result.getTransactions().get(0);

        assertEquals("TRADING212:EOF46809354484", tx.getExternalId());
    }

    @Test
    void visestrukiRedciMijesajuValjaneINevaljane() throws IOException {
        String rows = String.join("\n", List.of(
                "Market buy,2026-02-19 07:27:58+00:00,KYG9830T1067,3CP,\"Xiaomi\",,EOF1,0.5,4.00,EUR,1.0,,,2.00,\"EUR\",,,,,,",
                "Deposit,2026-02-19 06:30:40+00:00,,,,\"Bank Transfer\",019c1,,,,,,,10.00,\"EUR\",,,,,,",
                "Market sell,,KYG9830T1067,3CP,\"Xiaomi\",,EOF2,0.5,4.10,EUR,1.0,,,2.05,\"EUR\",,,,,,"
        ));

        ParseResult result = parse(rows);

        assertEquals(1, result.getTransactions().size(), "samo Market buy je valjan");
        assertEquals(1, result.getSkippedRows().size(), "Market sell bez datuma ide u karantenu");
        // Deposit se ne broji ni kao transakcija ni kao karantena
    }

    @Test
    void vrijemeSeKonvertiraIzUtcUHrvatskoVrijeme_zimi() throws IOException {
        // 31.12.2025 23:30 UTC = 1.1.2026 00:30 u Hrvatskoj -> druga porezna godina
        String row = "Market sell,2025-12-31 23:30:00+00:00,KYG9830T1067,3CP,\"Xiaomi\",,EOF1,"
                + "1,4.00,EUR,1.0,,,4.00,\"EUR\",,,,,,";

        Transaction tx = parse(row).getTransactions().get(0);

        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 30), tx.getDateTime());
    }

    @Test
    void vrijemeSeKonvertiraIzUtcUHrvatskoVrijeme_ljeti() throws IOException {
        String row = "Market buy,2026-07-01 10:00:00+00:00,KYG9830T1067,3CP,\"Xiaomi\",,EOF2,"
                + "1,4.00,EUR,1.0,,,4.00,\"EUR\",,,,,,";

        assertEquals(LocalDateTime.of(2026, 7, 1, 12, 0), parse(row).getTransactions().get(0).getDateTime());
    }

    @Test
    void totalUNeEurValutiSeKonvertiraHnbTecajem() throws IOException {
        LocalDate date = LocalDate.of(2026, 3, 10);
        Mockito.when(rateRepository.findByCurrencyAndRateDate("USD", date))
                .thenReturn(Optional.of(new ExchangeRate("USD", date, new BigDecimal("1.25"))));
        String row = "Market buy,2026-03-10 10:00:00+00:00,US0378331005,AAPL,\"Apple\",,EOF3,"
                + "1,100.00,USD,1.0,,,100.00,\"USD\",,,,,,";

        Transaction tx = parse(row).getTransactions().get(0);

        assertEquals(0, new BigDecimal("80.0000").compareTo(tx.getTotalEur()));
        assertEquals(0, new BigDecimal("1.25").compareTo(tx.getExchangeRateToEur()));
    }

    @Test
    void neuspjelaHnbKonverzijaPostavljaSentinelZero() throws IOException {
        String row = "Market buy,2026-03-10 10:00:00+00:00,US0378331005,AAPL,\"Apple\",,EOF4,"
                + "1,100.00,USD,1.0,,,100.00,\"USD\",,,,,,";

        Transaction tx = parse(row).getTransactions().get(0);

        assertEquals(0, BigDecimal.ZERO.compareTo(tx.getExchangeRateToEur()));
    }

    @Test
    void korporativnaAkcijaSplitIdeUKarantenuANeTiho() throws IOException {
        String row = "Stock split open,2026-03-10 10:00:00+00:00,US0378331005,AAPL,\"Apple\",,EOF5,"
                + "4,,USD,,,,,\"EUR\",,,,,,";

        ParseResult result = parse(row);

        assertTrue(result.getTransactions().isEmpty());
        assertEquals(1, result.getSkippedRows().size());
        assertTrue(result.getSkippedRows().get(0).reason().contains("split"));
    }
}
