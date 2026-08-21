package hr.porezno.taxapp.dto;

import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.MatchedLot;
import hr.porezno.taxapp.model.TaxYearSummary;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.parser.ParseResult;

import java.util.List;
import java.util.Map;

public record TaxReportResponse(
        int parsedTransactionCount,
        int newlyImportedCount,
        int totalStoredCount,
        List<Transaction> transactions,
        List<MatchedLot> matchedLots,
        List<TaxYearSummary> taxYearSummaries,
        Map<BrokerType, List<TaxYearSummary>> taxYearSummariesByBroker,
        List<JoppdYearSummaryDto> joppdSummaries,
        List<String> warnings,
        List<ParseResult.SkippedRow> skippedRows
) {}
