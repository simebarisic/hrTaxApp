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

import static org.junit.jupiter.api.Assertions.*;

class IbkrCsvParserTest {

    private final IbkrCsvParser parser = new IbkrCsvParser(
            new HnbExchangeRateService(Mockito.mock(ExchangeRateRepository.class), new com.fasterxml.jackson.databind.ObjectMapper())
    );

    private ParseResult parse(String csv) throws IOException {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsiraStkKupnjuSPozitivnomKolicinom() throws IOException {
        String csv = """
                Symbol,ISIN,TradeDate,Buy/Sell,Quantity,TradePrice,CurrencyPrimary,IBCommission,AssetCategory,TradeID
                AAPL,US0378331005,20260115,BUY,10,185.50,EUR,1.00,STK,T1
                """;

        ParseResult result = parse(csv);

        assertEquals(1, result.getTransactions().size());
        Transaction tx = result.getTransactions().get(0);
        assertEquals(TransactionType.BUY, tx.getType());
        assertEquals(BrokerType.IBKR, tx.getBroker());
        assertEquals(0, new BigDecimal("10").compareTo(tx.getQuantity()));
        // total = 10*185.50 + 1.00 commission = 1856.00
        assertEquals(0, new BigDecimal("1856.00").compareTo(tx.getTotalEur()));
        assertEquals("IBKR:T1", tx.getExternalId());
    }

    @Test
    void odredujeSmjerIzPredznakaKolicineKadFaliBuySellStupac() throws IOException {
        String csv = """
                Symbol,ISIN,TradeDate,Quantity,TradePrice,CurrencyPrimary,AssetCategory,TradeID
                AAPL,US0378331005,20260115,-5,190.00,EUR,STK,T2
                """;

        ParseResult result = parse(csv);

        assertEquals(1, result.getTransactions().size());
        assertEquals(TransactionType.SELL, result.getTransactions().get(0).getType());
    }

    @Test
    void preskaceOpcijeBezKarantene() throws IOException {
        String csv = """
                Symbol,ISIN,TradeDate,Buy/Sell,Quantity,TradePrice,CurrencyPrimary,AssetCategory,TradeID
                AAPL 260320C00200000,,20260115,BUY,1,5.20,EUR,OPT,T3
                """;

        ParseResult result = parse(csv);

        assertTrue(result.getTransactions().isEmpty());
        assertTrue(result.getSkippedRows().isEmpty(), "opcije su namjerno filtriranje, ne karantena");
    }

    @Test
    void bacaJasnuGreskuKadNedostajeObaveznoPolje() {
        String csv = "Symbol,ISIN,AssetCategory\nAAPL,US0378331005,STK\n";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse(csv));
        assertTrue(ex.getMessage().contains("TradeDate"));
    }

    @Test
    void nevaljanaKolicinaIdeUKarantenu() throws IOException {
        String csv = """
                Symbol,ISIN,TradeDate,Buy/Sell,Quantity,TradePrice,CurrencyPrimary,AssetCategory,TradeID
                AAPL,US0378331005,20260115,BUY,,185.50,EUR,STK,T4
                """;

        ParseResult result = parse(csv);

        assertTrue(result.getTransactions().isEmpty());
        assertEquals(1, result.getSkippedRows().size());
    }
}
