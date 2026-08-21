package hr.porezno.taxapp.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Rezultat FIFO uparivanja: dio prodaje uparen s (dijelom) najstarije jos
 * neuparene kupnje za isti ISIN.
 *
 * Jedna SELL transakcija moze proizvesti vise MatchedLot zapisa ako je
 * uparena s vise razlicitih BUY redova.
 */
public class MatchedLot {

    private String isin;
    private String ticker;
    private BrokerType broker;
    private LocalDateTime buyDate;
    private LocalDateTime sellDate;
    private BigDecimal quantity;
    private BigDecimal costBasisEur;
    private BigDecimal proceedsEur;
    private BigDecimal gainLossEur;

    public MatchedLot() {
    }

    private MatchedLot(Builder b) {
        this.isin = b.isin;
        this.ticker = b.ticker;
        this.broker = b.broker;
        this.buyDate = b.buyDate;
        this.sellDate = b.sellDate;
        this.quantity = b.quantity;
        this.costBasisEur = b.costBasisEur;
        this.proceedsEur = b.proceedsEur;
        this.gainLossEur = b.gainLossEur;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Je li drzano dulje od 2 godine (clanak 67. st. 8. ZPD-a) u trenutku
     * prodaje. Ako true, dobitak se NE oporezuje, bez obzira na iznos.
     */
    public boolean isExemptOverTwoYears() {
        if (buyDate == null || sellDate == null) return false;
        LocalDate twoYearsAfterBuy = buyDate.toLocalDate().plusYears(2);
        return sellDate.toLocalDate().isAfter(twoYearsAfterBuy);
    }

    public String getIsin() { return isin; }
    public String getTicker() { return ticker; }
    public BrokerType getBroker() { return broker; }
    public LocalDateTime getBuyDate() { return buyDate; }
    public LocalDateTime getSellDate() { return sellDate; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getCostBasisEur() { return costBasisEur; }
    public BigDecimal getProceedsEur() { return proceedsEur; }
    public BigDecimal getGainLossEur() { return gainLossEur; }

    public void setIsin(String v) { this.isin = v; }
    public void setTicker(String v) { this.ticker = v; }
    public void setBroker(BrokerType v) { this.broker = v; }
    public void setBuyDate(LocalDateTime v) { this.buyDate = v; }
    public void setSellDate(LocalDateTime v) { this.sellDate = v; }
    public void setQuantity(BigDecimal v) { this.quantity = v; }
    public void setCostBasisEur(BigDecimal v) { this.costBasisEur = v; }
    public void setProceedsEur(BigDecimal v) { this.proceedsEur = v; }
    public void setGainLossEur(BigDecimal v) { this.gainLossEur = v; }

    public static class Builder {
        private String isin;
        private String ticker;
        private BrokerType broker;
        private LocalDateTime buyDate;
        private LocalDateTime sellDate;
        private BigDecimal quantity;
        private BigDecimal costBasisEur;
        private BigDecimal proceedsEur;
        private BigDecimal gainLossEur;

        public Builder isin(String v) { this.isin = v; return this; }
        public Builder ticker(String v) { this.ticker = v; return this; }
        public Builder broker(BrokerType v) { this.broker = v; return this; }
        public Builder buyDate(LocalDateTime v) { this.buyDate = v; return this; }
        public Builder sellDate(LocalDateTime v) { this.sellDate = v; return this; }
        public Builder quantity(BigDecimal v) { this.quantity = v; return this; }
        public Builder costBasisEur(BigDecimal v) { this.costBasisEur = v; return this; }
        public Builder proceedsEur(BigDecimal v) { this.proceedsEur = v; return this; }
        public Builder gainLossEur(BigDecimal v) { this.gainLossEur = v; return this; }

        public MatchedLot build() { return new MatchedLot(this); }
    }
}
