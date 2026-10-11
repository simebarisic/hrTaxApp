package hr.porezno.taxapp.controller;

import hr.porezno.taxapp.dto.JoppdYearSummaryDto;
import hr.porezno.taxapp.service.JoppdPdfExportService;
import hr.porezno.taxapp.service.JoppdXlsxExportService;
import hr.porezno.taxapp.service.TaxReportService;
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

    private final TaxReportService taxReportService;
    private final JoppdXlsxExportService xlsxExportService;
    private final JoppdPdfExportService pdfExportService;

    public ExportController(TaxReportService taxReportService,
                             JoppdXlsxExportService xlsxExportService,
                             JoppdPdfExportService pdfExportService) {
        this.taxReportService = taxReportService;
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
        return taxReportService.buildFromFullHistory().joppdSummaries();
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
