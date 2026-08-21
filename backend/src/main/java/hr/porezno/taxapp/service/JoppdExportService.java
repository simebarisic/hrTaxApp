package hr.porezno.taxapp.service;

import hr.porezno.taxapp.dto.JoppdEntryDto;
import hr.porezno.taxapp.dto.JoppdYearSummaryDto;
import hr.porezno.taxapp.model.MatchedLot;
import hr.porezno.taxapp.model.TaxYearSummary;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;

@Service
public class JoppdExportService {

    public List<JoppdYearSummaryDto> buildSummaries(List<TaxYearSummary> taxYearSummaries) {
        return taxYearSummaries.stream()
                .map(this::toDto)
                .toList();
    }

    private JoppdYearSummaryDto toDto(TaxYearSummary summary) {
        List<JoppdEntryDto> entries = summary.getTaxableLots().stream()
                .map(this::toEntry)
                .toList();

        // Priblizan rok: krajem veljace sljedece godine (u praksi datum moze
        // biti pomaknut na sljedeci radni dan ako pada na vikend - provjeriti
        // sluzbeni kalendar Porezne uprave za tocan datum svake godine).
        LocalDate approxDeadline = LocalDate.of(summary.getYear() + 1, Month.FEBRUARY, 28);

        String napomena = summary.getNetTaxableBaseEur().signum() == 0
                ? "Nema porezne obveze za ovu godinu (neto rezultat oporezivih transakcija <= 0)."
                : "Provjeriti tocnu oznaku primitka i rok predaje JOPPD obrasca na porezna-uprava.gov.hr prije podnosenja.";

        return new JoppdYearSummaryDto(
                summary.getYear(),
                approxDeadline,
                entries,
                summary.getNetTaxableBaseEur(),
                summary.getTaxDueEur(),
                napomena
        );
    }

    private JoppdEntryDto toEntry(MatchedLot lot) {
        return new JoppdEntryDto(
                lot.getSellDate().toLocalDate(),
                lot.getIsin(),
                lot.getTicker(),
                lot.getBroker() != null ? lot.getBroker().name() : null,
                lot.getQuantity(),
                lot.getProceedsEur(),
                lot.getCostBasisEur(),
                lot.getGainLossEur(),
                lot.getGainLossEur().signum() < 0 ? "Gubitak - umanjuje osnovicu unutar iste godine" : null
        );
    }
}
