package com.eatfood.control.service;

import com.eatfood.control.dto.ReportDtos.ConsumptionRow;
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

    public byte[] toCsv(List<ConsumptionRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(";", HEADERS)).append("\n");
        for (ConsumptionRow r : rows) {
            sb.append(r.businessDate()).append(';')
              .append(r.consumedAt() != null ? r.consumedAt().format(DT) : "").append(';')
              .append(safe(r.identityCard())).append(';')
              .append(safe(r.employeeName())).append(';')
              .append(safe(r.positionName())).append(';')
              .append(safe(r.cateringName())).append(';')
              .append(safe(r.mealName())).append(';')
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
            throw new RuntimeException("Error generando Excel", e);
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
            throw new RuntimeException("Error generando PDF", e);
        }
    }

    private String safe(String v) {
        return v == null ? "" : v.replace(";", ",");
    }
}
