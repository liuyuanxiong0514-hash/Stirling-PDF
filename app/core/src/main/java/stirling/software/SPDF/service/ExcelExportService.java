package stirling.software.SPDF.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class ExcelExportService {

    private static final String DISPLAY_FILE_NAME = "table_extract_result.xlsx";
    private static final String XLSX_EXTENSION = ".xlsx";

    private final Path exportDirectory;

    public ExcelExportService() {
        this.exportDirectory =
                Path.of(System.getProperty("java.io.tmpdir"), "stirling-pdf", "excel-exports");
    }

    public ExportedExcel exportTable(List<List<String>> tableData) throws IOException {
        Files.createDirectories(exportDirectory);

        String storageFileName = UUID.randomUUID() + "_" + DISPLAY_FILE_NAME;
        Path outputPath = exportDirectory.resolve(storageFileName).normalize();
        if (!outputPath.startsWith(exportDirectory)) {
            throw new IOException("Invalid Excel export path");
        }

        try (Workbook workbook = new XSSFWorkbook();
                OutputStream outputStream = Files.newOutputStream(outputPath)) {
            Sheet sheet = workbook.createSheet("Table");
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle bodyStyle = createBodyStyle(workbook);

            for (int rowIndex = 0; rowIndex < tableData.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex);
                List<String> rowData = tableData.get(rowIndex);
                for (int cellIndex = 0; cellIndex < rowData.size(); cellIndex++) {
                    Cell cell = row.createCell(cellIndex);
                    cell.setCellValue(rowData.get(cellIndex));
                    cell.setCellStyle(rowIndex == 0 ? headerStyle : bodyStyle);
                }
            }

            int columnCount = tableData.stream().mapToInt(List::size).max().orElse(0);
            if (!tableData.isEmpty() && columnCount > 0) {
                sheet.createFreezePane(0, 1);
                sheet.setAutoFilter(
                        new CellRangeAddress(0, tableData.size() - 1, 0, columnCount - 1));
                for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                    sheet.autoSizeColumn(columnIndex);
                    sheet.setColumnWidth(
                            columnIndex,
                            Math.min(
                                    Math.max(sheet.getColumnWidth(columnIndex) + 512, 3000),
                                    12000));
                }
            }

            workbook.write(outputStream);
        }

        return new ExportedExcel(storageFileName, DISPLAY_FILE_NAME);
    }

    public Resource getExportedFile(String storageFileName) throws IOException {
        Path filePath = resolveExportedFile(storageFileName);
        if (!Files.isRegularFile(filePath)) {
            return null;
        }
        return new FileSystemResource(filePath);
    }

    private Path resolveExportedFile(String storageFileName) throws IOException {
        if (storageFileName == null
                || storageFileName.isBlank()
                || storageFileName.contains("/")
                || storageFileName.contains("\\")
                || !storageFileName.endsWith(XLSX_EXTENSION)) {
            throw new IOException("Invalid Excel export file name");
        }

        Path filePath = exportDirectory.resolve(storageFileName).normalize();
        if (!filePath.startsWith(exportDirectory)) {
            throw new IOException("Invalid Excel export path");
        }
        return filePath;
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = createBaseStyle(workbook);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle createBodyStyle(Workbook workbook) {
        return createBaseStyle(workbook);
    }

    private CellStyle createBaseStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }

    public record ExportedExcel(String storageFileName, String displayFileName) {}
}
