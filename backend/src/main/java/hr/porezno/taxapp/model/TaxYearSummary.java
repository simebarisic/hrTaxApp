package hr.porezno.taxapp.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Porezni obracun za jednu kalendarsku godinu.
 *
 * Pravilo: porez se placa samo na POZITIVNU razliku zbroja svih oporezivih
 * dobitaka i gubitaka (onih drzanih <= 2 godine) ostvarenih u toj godini.
 * Gubici se ne prenose u sljedecu godinu (clanak 67. ZPD-a).
 */
public class TaxYearSummary {

    private int year;
    private List<MatchedLot> taxableLots;
    private List<MatchedLot> exemptLots;
    private BigDecimal totalTaxableGainsEur;
    private BigDecimal totalTaxableLossesEur;
    private BigDecimal netTaxableBaseEur;
    private BigDecimal taxRate;
    private BigDecimal taxDueEur;
    private BigDecimal totalExemptGainEur;

    public TaxYearSummary() {
    }

    private TaxYearSummary(Builder b) {
        this.year = b.year;
        this.taxableLots = b.taxableLots;
        this.exemptLots = b.exemptLots;
        this.totalTaxableGainsEur = b.totalTaxableGainsEur;
        this.totalTaxableLossesEur = b.totalTaxableLossesEur;
        this.netTaxableBaseEur = b.netTaxableBaseEur;
        this.taxRate = b.taxRate;
        this.taxDueEur = b.taxDueEur;
        this.totalExemptGainEur = b.totalExemptGainEur;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getYear() { return year; }
    public List<MatchedLot> getTaxableLots() { return taxableLots; }
    public List<MatchedLot> getExemptLots() { return exemptLots; }
    public BigDecimal getTotalTaxableGainsEur() { return totalTaxableGainsEur; }
    public BigDecimal getTotalTaxableLossesEur() { return totalTaxableLossesEur; }
    public BigDecimal getNetTaxableBaseEur() { return netTaxableBaseEur; }
    public BigDecimal getTaxRate() { return taxRate; }
    public BigDecimal getTaxDueEur() { return taxDueEur; }
    public BigDecimal getTotalExemptGainEur() { return totalExemptGainEur; }

    public void setYear(int v) { this.year = v; }
    public void setTaxableLots(List<MatchedLot> v) { this.taxableLots = v; }
    public void setExemptLots(List<MatchedLot> v) { this.exemptLots = v; }
    public void setTotalTaxableGainsEur(BigDecimal v) { this.totalTaxableGainsEur = v; }
    public void setTotalTaxableLossesEur(BigDecimal v) { this.totalTaxableLossesEur = v; }
    public void setNetTaxableBaseEur(BigDecimal v) { this.netTaxableBaseEur = v; }
    public void setTaxRate(BigDecimal v) { this.taxRate = v; }
    public void setTaxDueEur(BigDecimal v) { this.taxDueEur = v; }
    public void setTotalExemptGainEur(BigDecimal v) { this.totalExemptGainEur = v; }

    public static class Builder {
        private int year;
        private List<MatchedLot> taxableLots;
        private List<MatchedLot> exemptLots;
        private BigDecimal totalTaxableGainsEur;
        private BigDecimal totalTaxableLossesEur;
        private BigDecimal netTaxableBaseEur;
        private BigDecimal taxRate;
        private BigDecimal taxDueEur;
        private BigDecimal totalExemptGainEur;

        public Builder year(int v) { this.year = v; return this; }
        public Builder taxableLots(List<MatchedLot> v) { this.taxableLots = v; return this; }
        public Builder exemptLots(List<MatchedLot> v) { this.exemptLots = v; return this; }
        public Builder totalTaxableGainsEur(BigDecimal v) { this.totalTaxableGainsEur = v; return this; }
        public Builder totalTaxableLossesEur(BigDecimal v) { this.totalTaxableLossesEur = v; return this; }
        public Builder netTaxableBaseEur(BigDecimal v) { this.netTaxableBaseEur = v; return this; }
        public Builder taxRate(BigDecimal v) { this.taxRate = v; return this; }
        public Builder taxDueEur(BigDecimal v) { this.taxDueEur = v; return this; }
        public Builder totalExemptGainEur(BigDecimal v) { this.totalExemptGainEur = v; return this; }

        public TaxYearSummary build() { return new TaxYearSummary(this); }
    }
}
