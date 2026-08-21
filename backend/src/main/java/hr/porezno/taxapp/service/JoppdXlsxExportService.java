package hr.porezno.taxapp.service;

import hr.porezno.taxapp.dto.JoppdEntryDto;
import hr.porezno.taxapp.dto.JoppdYearSummaryDto;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generira Excel (.xlsx) sazetak pripremljen za JOPPD - jedan list po godini,
 * s tablicom oporezivih transakcija i ukupnim iznosima. Namijenjeno za
 * predaju knjigovodi/poreznom savjetniku ili kao osobna evidencija, NE kao
 * sluzbeni format za e-Poreznu (vidi napomenu u JoppdEntryDto).
 */
@Service
public class JoppdXlsxExportService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public byte[] generate(List<JoppdYearSummaryDto> summaries) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle titleStyle = titleStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle currencyStyle = currencyStyle(workbook);
            CellStyle totalLabelStyle = totalLabelStyle(workbook);
            CellStyle totalCurrencyStyle = totalCurrencyStyle(workbook);

            if (summaries.isEmpty()) {
                Sheet sheet = workbook.createSheet("Nema podataka");
                Row row = sheet.createRow(0);
                row.createCell(0).setCellValue("Nema realiziranih oporezivih prodaja u ucitanim podacima.");
            }

            for (JoppdYearSummaryDto summary : summaries) {
                Sheet sheet = workbook.createSheet(String.valueOf(summary.year()));
                int rowIdx = 0;

                Row title = sheet.createRow(rowIdx++);
                Cell titleCell = title.createCell(0);
                titleCell.setCellValue("Porez na kapitalnu dobit - " + summary.year() + ". godina");
                titleCell.setCellStyle(titleStyle);
                rowIdx++; // prazan red

                String[] headers = {"Datum prodaje", "Broker", "Ticker", "ISIN", "Kolicina",
                        "Nabavna vrijednost (EUR)", "Prodajna vrijednost (EUR)", "Dobit/gubitak (EUR)"};
                Row headerRow = sheet.createRow(rowIdx++);
                for (int i = 0; i < headers.length; i++) {
                    Cell c = headerRow.createCell(i);
                    c.setCellValue(headers[i]);
                    c.setCellStyle(headerStyle);
                }

                for (JoppdEntryDto entry : summary.entries()) {
                    Row row = sheet.createRow(rowIdx++);
                    row.createCell(0).setCellValue(entry.sellDate() != null ? entry.sellDate().format(DATE_FORMAT) : "");
                    row.createCell(1).setCellValue(entry.broker() != null ? entry.broker() : "");
                    row.createCell(2).setCellValue(entry.ticker() != null ? entry.ticker() : "");
                    row.createCell(3).setCellValue(entry.isin() != null ? entry.isin() : "");

                    Cell qtyCell = row.createCell(4);
                    if (entry.quantity() != null) qtyCell.setCellValue(entry.quantity().doubleValue());

                    setCurrencyCell(row.createCell(5), entry.costBasisEur(), currencyStyle);
                    setCurrencyCell(row.createCell(6), entry.proceedsEur(), currencyStyle);
                    setCurrencyCell(row.createCell(7), entry.gainEur(), currencyStyle);
                }

                rowIdx++; // prazan red prije ukupnog
                Row totalBaseRow = sheet.createRow(rowIdx++);
                totalBaseRow.createCell(0).setCellValue("Ukupna porezna osnovica");
                totalBaseRow.getCell(0).setCellStyle(totalLabelStyle);
                setCurrencyCell(totalBaseRow.createCell(7), summary.ukupnaOsnovicaEur(), totalCurrencyStyle);

                Row totalTaxRow = sheet.createRow(rowIdx++);
                totalTaxRow.createCell(0).setCellValue("Porez (12%)");
                totalTaxRow.getCell(0).setCellStyle(totalLabelStyle);
                setCurrencyCell(totalTaxRow.createCell(7), summary.ukupanPorezEur(), totalCurrencyStyle);

                rowIdx++;
                Row noteRow = sheet.createRow(rowIdx);
                noteRow.createCell(0).setCellValue(summary.napomena());

                setFixedColumnWidths(sheet);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void setCurrencyCell(Cell cell, java.math.BigDecimal value, CellStyle style) {
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    /**
     * Fiksne sirine stupaca (u jedinicama od 1/256 sirine znaka "0") umjesto
     * Sheet.autoSizeColumn() - potonji koristi AWT za mjerenje fonta, sto moze
     * baciti gresku ili raditi netocno na minimalnim JRE runtime okruzenjima
     * bez fontconfig-a (npr. eclipse-temurin:*-jre-alpine u nasem Dockerfileu).
     */
    private void setFixedColumnWidths(Sheet sheet) {
        int[] widths = {12, 12, 10, 16, 10, 20, 20, 18};
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
    }

    private CellStyle titleStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle currencyStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00 \"EUR\""));
        return style;
    }

    private CellStyle totalLabelStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle totalCurrencyStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00 \"EUR\""));
        return style;
    }
}
