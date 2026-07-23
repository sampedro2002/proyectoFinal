package com.eatfood.control.service;

import com.eatfood.control.dto.EmployeeDtos.EmployeeResponse;
import com.eatfood.control.dto.ReportDtos.ConsumptionRow;
import com.eatfood.control.exception.BusinessException;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ExportService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Identidad de marca del sistema (usada en cabeceras y pie de los reportes). */
    private static final String BRAND_NAME = "Club Castillo Amaguaña";
    private static final String BRAND_TAGLINE = "Control de Alimentos";
    private static final Color BRAND_COLOR = new Color(33, 102, 172);   // #2166AC
    private static final Color BRAND_INK = new Color(31, 41, 55);        // #1F2937
    private static final Color BRAND_MUTED = new Color(120, 132, 148);

    /** Colores de marca por metodo de registro (usados en fila de reportes). */
    private static final Color MANUAL_BG  = new Color(254, 243, 199);  // #FEF3C7 (amarillo claro)
    private static final Color MANUAL_INK = new Color(180, 83, 9);     // #B45309 (ambar)
    private static final Color EXTERNAL_BG = new Color(255, 237, 213); // #FFEDD5 (naranja claro)
    private static final Color EXTERNAL_INK = new Color(194, 65, 12); // #C2410C
    private static final Color FINGERPRINT_BG = new Color(244, 247, 251);
    private static final Color FINGERPRINT_INK = new Color(31, 41, 55);

    private static final String LOGO_PATH = "images/logo.png";

    /** Logo cargado una sola vez desde el classpath; null si no se encuentra. */
    private static final byte[] LOGO_BYTES = loadLogo();

    private static byte[] loadLogo() {
        try (InputStream in = new ClassPathResource(LOGO_PATH).getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            LoggerFactory.getLogger(ExportService.class)
                    .warn("No se pudo cargar el logo para los reportes ({}): {}", LOGO_PATH, e.getMessage());
            return null;
        }
    }

    private static final String[] HEADERS =
            {"N°", "Hora", "Cédula", "Empleado", "Restaurante", "Comida",
             "Tipo", "Retira", "Descripción"};
    private static final String[] EMP_HEADERS =
            {"Cédula", "Nombre", "Almuerzo", "Merienda", "Estado",
             "N.º Huellas", "Observación"};

    public byte[] toCsv(List<ConsumptionRow> rows, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append(csv(BRAND_NAME + " - " + BRAND_TAGLINE)).append("\n");
        if (title != null && !title.isBlank()) {
            sb.append(csv(title)).append("\n");
        }
        sb.append("\n");
        sb.append(String.join(";", HEADERS)).append("\n");
        int n = 1;
        for (ConsumptionRow r : rows) {
            sb.append(n++).append(';')
              .append(r.consumedAt() != null ? r.consumedAt().format(DT) : "").append(';')
              .append(csv(r.identityCard())).append(';')
              .append(csv(r.employeeName())).append(';')
              .append(csv(r.restaurantName())).append(';')
              .append(csv(r.mealName())).append(';')
              .append(methodLabel(r.method())).append(';')
              .append(csv(r.proxyEmployeeName())).append(';')
              .append(csv(buildDescription(r))).append('\n');
        }
        sb.append("\n");
        sb.append("RESUMEN DE PLATOS").append("\n");
        Map<String, Long> plateCounts = buildPlateCounts(rows);
        long total = 0;
        for (Map.Entry<String, Long> entry : plateCounts.entrySet()) {
            sb.append(csv(entry.getKey())).append(':').append(';')
              .append(entry.getValue()).append('\n');
            total += entry.getValue();
        }
        sb.append("TOTAL").append(':').append(';').append(total).append('\n');
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------
    // Utilidades de marca para los reportes Excel (banner con logo + cabecera).
    // ------------------------------------------------------------------------

    /**
     * Escribe la cabecera de marca (banner azul con el logo y el nombre de la
     * empresa, más un subtítulo) en las primeras filas de la hoja y devuelve el
     * índice de fila donde debe empezar la cabecera de columnas.
     */
    private int writeExcelHeader(Workbook wb, Sheet sheet, int cols, String subtitle) {
        // Banner de marca (fila 0)
        XSSFCellStyle banner = (XSSFCellStyle) wb.createCellStyle();
        banner.setFillForegroundColor(new XSSFColor(BRAND_COLOR, null));
        banner.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        banner.setVerticalAlignment(VerticalAlignment.CENTER);
        org.apache.poi.ss.usermodel.Font bf = wb.createFont();
        bf.setBold(true);
        bf.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
        bf.setFontHeightInPoints((short) 15);
        banner.setFont(bf);

        Row r0 = sheet.createRow(0);
        r0.setHeightInPoints(38);
        Cell b0 = r0.createCell(0);
        // Espacios iniciales para no solaparse con el logo de la izquierda.
        b0.setCellValue("      " + BRAND_NAME + "  ·  " + BRAND_TAGLINE);
        b0.setCellStyle(banner);
        for (int i = 1; i < cols; i++) r0.createCell(i).setCellStyle(banner);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, cols - 1));

        int headerRowIdx = 2;
        if (subtitle != null && !subtitle.isBlank()) {
            XSSFCellStyle subStyle = (XSSFCellStyle) wb.createCellStyle();
            org.apache.poi.xssf.usermodel.XSSFFont sf =
                    (org.apache.poi.xssf.usermodel.XSSFFont) wb.createFont();
            sf.setBold(true);
            sf.setFontHeightInPoints((short) 11);
            sf.setColor(new XSSFColor(BRAND_INK, null));
            subStyle.setFont(sf);
            Row r1 = sheet.createRow(1);
            r1.setHeightInPoints(18);
            Cell s0 = r1.createCell(0);
            s0.setCellValue(subtitle);
            s0.setCellStyle(subStyle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, cols - 1));
            headerRowIdx = 3;
        }

        // Logo flotante en el extremo izquierdo del banner. Se ancla a una caja
        // explícita (fila 0, columnas 0-1) con MOVE_AND_RESIZE y sin resize(), que
        // es la forma estable de posicionarlo sin depender del autosize posterior.
        if (LOGO_BYTES != null) {
            try {
                int picIdx = wb.addPicture(LOGO_BYTES, Workbook.PICTURE_TYPE_PNG);
                Drawing<?> drawing = sheet.createDrawingPatriarch();
                ClientAnchor anchor = wb.getCreationHelper().createClientAnchor();
                anchor.setAnchorType(ClientAnchor.AnchorType.DONT_MOVE_AND_RESIZE);
                int emu = 9_525; // EMUs por píxel
                anchor.setCol1(0);
                anchor.setRow1(0);
                anchor.setDx1(8 * emu);
                anchor.setDy1(6 * emu);
                anchor.setDx2(8 * emu + 42 * emu);      // ancho ≈ 42 px
                anchor.setDy2(6 * emu + 45 * emu);       // alto  ≈ 45 px
                drawing.createPicture(anchor, picIdx);
            } catch (Exception e) {
                log.warn("No se pudo insertar el logo en el Excel: {}", e.getMessage());
            }
        }
        return headerRowIdx;
    }

    /** Estilo de cabecera de columnas de marca: fondo azul y texto blanco en negrita. */
    private CellStyle excelColumnHeaderStyle(Workbook wb) {
        XSSFCellStyle style = (XSSFCellStyle) wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(BRAND_COLOR, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        org.apache.poi.ss.usermodel.Font f = wb.createFont();
        f.setBold(true);
        f.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
        style.setFont(f);
        return style;
    }

    public byte[] toExcel(List<ConsumptionRow> rows, String title) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Consumos");
            CellStyle headerStyle = excelColumnHeaderStyle(wb);

            int headerRowIdx = writeExcelHeader(wb, sheet, HEADERS.length, title);

            Row header = sheet.createRow(headerRowIdx);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }
            int rn = headerRowIdx + 1;
            int n = 1;
            // Un estilo por método para pintar la fila con su color de marca.
            XSSFCellStyle manualStyle   = (XSSFCellStyle) wb.createCellStyle();
            manualStyle.setFillForegroundColor(new XSSFColor(MANUAL_BG, null));
            manualStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            XSSFCellStyle externalStyle = (XSSFCellStyle) wb.createCellStyle();
            externalStyle.setFillForegroundColor(new XSSFColor(EXTERNAL_BG, null));
            externalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (ConsumptionRow r : rows) {
                Row row = sheet.createRow(rn++);
                row.createCell(0).setCellValue(n++);
                row.createCell(1).setCellValue(r.consumedAt() != null ? r.consumedAt().format(DT) : "");
                row.createCell(2).setCellValue(safe(r.identityCard()));
                row.createCell(3).setCellValue(safe(r.employeeName()));
                row.createCell(4).setCellValue(safe(r.restaurantName()));
                row.createCell(5).setCellValue(safe(r.mealName()));
                row.createCell(6).setCellValue(methodLabel(r.method()));
                row.createCell(7).setCellValue(safe(r.proxyEmployeeName()));
                row.createCell(8).setCellValue(safe(buildDescription(r)));
                if ("MANUAL".equals(r.method()) || "EXTERNAL".equals(r.method())) {
                    XSSFCellStyle style = "MANUAL".equals(r.method()) ? manualStyle : externalStyle;
                    for (int i = 0; i < HEADERS.length; i++) {
                        Cell c = row.getCell(i);
                        if (c == null) c = row.createCell(i);
                        c.setCellStyle(style);
                    }
                }
            }

            // Resumen de platos
            rn += 1;
            Row summaryTitle = sheet.createRow(rn++);
            Cell stCell = summaryTitle.createCell(0);
            stCell.setCellValue("RESUMEN DE PLATOS");
            stCell.setCellStyle(headerStyle);

            Map<String, Long> plateCounts = buildPlateCounts(rows);
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

            for (int i = 0; i < HEADERS.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("EXPORT_FAILED", "No se pudo generar el Excel.");
        }
    }

    public byte[] toPdf(List<ConsumptionRow> rows, String title) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 28, 28, 92, 40);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new BrandPageEvent());
            doc.open();

            addBrandHeading(doc, "Reporte de Consumos",
                    title != null ? title : "Todos los registros");

            PdfPTable table = new PdfPTable(HEADERS.length);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{5f, 12f, 10f, 18f, 15f, 10f, 10f, 13f, 17f});
            table.setHeaderRows(1);
            addHeaderRow(table, HEADERS);
            Font cf = new Font(Font.HELVETICA, 8);
            boolean zebra = false;
            int n = 1;
            for (ConsumptionRow r : rows) {
                Color bg = rowColor(r.method(), (zebra = !zebra) ? FINGERPRINT_BG : Color.WHITE);
                addBodyCell(table, String.valueOf(n++), cf, bg);
                addBodyCell(table, r.consumedAt() != null ? r.consumedAt().format(DT) : "", cf, bg);
                addBodyCell(table, safe(r.identityCard()), cf, bg);
                addBodyCell(table, safe(r.employeeName()), cf, bg);
                addBodyCell(table, safe(r.restaurantName()), cf, bg);
                addBodyCell(table, safe(r.mealName()), cf, bg);
                addBodyCell(table, methodLabel(r.method()), cf, bg);
                addBodyCell(table, safe(r.proxyEmployeeName()), cf, bg);
                addBodyCell(table, safe(buildDescription(r)), cf, bg);
            }
            doc.add(table);
            doc.add(new Paragraph(" "));

            Font sumTitleFont = new Font(Font.HELVETICA, 11, Font.BOLD, BRAND_INK);
            doc.add(new Paragraph("RESUMEN DE PLATOS", sumTitleFont));
            doc.add(new Paragraph(" "));

            PdfPTable sumTable = new PdfPTable(2);
            sumTable.setWidthPercentage(40);
            sumTable.setHorizontalAlignment(PdfPTable.ALIGN_LEFT);
            addHeaderRow(sumTable, new String[]{"Tipo de Comida", "Cantidad"});

            Font scf = new Font(Font.HELVETICA, 9);
            Map<String, Long> plateCounts = buildPlateCounts(rows);
            long total = 0;
            boolean zebra2 = false;
            for (Map.Entry<String, Long> entry : plateCounts.entrySet()) {
                Color bg = (zebra2 = !zebra2) ? new Color(244, 247, 251) : Color.WHITE;
                addBodyCell(sumTable, entry.getKey(), scf, bg);
                addBodyCell(sumTable, String.valueOf(entry.getValue()), scf, bg);
                total += entry.getValue();
            }
            Font tf = new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE);
            PdfPCell totalLabel = new PdfPCell(new Phrase("TOTAL", tf));
            totalLabel.setBackgroundColor(BRAND_INK);
            totalLabel.setBorderColor(BRAND_INK);
            totalLabel.setPadding(5);
            sumTable.addCell(totalLabel);
            PdfPCell totalVal = new PdfPCell(new Phrase(String.valueOf(total), tf));
            totalVal.setBackgroundColor(BRAND_INK);
            totalVal.setBorderColor(BRAND_INK);
            totalVal.setPadding(5);
            sumTable.addCell(totalVal);

            doc.add(sumTable);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("EXPORT_FAILED", "No se pudo generar el PDF.");
        }
    }

    // ------------------------------------------------------------------------
    // Utilidades de marca para los reportes PDF (cabecera, marca de agua, pie).
    // Estas rutinas dan a los PDF generados en la web y descargados desde la app
    // móvil una identidad visual unificada con el logo corporativo.
    // ------------------------------------------------------------------------

    /** Cabecera de contenido: título del reporte y subtítulo (periodo / filtro). */
    private void addBrandHeading(Document doc, String reportTitle, String subtitle) {
        Font titleFont = new Font(Font.HELVETICA, 15, Font.BOLD, BRAND_INK);
        Paragraph t = new Paragraph(reportTitle, titleFont);
        t.setSpacingAfter(2);
        doc.add(t);
        if (subtitle != null && !subtitle.isBlank()) {
            Font subFont = new Font(Font.HELVETICA, 10, Font.NORMAL, BRAND_MUTED);
            Paragraph s = new Paragraph(subtitle, subFont);
            s.setSpacingAfter(10);
            doc.add(s);
        } else {
            doc.add(new Paragraph(" "));
        }
    }

    private void addHeaderRow(PdfPTable table, String[] headers) {
        Font hf = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, hf));
            cell.setBackgroundColor(BRAND_COLOR);
            cell.setBorderColor(BRAND_COLOR);
            cell.setPadding(5);
            table.addCell(cell);
        }
    }

    private void addBodyCell(PdfPTable table, String text, Font font, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setBorderColor(new Color(223, 230, 238));
        cell.setPadding(4);
        table.addCell(cell);
    }

    /**
     * Evento de página que dibuja, en cada hoja, la cabecera con el logo y el
     * nombre de la empresa, una marca de agua tenue centrada con el escudo y un
     * pie con la fecha de generación y el número de página.
     */
    private static final class BrandPageEvent extends PdfPageEventHelper {
        private Image watermark;
        private Image headerLogo;

        private void ensureImages() {
            if (LOGO_BYTES == null) return;
            try {
                if (headerLogo == null) {
                    headerLogo = Image.getInstance(LOGO_BYTES);
                }
                if (watermark == null) {
                    watermark = Image.getInstance(LOGO_BYTES);
                }
            } catch (Exception e) {
                log.warn("No se pudo preparar el logo del PDF: {}", e.getMessage());
            }
        }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            try {
                ensureImages();
                Rectangle page = doc.getPageSize();

                // --- Cabecera: logo + nombre de marca a la izquierda, franja inferior ---
                PdfContentByte cb = writer.getDirectContent();
                float top = page.getTop() - 24;
                if (headerLogo != null) {
                    headerLogo.scaleToFit(34, 34);
                    headerLogo.setAbsolutePosition(doc.leftMargin(), top - 30);
                    cb.addImage(headerLogo);
                }
                float textX = doc.leftMargin() + (headerLogo != null ? 42 : 0);
                cb.beginText();
                cb.setFontAndSize(com.lowagie.text.pdf.BaseFont.createFont(
                        com.lowagie.text.pdf.BaseFont.HELVETICA_BOLD,
                        com.lowagie.text.pdf.BaseFont.WINANSI, false), 14);
                cb.setColorFill(BRAND_COLOR);
                cb.setTextMatrix(textX, top - 12);
                cb.showText(BRAND_NAME);
                cb.setFontAndSize(com.lowagie.text.pdf.BaseFont.createFont(
                        com.lowagie.text.pdf.BaseFont.HELVETICA,
                        com.lowagie.text.pdf.BaseFont.WINANSI, false), 9);
                cb.setColorFill(BRAND_MUTED);
                cb.setTextMatrix(textX, top - 26);
                cb.showText(BRAND_TAGLINE);
                cb.endText();

                // Línea divisoria bajo la cabecera
                cb.setColorStroke(BRAND_COLOR);
                cb.setLineWidth(1.2f);
                cb.moveTo(doc.leftMargin(), top - 40);
                cb.lineTo(page.getRight() - doc.rightMargin(), top - 40);
                cb.stroke();

                // --- Marca de agua tenue (bajo el contenido) ---
                if (watermark != null) {
                    PdfContentByte under = writer.getDirectContentUnder();
                    PdfGState gs = new PdfGState();
                    gs.setFillOpacity(0.06f);
                    gs.setStrokeOpacity(0.06f);
                    under.saveState();
                    under.setGState(gs);
                    float wmW = page.getWidth() * 0.45f;
                    float wmH = wmW * watermark.getHeight() / watermark.getWidth();
                    Image wm = Image.getInstance(watermark);
                    wm.scaleAbsolute(wmW, wmH);
                    wm.setAbsolutePosition((page.getWidth() - wmW) / 2f, (page.getHeight() - wmH) / 2f);
                    under.addImage(wm);
                    under.restoreState();
                }

                // --- Pie: fecha de generación y número de página ---
                cb.beginText();
                cb.setFontAndSize(com.lowagie.text.pdf.BaseFont.createFont(
                        com.lowagie.text.pdf.BaseFont.HELVETICA,
                        com.lowagie.text.pdf.BaseFont.WINANSI, false), 8);
                cb.setColorFill(BRAND_MUTED);
                cb.setTextMatrix(doc.leftMargin(), page.getBottom() + 18);
                cb.showText(BRAND_NAME + " · " + BRAND_TAGLINE + "  —  Generado el "
                        + LocalDateTime.now().format(STAMP));
                cb.endText();
                cb.beginText();
                cb.setFontAndSize(com.lowagie.text.pdf.BaseFont.createFont(
                        com.lowagie.text.pdf.BaseFont.HELVETICA,
                        com.lowagie.text.pdf.BaseFont.WINANSI, false), 8);
                cb.setColorFill(BRAND_MUTED);
                String pg = "Página " + writer.getPageNumber();
                cb.showTextAligned(Element.ALIGN_RIGHT, pg,
                        page.getRight() - doc.rightMargin(), page.getBottom() + 18, 0);
                cb.endText();
            } catch (Exception e) {
                log.warn("No se pudo pintar la marca en el PDF: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------
    // Reporte diario del Kiosk (con conteo de platos al final)
    // ------------------------------------------------------------------------
    private static final String[] KIOSK_HEADERS =
            {"N°", "Hora", "Cédula", "Empleado", "Restaurante", "Comida",
             "Tipo", "Retira", "Descripción"};

    public byte[] kioskDailyCsv(String restaurantName, LocalDate date,
                                List<ConsumptionRow> rows, Map<String, Long> plateCounts) {
        StringBuilder sb = new StringBuilder();
        sb.append(csv(BRAND_NAME + " - " + BRAND_TAGLINE)).append("\n");
        sb.append("Reporte Diario - ").append(restaurantName)
          .append(" - Fecha: ").append(date.format(DATE_FMT)).append("\n\n");
        sb.append(String.join(";", KIOSK_HEADERS)).append("\n");
        int n = 1;
        for (ConsumptionRow r : rows) {
            sb.append(n++).append(';')
              .append(r.consumedAt() != null ? r.consumedAt().format(DT) : "").append(';')
              .append(csv(r.identityCard())).append(';')
              .append(csv(r.employeeName())).append(';')
              .append(csv(restaurantName)).append(';')
              .append(csv(r.mealName())).append(';')
              .append(methodLabel(r.method())).append(';')
              .append(csv(r.proxyEmployeeName())).append(';')
              .append(csv(buildDescription(r))).append('\n');
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
            CellStyle headerStyle = excelColumnHeaderStyle(wb);

            int headerRowIdx = writeExcelHeader(wb, sheet, KIOSK_HEADERS.length,
                    "Reporte Diario · " + restaurantName + " · " + date.format(DATE_FMT));

            Row header = sheet.createRow(headerRowIdx);
            for (int i = 0; i < KIOSK_HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(KIOSK_HEADERS[i]);
                c.setCellStyle(headerStyle);
            }
            int rn = headerRowIdx + 1;
            int n = 1;
            XSSFCellStyle kManualStyle   = (XSSFCellStyle) wb.createCellStyle();
            kManualStyle.setFillForegroundColor(new XSSFColor(MANUAL_BG, null));
            kManualStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            XSSFCellStyle kExternalStyle = (XSSFCellStyle) wb.createCellStyle();
            kExternalStyle.setFillForegroundColor(new XSSFColor(EXTERNAL_BG, null));
            kExternalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (ConsumptionRow r : rows) {
                Row row = sheet.createRow(rn++);
                row.createCell(0).setCellValue(n++);
                row.createCell(1).setCellValue(r.consumedAt() != null ? r.consumedAt().format(DT) : "");
                row.createCell(2).setCellValue(safe(r.identityCard()));
                row.createCell(3).setCellValue(safe(r.employeeName()));
                row.createCell(4).setCellValue(safe(restaurantName));
                row.createCell(5).setCellValue(safe(r.mealName()));
                row.createCell(6).setCellValue(methodLabel(r.method()));
                row.createCell(7).setCellValue(safe(r.proxyEmployeeName()));
                row.createCell(8).setCellValue(safe(buildDescription(r)));
                if ("MANUAL".equals(r.method()) || "EXTERNAL".equals(r.method())) {
                    XSSFCellStyle style = "MANUAL".equals(r.method()) ? kManualStyle : kExternalStyle;
                    for (int i = 0; i < KIOSK_HEADERS.length; i++) {
                        Cell c = row.getCell(i);
                        if (c == null) c = row.createCell(i);
                        c.setCellStyle(style);
                    }
                }
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
            Document doc = new Document(PageSize.A4, 28, 28, 92, 40);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new BrandPageEvent());
            doc.open();

            addBrandHeading(doc, "Reporte Diario · " + restaurantName,
                    "Fecha: " + date.format(DATE_FMT));

            PdfPTable table = new PdfPTable(KIOSK_HEADERS.length);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{5f, 12f, 10f, 18f, 15f, 10f, 10f, 13f, 17f});
            table.setHeaderRows(1);
            addHeaderRow(table, KIOSK_HEADERS);
            Font cf = new Font(Font.HELVETICA, 8);
            boolean zebra = false;
            int n = 1;
            for (ConsumptionRow r : rows) {
                Color bg = rowColor(r.method(), (zebra = !zebra) ? FINGERPRINT_BG : Color.WHITE);
                addBodyCell(table, String.valueOf(n++), cf, bg);
                addBodyCell(table, r.consumedAt() != null ? r.consumedAt().format(DT) : "", cf, bg);
                addBodyCell(table, safe(r.identityCard()), cf, bg);
                addBodyCell(table, safe(r.employeeName()), cf, bg);
                addBodyCell(table, safe(restaurantName), cf, bg);
                addBodyCell(table, safe(r.mealName()), cf, bg);
                addBodyCell(table, methodLabel(r.method()), cf, bg);
                addBodyCell(table, safe(r.proxyEmployeeName()), cf, bg);
                addBodyCell(table, safe(buildDescription(r)), cf, bg);
            }
            doc.add(table);
            doc.add(new Paragraph(" "));

            Font sumTitleFont = new Font(Font.HELVETICA, 11, Font.BOLD, BRAND_INK);
            doc.add(new Paragraph("RESUMEN DE PLATOS", sumTitleFont));
            doc.add(new Paragraph(" "));

            PdfPTable sumTable = new PdfPTable(2);
            sumTable.setWidthPercentage(40);
            sumTable.setHorizontalAlignment(PdfPTable.ALIGN_LEFT);
            addHeaderRow(sumTable, new String[]{"Tipo de Comida", "Cantidad"});

            Font scf = new Font(Font.HELVETICA, 9);
            long total = 0;
            boolean zebra2 = false;
            for (Map.Entry<String, Long> entry : plateCounts.entrySet()) {
                Color bg = (zebra2 = !zebra2) ? new Color(244, 247, 251) : Color.WHITE;
                addBodyCell(sumTable, entry.getKey(), scf, bg);
                addBodyCell(sumTable, String.valueOf(entry.getValue()), scf, bg);
                total += entry.getValue();
            }
            Font tf = new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE);
            PdfPCell totalLabel = new PdfPCell(new Phrase("TOTAL", tf));
            totalLabel.setBackgroundColor(BRAND_INK);
            totalLabel.setBorderColor(BRAND_INK);
            totalLabel.setPadding(5);
            sumTable.addCell(totalLabel);
            PdfPCell totalVal = new PdfPCell(new Phrase(String.valueOf(total), tf));
            totalVal.setBackgroundColor(BRAND_INK);
            totalVal.setBorderColor(BRAND_INK);
            totalVal.setPadding(5);
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
            sb.append(csv(r.identityCard())).append(';')
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
            CellStyle headerStyle = excelColumnHeaderStyle(wb);

            int headerRowIdx = writeExcelHeader(wb, sheet, EMP_HEADERS.length,
                    "Padrón de Empleados");

            Row header = sheet.createRow(headerRowIdx);
            for (int i = 0; i < EMP_HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(EMP_HEADERS[i]);
                c.setCellStyle(headerStyle);
            }
            int rn = headerRowIdx + 1;
            for (EmployeeResponse r : rows) {
                Row row = sheet.createRow(rn++);
                row.createCell(0).setCellValue(safe(r.identityCard()));
                row.createCell(1).setCellValue(safe(r.fullName()));
                row.createCell(2).setCellValue(r.allowsLunch() ? "Sí" : "No");
                row.createCell(3).setCellValue(r.effectiveSnack() ? "Sí" : "No");
                row.createCell(4).setCellValue(safe(r.status()));
                row.createCell(5).setCellValue(r.fingerprintCount());
                row.createCell(6).setCellValue(safe(r.observation()));
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
        boolean formulaLike = "=+-@".indexOf(s.charAt(0)) >= 0;
        // Neutraliza fórmulas anteponiendo un apóstrofo FUERA de las comillas: Excel/Sheets
        // evalúan un campo que empieza por =/+/-/@ como fórmula sin importar que esté citado
        // entre comillas, así que citar solo no evita la inyección (CSV injection).
        if (formulaLike) s = "'" + s;
        boolean needsQuoting = formulaLike || s.indexOf(';') >= 0 || s.indexOf(',') >= 0
                || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (needsQuoting) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private String safe(String v) {
        return v == null ? "" : v.replace(";", ",");
    }

    /** Color de fondo del cuerpo según el método de registro. */
    private Color rowColor(String method, Color fallback) {
        if (method == null) return fallback;
        return switch (method) {
            case "MANUAL"   -> MANUAL_BG;
            case "EXTERNAL" -> EXTERNAL_BG;
            default         -> fallback;
        };
    }

    private String methodLabel(String method) {
        if (method == null) return "Huella";
        return switch (method) {
            case "MANUAL"   -> "Manual";
            case "EXTERNAL" -> "Externo";
            default         -> "Huella";
        };
    }

    /** Descripción compuesta para los reportes: prioriza observation si existe;
     *  si es MANUAL y no tiene observation (la data histórica), muestra
     *  "X retira de Y" cuando proxyEmployeeName está disponible. */
    private String buildDescription(ConsumptionRow r) {
        String obs = r.observation();
        if (obs != null && !obs.isBlank()) return obs;
        return "";
    }

    /**
     * Agrupa los consumos por tipo de comida y cuenta cuántos hay de cada uno.
     * Se usa tanto en los reportes del Kiosk como en los reportes generales.
     */
    private Map<String, Long> buildPlateCounts(List<ConsumptionRow> rows) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (ConsumptionRow r : rows) {
            String meal = r.mealName() != null ? r.mealName() : "Sin tipo";
            counts.merge(meal, 1L, Long::sum);
        }
        return counts;
    }
}
