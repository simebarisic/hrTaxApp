package hr.porezno.taxapp.parser;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import hr.porezno.taxapp.repository.ExchangeRateRepository;
import hr.porezno.taxapp.service.HnbExchangeRateService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Koristi stvaran (djelomican) uzorak Revolut "Tax Report" CSV-a koji je
 * korisnik podijelio - vidi RevolutCsvParser javadoc za detalje formata.
 */
class RevolutCsvParserTest {

    // ExchangeRateRepository mock koji nikad ne vraca kes-hit, tako da svi
    // testovi rade s currency=EUR gdje se konverzija uopce ne pokusava
    // (izbjegavamo stvarne HTTP pozive prema HNB-u u testovima).
    private final RevolutCsvParser parser = new RevolutCsvParser(
            new HnbExchangeRateService(Mockito.mock(ExchangeRateRepository.class), new com.fasterxml.jackson.databind.ObjectMapper())
    );

    private ParseResult parse(String csv) throws IOException {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsiraIncomeFromSellsUSintetickiBuyISellPar() throws IOException {
        String csv = """
                Income from Sells
                Date acquired,Date sold,Symbol,Security name,ISIN,Country,Quantity,Cost basis,Gross proceeds,Gross PnL,Currency
                2024-12-04,2025-12-15,TSLA,Tesla,US88160R1014,US,0.00886585,3.13,4.16,1.03,EUR
                """;

        ParseResult result = parse(csv);

        assertEquals(2, result.getTransactions().size(), "jedan redak = jedan BUY + jedan SELL");

        Transaction buy = result.getTransactions().stream().filter(t -> t.getType() == TransactionType.BUY).findFirst().orElseThrow();
        Transaction sell = result.getTransactions().stream().filter(t -> t.getType() == TransactionType.SELL).findFirst().orElseThrow();

        assertEquals(BrokerType.REVOLUT, buy.getBroker());
        assertNotNull(buy.getLotGroupKey());
        assertEquals(buy.getLotGroupKey(), sell.getLotGroupKey(), "BUY i SELL istog retka moraju dijeliti lotGroupKey");
        assertEquals(0, new BigDecimal("3.13").compareTo(buy.getTotalEur()));
        assertEquals(0, new BigDecimal("4.16").compareTo(sell.getTotalEur()));
    }

    @Test
    void dvaLotaIstogDanaImajuRazliciteLotGroupKeyeve() throws IOException {
        // Regresijski test za bug koji smo otkrili: dva razlicita lota istog
        // ISIN-a prodana ISTOG DANA se ne smiju krizno upariti u FIFO-u.
        String csv = """
                Income from Sells
                Date acquired,Date sold,Symbol,Security name,ISIN,Country,Quantity,Cost basis,Gross proceeds,Gross PnL,Currency
                2024-12-20,2025-12-15,TSLA,Tesla,US88160R1014,US,0.59981111,190.53,115.81,-74.72,EUR
                2025-12-15,2025-12-15,TSLA,Tesla,US88160R1014,US,1.01021966,430.00,474.37,44.37,EUR
                """;

        ParseResult result = parse(csv);

        assertEquals(4, result.getTransactions().size());
        List<String> lotKeys = result.getTransactions().stream().map(Transaction::getLotGroupKey).distinct().toList();
        assertEquals(2, lotKeys.size(), "svaki redak treba svoj jedinstveni lotGroupKey");
    }

    @Test
    void parsiraOtherIncomeDividenduSSimbolomValuteUIznosu() throws IOException {
        String csv = """
                Other income & fees
                Date,Symbol,Security name,ISIN,Country,Gross amount,Withholding tax,Net Amount,Currency
                2026-03-27,GRMN,Garmin dividend,CH0114405324,CH,0.01,$0,€0.01,EUR
                """;

        ParseResult result = parse(csv);

        assertEquals(1, result.getTransactions().size());
        Transaction div = result.getTransactions().get(0);
        assertEquals(TransactionType.DIVIDEND, div.getType());
        assertEquals(0, new BigDecimal("0.01").compareTo(div.getTotalEur()), "simbol '€' se mora skinuti prije parsiranja");
    }

    @Test
    void nepotpunRedakIdeUKarantenu() throws IOException {
        String csv = """
                Income from Sells
                Date acquired,Date sold,Symbol,Security name,ISIN,Country,Quantity,Cost basis,Gross proceeds,Gross PnL,Currency
                ,2025-12-15,TSLA,Tesla,US88160R1014,US,0.00886585,3.13,4.16,1.03,EUR
                """;

        ParseResult result = parse(csv);

        assertTrue(result.getTransactions().isEmpty());
        assertEquals(1, result.getSkippedRows().size());
    }

    @Test
    void nepoznataSekcijaIdeUKarantenuAliNeBacaGresku() throws IOException {
        String csv = """
                Income from Interest
                Date,Amount,Currency
                2026-01-01,0.50,EUR
                """;

        ParseResult result = parse(csv);

        assertTrue(result.getTransactions().isEmpty());
        assertEquals(1, result.getSkippedRows().size());
        assertTrue(result.getSkippedRows().get(0).reason().toLowerCase().contains("nepoznata sekcija"));
    }
}
