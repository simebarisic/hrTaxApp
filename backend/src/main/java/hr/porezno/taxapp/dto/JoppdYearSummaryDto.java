package hr.porezno.taxapp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record JoppdYearSummaryDto(
        int year,
        LocalDate rokPlacanja,
        List<JoppdEntryDto> entries,
        BigDecimal ukupnaOsnovicaEur,
        BigDecimal ukupanPorezEur,
        String napomena
) {}
