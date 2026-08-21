package hr.porezno.taxapp.service;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.MatchedLot;
import hr.porezno.taxapp.model.TaxYearSummary;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FifoAndTaxCalculationTest {

    private final FifoMatchingService fifoService = new FifoMatchingService();
    private final TaxCalculationService taxService = new TaxCalculationService();

    private Transaction buy(String isin, LocalDateTime date, String qty, String totalEur) {
        return buy(isin, date, qty, totalEur, BrokerType.TRADING212);
    }

    private Transaction buy(String isin, LocalDateTime date, String qty, String totalEur, BrokerType broker) {
        return Transaction.builder()
                .isin(isin).ticker(isin).type(TransactionType.BUY)
                .broker(broker)
                .dateTime(date)
                .quantity(new BigDecimal(qty))
                .totalEur(new BigDecimal(totalEur))
                .currency("EUR").exchangeRateToEur(BigDecimal.ONE)
                .build();
    }

    private Transaction sell(String isin, LocalDateTime date, String qty, String totalEur) {
        return sell(isin, date, qty, totalEur, BrokerType.TRADING212);
    }

    private Transaction sell(String isin, LocalDateTime date, String qty, String totalEur, BrokerType broker) {
        return Transaction.builder()
                .isin(isin).ticker(isin).type(TransactionType.SELL)
                .broker(broker)
                .dateTime(date)
                .quantity(new BigDecimal(qty))
                .totalEur(new BigDecimal(totalEur))
                .currency("EUR").exchangeRateToEur(BigDecimal.ONE)
                .build();
    }

    @Test
    void oporezivaProdajaUnutarDvijeGodine_racunaDobit() {
        // Kupljeno 10 kom za 100 EUR (10/kom), prodano 10 kom za 150 EUR (15/kom), unutar godinu dana
        var buy1 = buy("US0001", LocalDateTime.of(2025, 1, 10, 10, 0), "10", "100");
        var sell1 = sell("US0001", LocalDateTime.of(2025, 6, 10, 10, 0), "10", "150");

        List<MatchedLot> matches = fifoService.match(List.of(buy1, sell1));

        assertEquals(1, matches.size());
        MatchedLot lot = matches.get(0);
        assertFalse(lot.isExemptOverTwoYears());
        assertEquals(0, new BigDecimal("50.0000").compareTo(lot.getGainLossEur()));

        List<TaxYearSummary> summaries = taxService.calculate(matches);
        assertEquals(1, summaries.size());
        TaxYearSummary y2025 = summaries.get(0);
        assertEquals(2025, y2025.getYear());
        assertEquals(0, new BigDecimal("50.00").compareTo(y2025.getNetTaxableBaseEur()));
        assertEquals(0, new BigDecimal("6.00").compareTo(y2025.getTaxDueEur())); // 50 * 12%
    }

    @Test
    void prodajaNakonDvijeGodine_izuzetaOdPoreza() {
        var buy1 = buy("US0002", LocalDateTime.of(2022, 1, 10, 10, 0), "5", "50");
        var sell1 = sell("US0002", LocalDateTime.of(2025, 1, 11, 10, 0), "5", "200"); // > 2 god nakon kupnje

        List<MatchedLot> matches = fifoService.match(List.of(buy1, sell1));
        assertEquals(1, matches.size());
        assertTrue(matches.get(0).isExemptOverTwoYears());

        List<TaxYearSummary> summaries = taxService.calculate(matches);
        TaxYearSummary y2025 = summaries.get(0);
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(y2025.getNetTaxableBaseEur()));
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(y2025.getTaxDueEur()));
        assertEquals(0, new BigDecimal("150.00").compareTo(y2025.getTotalExemptGainEur()));
    }

    @Test
    void gubiciNetirajuDobitkeUnutarIsteGodine() {
        // Dobit 100 na jednoj poziciji, gubitak 40 na drugoj, obje unutar 2 god - osnovica treba biti 60
        var buyA = buy("A", LocalDateTime.of(2025, 1, 1, 0, 0), "1", "100");
        var sellA = sell("A", LocalDateTime.of(2025, 3, 1, 0, 0), "1", "200"); // +100

        var buyB = buy("B", LocalDateTime.of(2025, 1, 1, 0, 0), "1", "100");
        var sellB = sell("B", LocalDateTime.of(2025, 4, 1, 0, 0), "1", "60"); // -40

        List<MatchedLot> matches = fifoService.match(List.of(buyA, sellA, buyB, sellB));
        List<TaxYearSummary> summaries = taxService.calculate(matches);

        assertEquals(1, summaries.size());
        TaxYearSummary y = summaries.get(0);
        assertEquals(0, new BigDecimal("60.00").compareTo(y.getNetTaxableBaseEur()));
        assertEquals(0, new BigDecimal("7.20").compareTo(y.getTaxDueEur())); // 60 * 12%
    }

    @Test
    void fifoUparujeNajstarijuKupnjuPrvo() {
        // Dvije kupnje po razlicitoj cijeni, jedna djelomicna prodaja - mora se uzeti prva (starija) kupnja
        var buy1 = buy("C", LocalDateTime.of(2025, 1, 1, 0, 0), "10", "100"); // 10/kom
        var buy2 = buy("C", LocalDateTime.of(2025, 2, 1, 0, 0), "10", "300"); // 30/kom
        var sell1 = sell("C", LocalDateTime.of(2025, 3, 1, 0, 0), "10", "250"); // 25/kom, prodano 10 kom

        List<MatchedLot> matches = fifoService.match(List.of(buy1, buy2, sell1));

        assertEquals(1, matches.size());
        MatchedLot lot = matches.get(0);
        // Trebala bi biti uparena s buy1 (10/kom), ne buy2 (30/kom)
        assertEquals(0, new BigDecimal("100.0000").compareTo(lot.getCostBasisEur()));
        assertEquals(0, new BigDecimal("150.0000").compareTo(lot.getGainLossEur()));
    }

    @Test
    void netoOsnovicaNijeNikadaNegativna() {
        var buyA = buy("D", LocalDateTime.of(2025, 1, 1, 0, 0), "1", "100");
        var sellA = sell("D", LocalDateTime.of(2025, 3, 1, 0, 0), "1", "50"); // -50, jedini rezultat u godini

        List<MatchedLot> matches = fifoService.match(List.of(buyA, sellA));
        List<TaxYearSummary> summaries = taxService.calculate(matches);

        TaxYearSummary y = summaries.get(0);
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(y.getNetTaxableBaseEur()));
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(y.getTaxDueEur()));
    }

    @Test
    void fifoNikadNeUparujePrekoRazlicitihBrokera() {
        // Kupljeno na Trading212, "kupljeno" isti ISIN i na Revolutu (odvojen portfelj) -
        // prodaja na Trading212 se MORA upariti sa svojom Trading212 kupnjom, ne s Revolut kupnjom
        // (cak i ako je Revolut kupnja starija i FIFO bi je inace odabrao prvu).
        var buyRevolut = buy("E", LocalDateTime.of(2024, 1, 1, 0, 0), "5", "50", BrokerType.REVOLUT); // starija, 10/kom
        var buyTrading212 = buy("E", LocalDateTime.of(2025, 1, 1, 0, 0), "5", "100", BrokerType.TRADING212); // novija, 20/kom
        var sellTrading212 = sell("E", LocalDateTime.of(2025, 6, 1, 0, 0), "5", "150", BrokerType.TRADING212);

        List<MatchedLot> matches = fifoService.match(List.of(buyRevolut, buyTrading212, sellTrading212));

        assertEquals(1, matches.size());
        MatchedLot lot = matches.get(0);
        assertEquals(BrokerType.TRADING212, lot.getBroker());
        // Cost basis MORA biti 100 (Trading212 kupnja po 20/kom), NE 50 (Revolut po 10/kom)
        assertEquals(0, new BigDecimal("100.0000").compareTo(lot.getCostBasisEur()));

        // Revolut kupnja ostaje neprodana (nema SELL za nju) - ne smije se pojaviti u rezultatu
        Map<BrokerType, List<TaxYearSummary>> byBroker = taxService.calculateByBroker(matches);
        assertEquals(1, byBroker.size());
        assertTrue(byBroker.containsKey(BrokerType.TRADING212));
        assertFalse(byBroker.containsKey(BrokerType.REVOLUT));
    }
}
