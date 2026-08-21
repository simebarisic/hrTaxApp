package hr.porezno.taxapp.service;

import hr.porezno.taxapp.model.MatchedLot;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Uparuje BUY i SELL transakcije po ISIN-u metodom FIFO (First In, First Out) -
 * clanak 67. Zakona o porezu na dohodak: kod visestrukih kupnji iste imovine,
 * prilikom prodaje se prvo "trose" najstarije kupljene jedinice.
 *
 * Napomena o naknadama: pretpostavlja se da je "Total" iz izvora vec neto iznos
 * (ukljucuje naknade), sto odgovara uobicajenom Trading212 CSV izvozu. Ako se
 * koristi drugi izvor gdje to ne vrijedi, potrebno je prilagoditi izracun
 * costPerUnit/proceedsPerUnit.
 *
 * Grupiranje: standardno po BROKERU + ISIN-u (ili tickeru ako ISIN fali) -
 * FIFO NIKAD ne upari kupnju kod jednog brokera s prodajom kod drugog, cak i
 * za isti vrijednosni papir (svaki broker se promatra kao odvojeni "pool"
 * dionica za potrebe ovog obracuna - transferi papira izmedu brokera trenutno
 * nisu posebno prepoznati, vidi upozorenja u ReportController). Ako
 * transakcija ima postavljen "lotGroupKey" (vidi Transaction), taj kljuc ima
 * DODATNU prednost unutar istog brokera - koristi se za pred-uparene lotove
 * iz izvjestaja koji vec daju tocan par datum stjecanja/prodaje (npr. Revolut
 * Tax Report), da se sintetski BUY+SELL par jednog retka nikad ne pomijesa s
 * drugim redovima istog ISIN-a.
 */
@Service
public class FifoMatchingService {

    public List<MatchedLot> match(List<Transaction> transactions) {
        List<MatchedLot> result = new ArrayList<>();

        Map<String, List<Transaction>> byGroup = new HashMap<>();
        for (Transaction tx : transactions) {
            if (tx.getType() != TransactionType.BUY && tx.getType() != TransactionType.SELL) {
                continue;
            }
            String instrumentKey = tx.getLotGroupKey() != null
                    ? tx.getLotGroupKey()
                    : (tx.getIsin() != null ? tx.getIsin() : tx.getTicker());
            String key = tx.getBroker() + "|" + instrumentKey;
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }

        for (Map.Entry<String, List<Transaction>> entry : byGroup.entrySet()) {
            result.addAll(matchForInstrument(entry.getValue()));
        }

        result.sort(Comparator.comparing(MatchedLot::getSellDate));
        return result;
    }

    private List<MatchedLot> matchForInstrument(List<Transaction> txs) {
        List<MatchedLot> matches = new ArrayList<>();
        List<Transaction> sorted = txs.stream()
                .sorted(Comparator.comparing(Transaction::getDateTime))
                .toList();

        Deque<OpenLot> openLots = new ArrayDeque<>();

        for (Transaction tx : sorted) {
            if (tx.getType() == TransactionType.BUY) {
                BigDecimal qty = nz(tx.getQuantity());
                if (qty.signum() <= 0) continue;
                BigDecimal totalCost = nz(tx.getTotalEur()).abs();
                BigDecimal costPerUnit = qty.signum() == 0
                        ? BigDecimal.ZERO
                        : totalCost.divide(qty, 8, RoundingMode.HALF_UP);
                openLots.addLast(new OpenLot(tx.getDateTime(), qty, costPerUnit));

            } else if (tx.getType() == TransactionType.SELL) {
                BigDecimal sellQtyRemaining = nz(tx.getQuantity());
                if (sellQtyRemaining.signum() <= 0) continue;
                BigDecimal totalProceeds = nz(tx.getTotalEur()).abs();
                BigDecimal proceedsPerUnit = sellQtyRemaining.signum() == 0
                        ? BigDecimal.ZERO
                        : totalProceeds.divide(sellQtyRemaining, 8, RoundingMode.HALF_UP);

                while (sellQtyRemaining.signum() > 0 && !openLots.isEmpty()) {
                    OpenLot lot = openLots.peekFirst();
                    BigDecimal matchedQty = sellQtyRemaining.min(lot.remainingQuantity);

                    BigDecimal costBasis = matchedQty.multiply(lot.costPerUnitEur).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal proceeds = matchedQty.multiply(proceedsPerUnit).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal gainLoss = proceeds.subtract(costBasis);

                    matches.add(MatchedLot.builder()
                            .isin(tx.getIsin())
                            .ticker(tx.getTicker())
                            .broker(tx.getBroker())
                            .buyDate(lot.buyDate)
                            .sellDate(tx.getDateTime())
                            .quantity(matchedQty)
                            .costBasisEur(costBasis)
                            .proceedsEur(proceeds)
                            .gainLossEur(gainLoss)
                            .build());

                    lot.remainingQuantity = lot.remainingQuantity.subtract(matchedQty);
                    sellQtyRemaining = sellQtyRemaining.subtract(matchedQty);

                    if (lot.remainingQuantity.signum() <= 0) {
                        openLots.pollFirst();
                    }
                }

                // Ako sellQtyRemaining > 0 ovdje, izvjestaj ima vise prodanih nego
                // evidentiranih kupljenih jedinica (npr. transfer iz drugog brokera,
                // ili nepotpuna povijest u uploadanoj datoteci). To se ne moze tiho
                // ignorirati - flagiramo ga na razini servisa koji poziva ovaj matcher.
            }
        }

        return matches;
    }

    private BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
