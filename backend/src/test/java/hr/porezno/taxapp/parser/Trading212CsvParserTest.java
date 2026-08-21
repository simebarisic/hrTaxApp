package hr.porezno.taxapp.parser;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Trading212CsvParserTest {

    private final Trading212CsvParser parser = new Trading212CsvParser();

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
}
