package hr.porezno.taxapp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Jedan red iz Trading212 CSV izvjestaja (Market buy / Market sell / Dividend...),
 * normaliziran u interni model neovisan o formatu izvora, i ujedno JPA entitet
 * za perzistenciju (tablica "transactions").
 *
 * "externalId" je Trading212-ov interni ID transakcije (kolona "ID" u exportu,
 * npr. "EOF46809354484") - koristi se kao prirodni jedinstveni kljuc da ponovni
 * upload istog ili preklapajuceg CSV-a ne stvara duplikate.
 */
@Entity
@Table(name = "transactions", uniqueConstraints = @UniqueConstraint(columnNames = "external_id"))
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true, length = 255)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BrokerType broker;

    private int sourceRowIndex;

    @Column(name = "date_time", nullable = false)
    private LocalDateTime dateTime;

    @Enumerated(EnumType.STRING)
    private TransactionType type;

    private String isin;
    private String ticker;
    private String name;

    @Column(precision = 24, scale = 8)
    private BigDecimal quantity;

    @Column(name = "price_per_share", precision = 24, scale = 8)
    private BigDecimal pricePerShare;

    private String currency;

    @Column(name = "exchange_rate_to_eur", precision = 24, scale = 8)
    private BigDecimal exchangeRateToEur;

    @Column(name = "fees_eur", precision = 24, scale = 4)
    private BigDecimal feesEur;

    @Column(name = "total_eur", precision = 24, scale = 4)
    private BigDecimal totalEur;

    /**
     * Ako je postavljeno, FIFO grupira ISKLJUCIVO po ovom kljucu umjesto po
     * ISIN-u/tickeru - koristi se za pred-uparene lotove iz izvjestaja koji vec
     * daju tocan par datum stjecanja/datum prodaje (npr. Revolut Tax Report),
     * da se sintetski BUY+SELL par te transakcije nikad ne pomijesa s drugim
     * redovima istog ISIN-a. NULL za obicne (negrupirane) transakcije.
     */
    @Column(name = "lot_group_key", length = 128)
    private String lotGroupKey;

    public Transaction() {
    }

    private Transaction(Builder b) {
        this.externalId = b.externalId;
        this.broker = b.broker;
        this.sourceRowIndex = b.sourceRowIndex;
        this.dateTime = b.dateTime;
        this.type = b.type;
        this.isin = b.isin;
        this.ticker = b.ticker;
        this.name = b.name;
        this.quantity = b.quantity;
        this.pricePerShare = b.pricePerShare;
        this.currency = b.currency;
        this.exchangeRateToEur = b.exchangeRateToEur;
        this.feesEur = b.feesEur;
        this.totalEur = b.totalEur;
        this.lotGroupKey = b.lotGroupKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getId() { return id; }
    public String getExternalId() { return externalId; }
    public BrokerType getBroker() { return broker; }
    public int getSourceRowIndex() { return sourceRowIndex; }
    public LocalDateTime getDateTime() { return dateTime; }
    public TransactionType getType() { return type; }
    public String getIsin() { return isin; }
    public String getTicker() { return ticker; }
    public String getName() { return name; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPricePerShare() { return pricePerShare; }
    public String getCurrency() { return currency; }
    public BigDecimal getExchangeRateToEur() { return exchangeRateToEur; }
    public BigDecimal getFeesEur() { return feesEur; }
    public BigDecimal getTotalEur() { return totalEur; }
    public String getLotGroupKey() { return lotGroupKey; }

    public void setId(Long v) { this.id = v; }
    public void setExternalId(String v) { this.externalId = v; }
    public void setBroker(BrokerType v) { this.broker = v; }
    public void setSourceRowIndex(int v) { this.sourceRowIndex = v; }
    public void setDateTime(LocalDateTime v) { this.dateTime = v; }
    public void setType(TransactionType v) { this.type = v; }
    public void setIsin(String v) { this.isin = v; }
    public void setTicker(String v) { this.ticker = v; }
    public void setName(String v) { this.name = v; }
    public void setQuantity(BigDecimal v) { this.quantity = v; }
    public void setPricePerShare(BigDecimal v) { this.pricePerShare = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setExchangeRateToEur(BigDecimal v) { this.exchangeRateToEur = v; }
    public void setFeesEur(BigDecimal v) { this.feesEur = v; }
    public void setTotalEur(BigDecimal v) { this.totalEur = v; }
    public void setLotGroupKey(String v) { this.lotGroupKey = v; }

    public static class Builder {
        private String externalId;
        private BrokerType broker;
        private int sourceRowIndex;
        private LocalDateTime dateTime;
        private TransactionType type;
        private String isin;
        private String ticker;
        private String name;
        private BigDecimal quantity;
        private BigDecimal pricePerShare;
        private String currency;
        private BigDecimal exchangeRateToEur;
        private BigDecimal feesEur;
        private BigDecimal totalEur;
        private String lotGroupKey;

        public Builder externalId(String v) { this.externalId = v; return this; }
        public Builder broker(BrokerType v) { this.broker = v; return this; }
        public Builder sourceRowIndex(int v) { this.sourceRowIndex = v; return this; }
        public Builder dateTime(LocalDateTime v) { this.dateTime = v; return this; }
        public Builder type(TransactionType v) { this.type = v; return this; }
        public Builder isin(String v) { this.isin = v; return this; }
        public Builder ticker(String v) { this.ticker = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder quantity(BigDecimal v) { this.quantity = v; return this; }
        public Builder pricePerShare(BigDecimal v) { this.pricePerShare = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder exchangeRateToEur(BigDecimal v) { this.exchangeRateToEur = v; return this; }
        public Builder feesEur(BigDecimal v) { this.feesEur = v; return this; }
        public Builder totalEur(BigDecimal v) { this.totalEur = v; return this; }
        public Builder lotGroupKey(String v) { this.lotGroupKey = v; return this; }

        public Transaction build() { return new Transaction(this); }
    }
}
