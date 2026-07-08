package com.eatfood.control.service;

import com.eatfood.control.dto.EmployeeDtos.EmployeeResponse;
import com.eatfood.control.dto.ReportDtos.ConsumptionRow;
import com.eatfood.control.exception.BusinessException;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class ExportService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String[] HEADERS =
            {"Fecha", "Hora", "Cédula", "Empleado", "Restaurante", "Comida", "Observación", "Offline"};
    private static final String[] EMP_HEADERS =
            {"Código", "Cédula", "Nombre", "Almuerzo", "Merienda", "Estado",
             "N.º Huellas", "Observación"};

    public byte[] toCsv(List<ConsumptionRow> rows, String title) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(csv(title)).append("\n\n");
        }
        sb.append(String.join(";", HEADERS)).append("\n");
        for (ConsumptionRow r : rows) {
            sb.append(csv(r.businessDate() != null ? r.businessDate().toString() : "")).append(';')
              .append(csv(r.consumedAt() != null ? r.consumedAt().format(DT) : "")).append(';')
              .append(csv(r.identityCard())).append(';')
              .append(csv(r.employeeName())).append(';')
              .append(csv(r.restaurantName())).append(';')
              .append(csv(r.mealName())).append(';')
              .append(csv(r.observation())).append(';')
              .append(r.offline() ? "Sí" : "No").append('\n');
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] toExcel(List<ConsumptionRow> rows, String title) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Consumos");
            CellStyle headerStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            int headerRowIdx = 0;
            if (title != null && !title.isBlank()) {
                CellStyle titleStyle = wb.createCellStyle();
                org.apache.poi.ss.usermodel.Font titleFont = wb.createFont();
                titleFont.setBold(true);
                titleFont.setFontHeightInPoints((short) 14);
                titleStyle.setFont(titleFont);
                Row titleRow = sheet.createRow(0);
                Cell titleCell = titleRow.createCell(0);
                titleCell.setCellValue(title);
                titleCell.setCellStyle(titleStyle);
                headerRowIdx = 2;
            }

            Row header = sheet.createRow(headerRowIdx);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }
            int rn = headerRowIdx + 1;
            for (ConsumptionRow r : rows) {
                Row row = sheet.createRow(rn++);
                row.createCell(0).setCellValue(String.valueOf(r.businessDate()));
                row.createCell(1).setCellValue(r.consumedAt() != null ? r.consumedAt().format(DT) : "");
                row.createCell(2).setCellValue(safe(r.identityCard()));
                row.createCell(3).setCellValue(safe(r.employeeName()));
                row.createCell(4).setCellValue(safe(r.restaurantName()));
                row.createCell(5).setCellValue(safe(r.mealName()));
                row.createCell(6).setCellValue(safe(r.observation()));
                row.createCell(7).setCellValue(r.offline() ? "Sí" : "No");
            }
            for (int i = 0; i < HEADERS.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("EXPORT_FAILED", "No se pudo generar el Excel.");
        }
    }

    public byte[] toPdf(List<ConsumptionRow> rows, String title) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4.rotate(), 24, 24, 30, 24);
            PdfWriter.getInstance(doc, out);
            doc.open();
            Font titleFont = new Font(Font.HELVETICA, 14, Font.BOLD);
            doc.add(new Paragraph(title != null ? title : "Reporte de Consumos", titleFont));
            doc.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(HEADERS.length);
            table.setWidthPercentage(100);
            Font hf = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
            for (String h : HEADERS) {
                PdfPCell cell = new PdfPCell(new Phrase(h, hf));
                cell.setBackgroundColor(new Color(33, 102, 172));
                cell.setPadding(4);
                table.addCell(cell);
            }
            Font cf = new Font(Font.HELVETICA, 8);
            for (ConsumptionRow r : rows) {
                table.addCell(new Phrase(String.valueOf(r.businessDate()), cf));
                table.addCell(new Phrase(r.consumedAt() != null ? r.consumedAt().format(DT) : "", cf));
                table.addCell(new Phrase(safe(r.identityCard()), cf));
                table.addCell(new Phrase(safe(r.employeeName()), cf));
                table.addCell(new Phrase(safe(r.restaurantName()), cf));
                table.addCell(new Phrase(safe(r.mealName()), cf));
                table.addCell(new Phrase(safe(r.observation()), cf));
                table.addCell(new Phrase(r.offline() ? "Sí" : "No", cf));
            }
            doc.add(table);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("EXPORT_FAILED", "No se pudo generar el PDF.");
        }
    }

    // ------------------------------------------------------------------------
    // Reporte diario del Kiosk (con conteo de platos al final)
    // ------------------------------------------------------------------------
    private static final String[] KIOSK_HEADERS =
            {"#", "Hora", "Cédula", "Empleado", "Comida", "Observación"};

    public byte[] kioskDailyCsv(String restaurantName, LocalDate date,
                                List<ConsumptionRow> rows, Map<String, Long> plateCounts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Reporte Diario - ").append(restaurantName)
          .append(" - Fecha: ").append(date.format(DATE_FMT)).append("\n\n");
        sb.append(String.join(";", KIOSK_HEADERS)).append("\n");
        int num = 1;
        for (ConsumptionRow r : rows) {
            sb.append(num++).append(';')
              .append(csv(r.consumedAt() != null ? r.consumedAt().format(DT) : "")).append(';')
              .append(csv(r.identityCard())).append(';')
              .append(csv(r.employeeName())).append(';')
              .append(csv(r.mealName())).append(';')
              .append(csv(r.observation())).append('\n');
        }
        sb.append("\n");
        sb.append("RESUMEN DE PLATOS").append("\n");
        long total = 0;
        for (Map.Entry<String, Long> entry : plateCounts.entrySet()) {
            sb.append(csv(entry.getKey())).append(':').append(';')
              .append(entry.getValue()).append('\n');
            total += entry.getValue();
        }
        sb.append("TOTAL").append(':').append(';').append(total).append('\n');
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] kioskDailyExcel(String restaurantName, LocalDate date,
                                  List<ConsumptionRow> rows, Map<String, Long> plateCounts) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Reporte Diario");
            CellStyle headerStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            CellStyle titleStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);

            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Reporte Diario - " + restaurantName + " - " + date.format(DATE_FMT));
            titleCell.setCellStyle(titleStyle);

            Row header = sheet.createRow(2);
            for (int i = 0; i < KIOSK_HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(KIOSK_HEADERS[i]);
                c.setCellStyle(headerStyle);
            }
            int rn = 3;
            int num = 1;
            for (ConsumptionRow r : rows) {
                Row row = sheet.createRow(rn++);
                row.createCell(0).setCellValue(num++);
                row.createCell(1).setCellValue(r.consumedAt() != null ? r.consumedAt().format(DT) : "");
                row.createCell(2).setCellValue(safe(r.identityCard()));
                row.createCell(3).setCellValue(safe(r.employeeName()));
                row.createCell(4).setCellValue(safe(r.mealName()));
                row.createCell(5).setCellValue(safe(r.observation()));
            }

            rn += 1;
            Row summaryTitle = sheet.createRow(rn++);
            Cell stCell = summaryTitle.createCell(0);
            stCell.setCellValue("RESUMEN DE PLATOS");
            stCell.setCellStyle(headerStyle);

            long total = 0;
            for (Map.Entry<String, Long> entry : plateCounts.entrySet()) {
                Row sr = sheet.createRow(rn++);
                sr.createCell(0).setCellValue(entry.getKey());
                sr.createCell(1).setCellValue(entry.getValue());
                total += entry.getValue();
            }
            Row totalRow = sheet.createRow(rn);
            Cell tc0 = totalRow.createCell(0);
            tc0.setCellValue("TOTAL");
            tc0.setCellStyle(headerStyle);
            Cell tc1 = totalRow.createCell(1);
            tc1.setCellValue(total);
            tc1.setCellStyle(headerStyle);

            for (int i = 0; i < KIOSK_HEADERS.length; i++) sheet.autoSizeColumn(i);
            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("EXPORT_FAILED", "No se pudo generar el Excel.");
        }
    }

    public byte[] kioskDailyPdf(String restaurantName, LocalDate date,
                                List<ConsumptionRow> rows, Map<String, Long> plateCounts) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4.rotate(), 24, 24, 30, 24);
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font titleFont = new Font(Font.HELVETICA, 14, Font.BOLD);
            doc.add(new Paragraph("Reporte Diario - " + restaurantName, titleFont));
            Font subFont = new Font(Font.HELVETICA, 10, Font.NORMAL);
            doc.add(new Paragraph("Fecha: " + date.format(DATE_FMT), subFont));
            doc.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(KIOSK_HEADERS.length);
            table.setWidthPercentage(100);
            Font hf = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
            for (String h : KIOSK_HEADERS) {
                PdfPCell cell = new PdfPCell(new Phrase(h, hf));
                cell.setBackgroundColor(new Color(33, 102, 172));
                cell.setPadding(4);
                table.addCell(cell);
            }
            Font cf = new Font(Font.HELVETICA, 8);
            int num = 1;
            for (ConsumptionRow r : rows) {
                table.addCell(new Phrase(String.valueOf(num++), cf));
                table.addCell(new Phrase(r.consumedAt() != null ? r.consumedAt().format(DT) : "", cf));
                table.addCell(new Phrase(safe(r.identityCard()), cf));
                table.addCell(new Phrase(safe(r.employeeName()), cf));
                table.addCell(new Phrase(safe(r.mealName()), cf));
                table.addCell(new Phrase(safe(r.observation()), cf));
            }
            doc.add(table);
            doc.add(new Paragraph(" "));

            Font sumTitleFont = new Font(Font.HELVETICA, 11, Font.BOLD);
            doc.add(new Paragraph("RESUMEN DE PLATOS", sumTitleFont));

            PdfPTable sumTable = new PdfPTable(2);
            sumTable.setWidthPercentage(40);
            sumTable.setHorizontalAlignment(PdfPTable.ALIGN_LEFT);
            Font shf = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
            PdfPCell sh1 = new PdfPCell(new Phrase("Tipo de Comida", shf));
            sh1.setBackgroundColor(new Color(33, 102, 172));
            sh1.setPadding(4);
            sumTable.addCell(sh1);
            PdfPCell sh2 = new PdfPCell(new Phrase("Cantidad", shf));
            sh2.setBackgroundColor(new Color(33, 102, 172));
            sh2.setPadding(4);
            sumTable.addCell(sh2);

            Font scf = new Font(Font.HELVETICA, 9);
            long total = 0;
            for (Map.Entry<String, Long> entry : plateCounts.entrySet()) {
                sumTable.addCell(new Phrase(entry.getKey(), scf));
                sumTable.addCell(new Phrase(String.valueOf(entry.getValue()), scf));
                total += entry.getValue();
            }
            Font tf = new Font(Font.HELVETICA, 10, Font.BOLD);
            PdfPCell totalLabel = new PdfPCell(new Phrase("TOTAL", tf));
            totalLabel.setPadding(4);
            sumTable.addCell(totalLabel);
            PdfPCell totalVal = new PdfPCell(new Phrase(String.valueOf(total), tf));
            totalVal.setPadding(4);
            sumTable.addCell(totalVal);

            doc.add(sumTable);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("EXPORT_FAILED", "No se pudo generar el PDF.");
        }
    }

    // ------------------------------------------------------------------------
    // Exportación de la base de empleados. Nunca se exporta el template
    // biométrico: sólo el conteo de huellas (fingerprintCount).
    // ------------------------------------------------------------------------
    public byte[] employeesToCsv(List<EmployeeResponse> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(";", EMP_HEADERS)).append("\n");
        for (EmployeeResponse r : rows) {
            sb.append(csv(r.publicCode())).append(';')
              .append(csv(r.identityCard())).append(';')
              .append(csv(r.fullName())).append(';')
              .append(r.allowsLunch() ? "Sí" : "No").append(';')
              .append(r.effectiveSnack() ? "Sí" : "No").append(';')
              .append(csv(r.status())).append(';')
              .append(r.fingerprintCount()).append(';')
              .append(csv(r.observation())).append('\n');
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] employeesToExcel(List<EmployeeResponse> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Empleados");
            CellStyle headerStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int i = 0; i < EMP_HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(EMP_HEADERS[i]);
                c.setCellStyle(headerStyle);
            }
            int rn = 1;
            for (EmployeeResponse r : rows) {
                Row row = sheet.createRow(rn++);
                row.createCell(0).setCellValue(safe(r.publicCode()));
                row.createCell(1).setCellValue(safe(r.identityCard()));
                row.createCell(2).setCellValue(safe(r.fullName()));
                row.createCell(3).setCellValue(r.allowsLunch() ? "Sí" : "No");
                row.createCell(4).setCellValue(r.effectiveSnack() ? "Sí" : "No");
                row.createCell(5).setCellValue(safe(r.status()));
                row.createCell(6).setCellValue(r.fingerprintCount());
                row.createCell(7).setCellValue(safe(r.observation()));
            }
            for (int i = 0; i < EMP_HEADERS.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("EXPORT_FAILED", "No se pudo generar el Excel de empleados.");
        }
    }

    /**
     * Escapa un valor para CSV según RFC 4180: si contiene comillas, punto y coma,
     * salto de línea o comienza con caracteres que un visor podría interpretar como
     * fórmula (=, +, -, @), se envuelve entre comillas duplicando las comillas internas.
     */
    private String csv(String v) {
        if (v == null || v.isEmpty()) return "";
        String s = v.replace(";", ","); // mantener separador ';'
        boolean needsQuoting = s.indexOf(';') >= 0 || s.indexOf(',') >= 0
                || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        boolean formulaLike = !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0;
        if (needsQuoting || formulaLike) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private String safe(String v) {
        return v == null ? "" : v.replace(";", ",");
    }
}
