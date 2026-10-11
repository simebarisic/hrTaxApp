package hr.porezno.taxapp.service;

import hr.porezno.taxapp.dto.JoppdYearSummaryDto;
import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.MatchedLot;
import hr.porezno.taxapp.model.TaxYearSummary;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Jedino mjesto na kojem se cijela povijest iz baze pretvara u porezni
 * izvjestaj (FIFO -> porez -> JOPPD + upozorenja). Koriste ga i
 * ReportController i ExportController, da se pipeline ne dupicira.
 */
@Service
public class TaxReportService {

    public record Report(
            List<Transaction> transactions,
            List<MatchedLot> matchedLots,
            List<TaxYearSummary> taxYearSummaries,
            Map<BrokerType, List<TaxYearSummary>> taxYearSummariesByBroker,
            List<JoppdYearSummaryDto> joppdSummaries,
            List<String> warnings
    ) {}

    private final TransactionRepository transactionRepository;
    private final FifoMatchingService fifoMatchingService;
    private final TaxCalculationService taxCalculationService;
    private final JoppdExportService joppdExportService;

    public TaxReportService(TransactionRepository transactionRepository,
                            FifoMatchingService fifoMatchingService,
                            TaxCalculationService taxCalculationService,
                            JoppdExportService joppdExportService) {
        this.transactionRepository = transactionRepository;
        this.fifoMatchingService = fifoMatchingService;
        this.taxCalculationService = taxCalculationService;
        this.joppdExportService = joppdExportService;
    }

    public Report buildFromFullHistory() {
        List<Transaction> all = transactionRepository.findAllByOrderByDateTimeAsc();
        FifoMatchingService.MatchResult match = fifoMatchingService.matchWithDiagnostics(all);

        List<TaxYearSummary> summaries = taxCalculationService.calculate(match.lots());
        var byBroker = taxCalculationService.calculateByBroker(match.lots());
        List<JoppdYearSummaryDto> joppd = joppdExportService.buildSummaries(summaries);

        return new Report(all, match.lots(), summaries, byBroker, joppd, buildWarnings(all, match.shortfalls()));
    }

    List<String> buildWarnings(List<Transaction> transactions, List<FifoMatchingService.Shortfall> shortfalls) {
        List<String> warnings = new ArrayList<>();

        for (FifoMatchingService.Shortfall s : shortfalls) {
            String where = "CRYPTO".equals(s.pool()) ? "kripto portfelju" : "kod brokera " + s.pool();
            warnings.add("Prodaja " + s.instrument() + " (" + s.sellDate().toLocalDate() + "): za " + s.quantity()
                    + " jedinica nema evidentirane kupnje " + where + ". Razlog je vjerojatno nepotpuna povijest "
                    + "(uploadaj export od pocetka) ili transfer imovine izmedju brokera/wallet-a. Ta kolicina NIJE "
                    + "ukljucena u obracun pa je porezna osnovica nepotpuna.");
        }

        // exchangeRateToEur == ZERO je sentinel starijih uploada kad HNB konverzija nije uspjela
        // (novi uploadi takve retke vise ne spremaju, nego ih stavljaju u karantenu).
        List<Transaction> failed = transactions.stream()
                .filter(tx -> tx.getExchangeRateToEur() != null && tx.getExchangeRateToEur().signum() == 0)
                .toList();
        if (!failed.isEmpty()) {
            Set<String> currencies = failed.stream().map(Transaction::getCurrency)
                    .collect(Collectors.toCollection(TreeSet::new));
            warnings.add(failed.size() + " vec spremljenih transakcija u valutama " + String.join(", ", currencies)
                    + " ima SIROV (nekonvertiran) iznos jer HNB konverzija nije uspjela pri ranijem uploadu. "
                    + "Obrisi podatke i uploadaj ponovno kad je HNB dostupan.");
        }

        return warnings;
    }
}
