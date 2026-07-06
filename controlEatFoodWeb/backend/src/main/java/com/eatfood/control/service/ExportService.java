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
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ExportService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String[] HEADERS =
            {"Fecha", "Hora", "Cédula", "Empleado", "Cargo", "Catering", "Comida", "Offline"};
    private static final String[] EMP_HEADERS =
            {"Código", "Cédula", "Nombre", "Cargo", "Almuerzo", "Merienda", "Estado",
             "N.º Huellas", "Observación"};

    public byte[] toCsv(List<ConsumptionRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(";", HEADERS)).append("\n");
        for (ConsumptionRow r : rows) {
            sb.append(csv(r.businessDate() != null ? r.businessDate().toString() : "")).append(';')
              .append(csv(r.consumedAt() != null ? r.consumedAt().format(DT) : "")).append(';')
              .append(csv(r.identityCard())).append(';')
              .append(csv(r.employeeName())).append(';')
              .append(csv(r.positionName())).append(';')
              .append(csv(r.cateringName())).append(';')
              .append(csv(r.mealName())).append(';')
              .append(r.offline() ? "Sí" : "No").append('\n');
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] toExcel(List<ConsumptionRow> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Consumos");
            CellStyle headerStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }
            int rn = 1;
            for (ConsumptionRow r : rows) {
                Row row = sheet.createRow(rn++);
                row.createCell(0).setCellValue(String.valueOf(r.businessDate()));
                row.createCell(1).setCellValue(r.consumedAt() != null ? r.consumedAt().format(DT) : "");
                row.createCell(2).setCellValue(safe(r.identityCard()));
                row.createCell(3).setCellValue(safe(r.employeeName()));
                row.createCell(4).setCellValue(safe(r.positionName()));
                row.createCell(5).setCellValue(safe(r.cateringName()));
                row.createCell(6).setCellValue(safe(r.mealName()));
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
                table.addCell(new Phrase(safe(r.positionName()), cf));
                table.addCell(new Phrase(safe(r.cateringName()), cf));
                table.addCell(new Phrase(safe(r.mealName()), cf));
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
              .append(csv(r.positionTitle())).append(';')
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
                row.createCell(3).setCellValue(safe(r.positionTitle()));
                row.createCell(4).setCellValue(r.allowsLunch() ? "Sí" : "No");
                row.createCell(5).setCellValue(r.effectiveSnack() ? "Sí" : "No");
                row.createCell(6).setCellValue(safe(r.status()));
                row.createCell(7).setCellValue(r.fingerprintCount());
                row.createCell(8).setCellValue(safe(r.observation()));
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
