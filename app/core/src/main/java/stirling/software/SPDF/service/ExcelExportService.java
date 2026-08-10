package stirling.software.SPDF.service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
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
        return exportTable(tableData, DISPLAY_FILE_NAME);
    }

    public ExportedExcel exportTable(List<List<String>> tableData, String displayFileName)
            throws IOException {
        return exportSheets(List.of(new ExcelSheet("Sheet1", tableData)), displayFileName);
    }

    public ExportedExcel exportSheets(List<ExcelSheet> sheets, String displayFileName)
            throws IOException {
        Files.createDirectories(exportDirectory);

        String safeDisplayFileName = toSafeXlsxFileName(displayFileName);
        String storageFileName = UUID.randomUUID() + "_" + safeDisplayFileName;
        Path outputPath = exportDirectory.resolve(storageFileName).normalize();
        if (!outputPath.startsWith(exportDirectory)) {
            throw new IOException("Invalid Excel export path");
        }

        try (Workbook workbook = new XSSFWorkbook();
                OutputStream outputStream = Files.newOutputStream(outputPath)) {
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle bodyStyle = createBodyStyle(workbook);

            List<ExcelSheet> nonEmptySheets =
                    sheets.stream().filter(sheet -> !sheet.rows().isEmpty()).toList();
            if (nonEmptySheets.isEmpty()) {
                nonEmptySheets = List.of(new ExcelSheet("Sheet1", List.of(List.of())));
            }

            List<String> usedSheetNames = new ArrayList<>();
            for (ExcelSheet excelSheet : nonEmptySheets) {
                writeSheet(workbook, excelSheet, headerStyle, bodyStyle, usedSheetNames);
            }

            workbook.write(outputStream);
        }

        return new ExportedExcel(storageFileName, safeDisplayFileName);
    }

    public ExportedExcel exportTypedSheets(List<TypedExcelSheet> sheets, String displayFileName)
            throws IOException {
        Files.createDirectories(exportDirectory);

        String safeDisplayFileName = toSafeXlsxFileName(displayFileName);
        String storageFileName = UUID.randomUUID() + "_" + safeDisplayFileName;
        Path outputPath = exportDirectory.resolve(storageFileName).normalize();
        if (!outputPath.startsWith(exportDirectory)) {
            throw new IOException("Invalid Excel export path");
        }

        try (Workbook workbook = new XSSFWorkbook();
                OutputStream outputStream = Files.newOutputStream(outputPath)) {
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle bodyStyle = createBodyStyle(workbook);
            CellStyle dateStyle = createBodyStyle(workbook);
            DataFormat dataFormat = workbook.createDataFormat();
            dateStyle.setDataFormat(dataFormat.getFormat("yyyy-mm-dd"));

            List<TypedExcelSheet> nonEmptySheets =
                    sheets.stream().filter(sheet -> !sheet.rows().isEmpty()).toList();
            if (nonEmptySheets.isEmpty()) {
                nonEmptySheets = List.of(new TypedExcelSheet("Sheet1", List.of(List.of())));
            }

            List<String> usedSheetNames = new ArrayList<>();
            for (TypedExcelSheet excelSheet : nonEmptySheets) {
                writeTypedSheet(
                        workbook, excelSheet, headerStyle, bodyStyle, dateStyle, usedSheetNames);
            }

            workbook.write(outputStream);
        }

        return new ExportedExcel(storageFileName, safeDisplayFileName);
    }

    public Resource getExportedFile(String storageFileName) throws IOException {
        Path filePath = resolveExportedFile(storageFileName);
        if (!Files.isRegularFile(filePath)) {
            return null;
        }
        return new FileSystemResource(filePath);
    }

    public String getDisplayFileName(String storageFileName) {
        if (storageFileName == null || storageFileName.isBlank()) {
            return DISPLAY_FILE_NAME;
        }

        int separatorIndex = storageFileName.indexOf('_');
        if (separatorIndex < 0 || separatorIndex == storageFileName.length() - 1) {
            return DISPLAY_FILE_NAME;
        }
        return storageFileName.substring(separatorIndex + 1);
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

    private void writeSheet(
            Workbook workbook,
            ExcelSheet excelSheet,
            CellStyle headerStyle,
            CellStyle bodyStyle,
            List<String> usedSheetNames) {
        Sheet sheet = workbook.createSheet(toUniqueSheetName(excelSheet.name(), usedSheetNames));
        List<List<String>> tableData = excelSheet.rows();

        for (int rowIndex = 0; rowIndex < tableData.size(); rowIndex++) {
            Row row = sheet.createRow(rowIndex);
            List<String> rowData = tableData.get(rowIndex);
            boolean separatorRow = rowData.stream().allMatch(String::isBlank);
            for (int cellIndex = 0; cellIndex < rowData.size(); cellIndex++) {
                Cell cell = row.createCell(cellIndex);
                cell.setCellValue(rowData.get(cellIndex));
                if (!separatorRow) {
                    cell.setCellStyle(rowIndex == 0 ? headerStyle : bodyStyle);
                }
            }
        }

        int columnCount = tableData.stream().mapToInt(List::size).max().orElse(0);
        if (!tableData.isEmpty() && columnCount > 0) {
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, tableData.size() - 1, 0, columnCount - 1));
            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                sheet.autoSizeColumn(columnIndex);
                sheet.setColumnWidth(
                        columnIndex,
                        Math.min(Math.max(sheet.getColumnWidth(columnIndex) + 512, 3000), 12000));
            }
        }
    }

    private void writeTypedSheet(
            Workbook workbook,
            TypedExcelSheet excelSheet,
            CellStyle headerStyle,
            CellStyle bodyStyle,
            CellStyle dateStyle,
            List<String> usedSheetNames) {
        Sheet sheet = workbook.createSheet(toUniqueSheetName(excelSheet.name(), usedSheetNames));
        List<List<Object>> tableData = excelSheet.rows();

        for (int rowIndex = 0; rowIndex < tableData.size(); rowIndex++) {
            Row row = sheet.createRow(rowIndex);
            List<Object> rowData = tableData.get(rowIndex);
            boolean separatorRow =
                    rowData.stream()
                            .allMatch(value -> value == null || String.valueOf(value).isBlank());
            for (int cellIndex = 0; cellIndex < rowData.size(); cellIndex++) {
                Cell cell = row.createCell(cellIndex);
                Object value = rowData.get(cellIndex);
                if (value instanceof BigDecimal decimal) {
                    cell.setCellValue(decimal.doubleValue());
                    cell.setCellStyle(bodyStyle);
                } else if (value instanceof Number number) {
                    cell.setCellValue(number.doubleValue());
                    cell.setCellStyle(bodyStyle);
                } else if (value instanceof LocalDate date) {
                    cell.setCellValue(
                            Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));
                    cell.setCellStyle(dateStyle);
                } else {
                    cell.setCellValue(value == null ? "" : String.valueOf(value));
                    if (!separatorRow) {
                        cell.setCellStyle(rowIndex == 0 ? headerStyle : bodyStyle);
                    }
                }
            }
        }

        int columnCount = tableData.stream().mapToInt(List::size).max().orElse(0);
        if (!tableData.isEmpty() && columnCount > 0) {
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, tableData.size() - 1, 0, columnCount - 1));
            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                sheet.autoSizeColumn(columnIndex);
                sheet.setColumnWidth(
                        columnIndex,
                        Math.min(Math.max(sheet.getColumnWidth(columnIndex) + 512, 3000), 12000));
            }
        }
    }

    private String toUniqueSheetName(String requestedName, List<String> usedSheetNames) {
        String baseName =
                WorkbookUtil.createSafeSheetName(
                        requestedName == null || requestedName.isBlank()
                                ? "Sheet1"
                                : requestedName);
        String uniqueName = baseName;
        int index = 1;
        while (usedSheetNames.contains(uniqueName)) {
            String suffix = " (" + index + ")";
            uniqueName =
                    baseName.length() + suffix.length() > 31
                            ? baseName.substring(0, 31 - suffix.length()) + suffix
                            : baseName + suffix;
            index++;
        }
        usedSheetNames.add(uniqueName);
        return uniqueName;
    }

    private String toSafeXlsxFileName(String displayFileName) {
        String safeName =
                displayFileName == null || displayFileName.isBlank()
                        ? DISPLAY_FILE_NAME
                        : displayFileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!safeName.toLowerCase(java.util.Locale.ROOT).endsWith(XLSX_EXTENSION)) {
            safeName += XLSX_EXTENSION;
        }
        return safeName;
    }

    public record ExcelSheet(String name, List<List<String>> rows) {}

    public record TypedExcelSheet(String name, List<List<Object>> rows) {}

    public record ExportedExcel(String storageFileName, String displayFileName) {}
}
