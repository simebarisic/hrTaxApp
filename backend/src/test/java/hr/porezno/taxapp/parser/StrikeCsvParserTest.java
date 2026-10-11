package hr.porezno.taxapp.parser;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.ExchangeRate;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StrikeCsvParserTest {

    private final ExchangeRateRepository rateRepository = Mockito.mock(ExchangeRateRepository.class);
    private final HnbExchangeRateService hnb =
            new HnbExchangeRateService(rateRepository, new com.fasterxml.jackson.databind.ObjectMapper());
    private final StrikeCsvParser parser = new StrikeCsvParser(hnb, true);

    private static final String HEADER = "Transaction ID,Time (UTC),Status,Transaction Type,Amount EUR,Fee EUR,Amount BTC,Fee BTC,BTC Price";

    private ParseResult parse(StrikeCsvParser p, String header, String... rows) throws IOException {
        String csv = header + "\n" + String.join("\n", rows) + "\n";
        return p.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private ParseResult parse(String... rows) throws IOException {
        return parse(parser, HEADER, rows);
    }

    @Test
    void parsiraKupnjuBitcoina() throws IOException {
        ParseResult r = parse("tx-1,2025-03-10 10:00:00,Completed,Purchase,100.00,1.00,0.00125000,0,80000");

        assertEquals(1, r.getTransactions().size());
        Transaction tx = r.getTransactions().get(0);
        assertEquals(TransactionType.BUY, tx.getType());
        assertEquals(BrokerType.STRIKE, tx.getBroker());
        assertEquals("BTC", tx.getTicker());
        assertEquals("STRIKE:tx-1", tx.getExternalId());
        assertEquals(0, new BigDecimal("0.00125").compareTo(tx.getQuantity()));
        assertEquals(0, new BigDecimal("100").compareTo(tx.getTotalEur()));
        // 10:00 UTC u ozujku (zima, +1) = 11:00
        assertEquals(LocalDateTime.of(2025, 3, 10, 11, 0), tx.getDateTime());
    }

    @Test
    void parsiraProdajuBitcoina() throws IOException {
        ParseResult r = parse("tx-2,2025-09-01 10:00:00,Completed,Sale,6000.00,10.00,-0.1,0,60000");

        Transaction tx = r.getTransactions().get(0);
        assertEquals(TransactionType.SELL, tx.getType());
        assertEquals(0, new BigDecimal("0.1").compareTo(tx.getQuantity())); // apsolutna vrijednost
        assertEquals(0, new BigDecimal("6000").compareTo(tx.getTotalEur()));
    }

    @Test
    void prijenosNaColdWalletNijePorezniDogadjaj_preskacaSeBezKarantene() throws IOException {
        ParseResult r = parse(
                "tx-3,2025-03-11 10:00:00,Completed,Withdrawal,0,0,0.1,0.00001,",
                "tx-4,2025-03-12 10:00:00,Completed,Deposit,500,0,0,0,");

        assertTrue(r.getTransactions().isEmpty());
        assertTrue(r.getSkippedRows().isEmpty());
    }

    @Test
    void nepoznataVrstaSBtcIznosomIdeUKarantenu() throws IOException {
        ParseResult r = parse("tx-5,2025-03-11 10:00:00,Completed,Reward,0,0,0.0001,0,");

        assertTrue(r.getTransactions().isEmpty());
        assertEquals(1, r.getSkippedRows().size());
    }

    @Test
    void nezavrsenaTransakcijaIdeUKarantenu() throws IOException {
        ParseResult r = parse("tx-6,2025-03-10 10:00:00,Pending,Purchase,100,1,0.001,0,");

        assertTrue(r.getTransactions().isEmpty());
        assertEquals(1, r.getSkippedRows().size());
    }

    @Test
    void naknadaOdvojena_kupnjaDodajeProdajaOduzima() throws IOException {
        StrikeCsvParser separateFee = new StrikeCsvParser(hnb, false);

        ParseResult r = parse(separateFee, HEADER,
                "a,2025-03-10 10:00:00,Completed,Purchase,100.00,1.00,0.001,0,",
                "b,2025-09-10 10:00:00,Completed,Sale,100.00,1.00,-0.001,0,");

        assertEquals(0, new BigDecimal("101").compareTo(r.getTransactions().get(0).getTotalEur()));
        assertEquals(0, new BigDecimal("99").compareTo(r.getTransactions().get(1).getTotalEur()));
    }

    @Test
    void usdSeKonvertiraHnbTecajem_aNeuspjehPostavljaSentinel() throws IOException {
        String usdHeader = "Transaction ID,Time (UTC),Status,Transaction Type,Amount USD,Fee USD,Amount BTC";
        LocalDate d = LocalDate.of(2025, 3, 10);
        Mockito.when(rateRepository.findByCurrencyAndRateDate("USD", d))
                .thenReturn(Optional.of(new ExchangeRate("USD", d, new BigDecimal("1.10"))));

        ParseResult ok = parse(parser, usdHeader, "u1,2025-03-10 10:00:00,Completed,Purchase,110.00,0,0.001");
        ParseResult noRate = parse(parser, usdHeader, "u2,2025-04-10 10:00:00,Completed,Purchase,110.00,0,0.001");

        assertEquals(0, new BigDecimal("100").compareTo(ok.getTransactions().get(0).getTotalEur()));
        assertEquals(0, BigDecimal.ZERO.compareTo(noRate.getTransactions().get(0).getExchangeRateToEur()));
    }

    @Test
    void nedostajuciObaveznStupciDajuJasnuGresku() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse(parser, "Foo,Bar", "1,2"));
        assertTrue(e.getMessage().contains("Pronadjeni stupci"));
    }

    @Test
    void ponovniUploadIstogIdaDajeIstiExternalId() throws IOException {
        String row = "tx-9,2025-03-10 10:00:00,Completed,Purchase,100.00,1.00,0.001,0,";
        assertEquals(parse(row).getTransactions().get(0).getExternalId(), parse(row).getTransactions().get(0).getExternalId());
    }
}
