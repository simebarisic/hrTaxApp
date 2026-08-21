package hr.porezno.taxapp.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Interna, mutabilna reprezentacija jos neprodane (ili djelomicno prodane)
 * kupnje - koristi se iskljucivo unutar FifoMatchingService kao stavka u
 * redu za FIFO uparivanje. Nije izlozena van servisa.
 */
class OpenLot {
    final LocalDateTime buyDate;
    BigDecimal remainingQuantity;
    /** Ukupan trosak (cijena + naknade) po jedinici, u EUR. */
    final BigDecimal costPerUnitEur;

    OpenLot(LocalDateTime buyDate, BigDecimal remainingQuantity, BigDecimal costPerUnitEur) {
        this.buyDate = buyDate;
        this.remainingQuantity = remainingQuantity;
        this.costPerUnitEur = costPerUnitEur;
    }
}
