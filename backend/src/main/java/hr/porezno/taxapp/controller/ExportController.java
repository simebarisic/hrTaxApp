package hr.porezno.taxapp.controller;

import hr.porezno.taxapp.dto.JoppdYearSummaryDto;
import hr.porezno.taxapp.model.MatchedLot;
import hr.porezno.taxapp.model.TaxYearSummary;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.repository.TransactionRepository;
import hr.porezno.taxapp.service.FifoMatchingService;
import hr.porezno.taxapp.service.JoppdExportService;
import hr.porezno.taxapp.service.JoppdPdfExportService;
import hr.porezno.taxapp.service.JoppdXlsxExportService;
import hr.porezno.taxapp.service.TaxCalculationService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Preuzimanje pripremljenog JOPPD sazetka kao PDF ili Excel datoteke, nad
 * CIJELOM spremljenom poviješcu (isti obracun kao GET /api/reports/summary,
 * samo u downloadable formatu umjesto JSON-a).
 */
@RestController
@RequestMapping("/api/reports/export")
public class ExportController {

    private final TransactionRepository transactionRepository;
    private final FifoMatchingService fifoMatchingService;
    private final TaxCalculationService taxCalculationService;
    private final JoppdExportService joppdExportService;
    private final JoppdXlsxExportService xlsxExportService;
    private final JoppdPdfExportService pdfExportService;

    public ExportController(TransactionRepository transactionRepository,
                             FifoMatchingService fifoMatchingService,
                             TaxCalculationService taxCalculationService,
                             JoppdExportService joppdExportService,
                             JoppdXlsxExportService xlsxExportService,
                             JoppdPdfExportService pdfExportService) {
        this.transactionRepository = transactionRepository;
        this.fifoMatchingService = fifoMatchingService;
        this.taxCalculationService = taxCalculationService;
        this.joppdExportService = joppdExportService;
        this.xlsxExportService = xlsxExportService;
        this.pdfExportService = pdfExportService;
    }

    @GetMapping("/xlsx")
    public ResponseEntity<?> exportXlsx() {
        try {
            List<JoppdYearSummaryDto> summaries = computeJoppdSummaries();
            byte[] bytes = xlsxExportService.generate(summaries);
            return fileResponse(bytes,
                    MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                    fileName("xlsx"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Greska pri generiranju Excel datoteke: " + e.getMessage()));
        }
    }

    @GetMapping("/pdf")
    public ResponseEntity<?> exportPdf() {
        try {
            List<JoppdYearSummaryDto> summaries = computeJoppdSummaries();
            byte[] bytes = pdfExportService.generate(summaries);
            return fileResponse(bytes, MediaType.APPLICATION_PDF, fileName("pdf"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Greska pri generiranju PDF datoteke: " + e.getMessage()));
        }
    }

    private List<JoppdYearSummaryDto> computeJoppdSummaries() {
        List<Transaction> allTransactions = transactionRepository.findAllByOrderByDateTimeAsc();
        List<MatchedLot> matchedLots = fifoMatchingService.match(allTransactions);
        List<TaxYearSummary> taxYearSummaries = taxCalculationService.calculate(matchedLots);
        return joppdExportService.buildSummaries(taxYearSummaries);
    }

    private ResponseEntity<byte[]> fileResponse(byte[] bytes, MediaType contentType, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return ResponseEntity.ok()
                .contentType(contentType)
                .headers(headers)
                .body(bytes);
    }

    private String fileName(String extension) {
        return "joppd-priprema-" + LocalDate.now() + "." + extension;
    }
}
