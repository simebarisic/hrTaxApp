package hr.porezno.taxapp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Keširan HNB "srednji tečaj" za jednu (valuta, datum) kombinaciju.
 * eurRate = koliko jedinica "currency" vrijedi 1 EUR (isto kao HNB-ovo polje
 * "srednji_tecaj" na https://api.hnb.hr/tecajn-eur/v3).
 *
 * Keširanje sprjecava ponovne pozive vanjskog API-ja za istu kombinaciju, i
 * osigurava da aplikacija i dalje radi (za vec obradene datume) i ako HNB API
 * privremeno ne odgovara.
 */
@Entity
@Table(name = "exchange_rates", uniqueConstraints = @UniqueConstraint(columnNames = {"currency", "rate_date"}))
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "eur_rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal eurRate;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    public ExchangeRate() {
    }

    public ExchangeRate(String currency, LocalDate rateDate, BigDecimal eurRate) {
        this.currency = currency;
        this.rateDate = rateDate;
        this.eurRate = eurRate;
        this.fetchedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getCurrency() { return currency; }
    public LocalDate getRateDate() { return rateDate; }
    public BigDecimal getEurRate() { return eurRate; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }

    public void setId(Long v) { this.id = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setRateDate(LocalDate v) { this.rateDate = v; }
    public void setEurRate(BigDecimal v) { this.eurRate = v; }
    public void setFetchedAt(LocalDateTime v) { this.fetchedAt = v; }
}
