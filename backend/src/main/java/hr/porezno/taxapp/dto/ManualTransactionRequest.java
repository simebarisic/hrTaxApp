package hr.porezno.taxapp.dto;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Rucni unos jedne kupnje/prodaje (npr. BTC koji nije u Strike exportu, ili
 * kad CSV format ne odgovara). Iznos je UKUPNO u EUR, ukljucujuci naknade
 * (kupnja: ukupno placeno, prodaja: ukupno primljeno).
 */
public record ManualTransactionRequest(
        @NotNull BrokerType broker,
        @NotNull TransactionType type,
        @NotNull LocalDateTime dateTime,
        @NotBlank String ticker,
        String isin,
        @NotNull @Positive BigDecimal quantity,
        @NotNull @Positive BigDecimal totalEur
) {}
