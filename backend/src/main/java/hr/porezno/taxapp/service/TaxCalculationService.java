package hr.porezno.taxapp.service;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.MatchedLot;
import hr.porezno.taxapp.model.TaxYearSummary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Primjenjuje hrvatska pravila oporezivanja kapitalne dobiti (clanci 64.-70.
 * Zakona o porezu na dohodak) na FIFO-uparene lotove:
 *
 *  1. Lotovi drzani > 2 godine (730 dana / 2 kalendarske godine od stjecanja)
 *     su potpuno izuzeti od poreza, bez obzira na iznos dobiti.
 *  2. Za preostale (oporezive) lotove, dobici i gubici se netiraju UNUTAR
 *     iste kalendarske godine prodaje.
 *  3. Porez se placa samo ako je neto rezultat pozitivan, po stopi 12%.
 *  4. Gubici se NE prenose u sljedecu poreznu godinu.
 *
 * VAZNO: stvarna porezna obveza (npr. za JOPPD) je UVIJEK zbrojena po godini
 * PREKO svih brokera - Porezna uprava ne razlikuje kod kojeg je brokera
 * dobitak nastao. calculate() vraca upravo taj sluzbeni, kombinirani broj.
 * calculateByBroker() dodatno raslanjuje isti izracun po brokeru - iskljucivo
 * kao transparentan prikaz odakle dolazi koji dio dobiti/poreza, ne kao
 * zamjena za sluzbeni kombinirani iznos.
 */
@Service
public class TaxCalculationService {

    public static final BigDecimal TAX_RATE = new BigDecimal("0.12");

    /** Sluzbeni, po godini KOMBINIRANI obracun (preko svih brokera) - ovo je stvarna porezna obveza. */
    public List<TaxYearSummary> calculate(List<MatchedLot> matchedLots) {
        return summarizeByYear(matchedLots);
    }

    /** Isti obracun, dodatno raslanjen po brokeru - samo za transparentnost/uvid, ne za JOPPD. */
    public Map<BrokerType, List<TaxYearSummary>> calculateByBroker(List<MatchedLot> matchedLots) {
        Map<BrokerType, List<MatchedLot>> byBroker = matchedLots.stream()
                .collect(Collectors.groupingBy(MatchedLot::getBroker, LinkedHashMap::new, Collectors.toList()));

        Map<BrokerType, List<TaxYearSummary>> result = new LinkedHashMap<>();
        for (BrokerType broker : BrokerType.values()) {
            List<MatchedLot> lots = byBroker.get(broker);
            if (lots != null && !lots.isEmpty()) {
                result.put(broker, summarizeByYear(lots));
            }
        }
        return result;
    }

    private List<TaxYearSummary> summarizeByYear(List<MatchedLot> matchedLots) {
        Map<Integer, List<MatchedLot>> byYear = matchedLots.stream()
                .collect(Collectors.groupingBy(lot -> lot.getSellDate().getYear()));

        List<TaxYearSummary> summaries = new ArrayList<>();

        for (Map.Entry<Integer, List<MatchedLot>> entry : new TreeMap<>(byYear).entrySet()) {
            int year = entry.getKey();
            List<MatchedLot> yearLots = entry.getValue();

            List<MatchedLot> taxable = yearLots.stream()
                    .filter(lot -> !lot.isExemptOverTwoYears())
                    .sorted(Comparator.comparing(MatchedLot::getSellDate))
                    .toList();
            List<MatchedLot> exempt = yearLots.stream()
                    .filter(MatchedLot::isExemptOverTwoYears)
                    .sorted(Comparator.comparing(MatchedLot::getSellDate))
                    .toList();

            BigDecimal totalGains = sumWhere(taxable, gl -> gl > 0);
            BigDecimal totalLosses = sumWhere(taxable, gl -> gl < 0).abs();
            BigDecimal netBase = totalGains.subtract(totalLosses).max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal taxDue = netBase.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);

            BigDecimal totalExemptGain = exempt.stream()
                    .map(MatchedLot::getGainLossEur)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            summaries.add(TaxYearSummary.builder()
                    .year(year)
                    .taxableLots(taxable)
                    .exemptLots(exempt)
                    .totalTaxableGainsEur(totalGains.setScale(2, RoundingMode.HALF_UP))
                    .totalTaxableLossesEur(totalLosses.setScale(2, RoundingMode.HALF_UP))
                    .netTaxableBaseEur(netBase)
                    .taxRate(TAX_RATE)
                    .taxDueEur(taxDue)
                    .totalExemptGainEur(totalExemptGain.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }

        return summaries;
    }

    private BigDecimal sumWhere(List<MatchedLot> lots, java.util.function.Predicate<Integer> signPredicate) {
        return lots.stream()
                .map(MatchedLot::getGainLossEur)
                .filter(gl -> signPredicate.test(gl.signum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
