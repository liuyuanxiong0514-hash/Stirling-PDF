package stirling.software.SPDF.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ExcelDataQualityService {

    private static final long MAX_UPLOAD_BYTES = 50L * 1024L * 1024L;
    private static final int PREVIEW_ROW_LIMIT = 20;
    private static final String XLS_EXTENSION = ".xls";
    private static final String XLSX_EXTENSION = ".xlsx";
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("^(\\d{15}|\\d{17}[0-9Xx])$");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    private final Path uploadDirectory =
            Path.of(System.getProperty("java.io.tmpdir"), "stirling-pdf", "excel-clean", "uploads");
    private final Map<String, UploadedCleanFile> uploadedFiles = new ConcurrentHashMap<>();

    public CleanAnalyzeResponse analyze(MultipartFile file, String sheetName) throws IOException {
        validateUpload(file);
        Files.createDirectories(uploadDirectory);
        String token = UUID.randomUUID().toString();
        String extension = fileExtension(file.getOriginalFilename());
        Path uploadPath = uploadDirectory.resolve(token + extension).normalize();
        if (!uploadPath.startsWith(uploadDirectory)) {
            throw new IOException("Invalid Excel clean upload path");
        }
        file.transferTo(uploadPath);
        String displayName =
                file.getOriginalFilename() == null ? token + extension : file.getOriginalFilename();
        uploadedFiles.put(token, new UploadedCleanFile(uploadPath, displayName));
        return analyzeExisting(token, sheetName);
    }

    public CleanAnalyzeResponse analyzeExisting(String fileToken, String sheetName)
            throws IOException {
        UploadedCleanFile uploadedFile = getUploadedFile(fileToken);
        try (Workbook workbook = openWorkbook(uploadedFile.path())) {
            Sheet sheet = getSheet(workbook, sheetName);
            List<String> headers = readHeaders(sheet);
            List<Map<String, String>> rows = readRows(sheet, headers);
            CleanIssues issues = detectIssues(headers, rows);
            return new CleanAnalyzeResponse(
                    true,
                    "\u6570\u636e\u8d28\u91cf\u68c0\u6d4b\u5b8c\u6210",
                    fileToken,
                    sheet.getSheetName(),
                    rows.size(),
                    headers.size(),
                    headers,
                    rows.stream()
                            .limit(PREVIEW_ROW_LIMIT)
                            .map(
                                    row ->
                                            headers.stream()
                                                    .map(header -> row.getOrDefault(header, ""))
                                                    .toList())
                            .toList(),
                    issues);
        }
    }

    public UploadedCleanFile getUploadedFile(String fileToken) {
        UploadedCleanFile uploadedFile = uploadedFiles.get(fileToken);
        if (uploadedFile == null || !Files.isRegularFile(uploadedFile.path())) {
            throw new IllegalArgumentException(
                    "\u6587\u4ef6\u5df2\u8fc7\u671f\uff0c\u8bf7\u91cd\u65b0\u4e0a\u4f20");
        }
        return uploadedFile;
    }

    public Workbook openWorkbook(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return WorkbookFactory.create(inputStream);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "\u0045\u0078\u0063\u0065\u006c\u6587\u4ef6\u89e3\u6790\u5931\u8d25");
        }
    }

    public Sheet getSheet(Workbook workbook, String sheetName) {
        if (sheetName != null && !sheetName.isBlank()) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet != null) {
                return sheet;
            }
        }
        if (workbook.getNumberOfSheets() == 0) {
            throw new IllegalArgumentException(
                    "\u0045\u0078\u0063\u0065\u006c\u4e2d\u6ca1\u6709\u53ef\u7528\u7684 sheet");
        }
        return workbook.getSheetAt(0);
    }

    public List<String> readHeaders(Sheet sheet) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            return List.of();
        }
        DataFormatter formatter = new DataFormatter();
        List<String> headers = new ArrayList<>();
        short lastCellNum = headerRow.getLastCellNum();
        for (int index = 0; index < lastCellNum; index++) {
            String header = formatter.formatCellValue(headerRow.getCell(index)).trim();
            if (header.isBlank()) {
                header = "Column" + (index + 1);
            }
            headers.add(toUniqueHeader(header, headers));
        }
        return headers;
    }

    public List<Map<String, String>> readRows(Sheet sheet, List<String> headers) {
        List<Map<String, String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        for (int rowIndex = sheet.getFirstRowNum() + 1;
                rowIndex <= sheet.getLastRowNum();
                rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            Map<String, String> data = new LinkedHashMap<>();
            for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                data.put(
                        headers.get(columnIndex),
                        row == null || row.getCell(columnIndex) == null
                                ? ""
                                : formatter.formatCellValue(row.getCell(columnIndex)).trim());
            }
            rows.add(data);
        }
        return rows;
    }

    private CleanIssues detectIssues(List<String> headers, List<Map<String, String>> rows) {
        int emptyRowCount = 0;
        int blankCellCount = 0;
        int trimIssueCount = 0;
        Set<String> seenRows = new LinkedHashSet<>();
        int duplicateRowCount = 0;

        for (Map<String, String> row : rows) {
            boolean empty =
                    headers.stream().allMatch(header -> row.getOrDefault(header, "").isBlank());
            if (empty) {
                emptyRowCount++;
            }
            List<String> rowValues =
                    headers.stream().map(header -> row.getOrDefault(header, "")).toList();
            if (!empty && !seenRows.add(rowValues.toString())) {
                duplicateRowCount++;
            }
            for (String header : headers) {
                String value = row.getOrDefault(header, "");
                if (value.isBlank()) {
                    blankCellCount++;
                }
                if (!value.equals(value.trim())) {
                    trimIssueCount++;
                }
            }
        }

        List<ColumnQuality> columns = new ArrayList<>();
        for (String header : headers) {
            List<String> values =
                    rows.stream()
                            .map(row -> row.getOrDefault(header, ""))
                            .filter(value -> !value.isBlank())
                            .toList();
            String detectedType = detectType(values);
            columns.add(
                    new ColumnQuality(header, detectedType, columnIssues(detectedType, values)));
        }
        return new CleanIssues(
                emptyRowCount, duplicateRowCount, blankCellCount, trimIssueCount, columns);
    }

    private List<ColumnIssue> columnIssues(String detectedType, List<String> values) {
        List<ColumnIssue> issues = new ArrayList<>();
        if (values.isEmpty()) {
            return issues;
        }
        if ("number".equals(detectedType)) {
            List<String> examples =
                    values.stream()
                            .filter(value -> !isPlainNumber(value))
                            .distinct()
                            .limit(5)
                            .toList();
            if (!examples.isEmpty()) {
                issues.add(
                        new ColumnIssue(
                                "mixed_number_format", examples.size(), examples, List.of()));
            }
        }
        if ("date".equals(detectedType)) {
            Set<String> normalizedFormats = new LinkedHashSet<>();
            for (String value : values) {
                normalizedFormats.add(dateFormatKey(value));
            }
            if (normalizedFormats.size() > 1) {
                issues.add(
                        new ColumnIssue(
                                "mixed_date_format",
                                values.size(),
                                values.stream().distinct().limit(5).toList(),
                                List.of()));
            }
        }
        if ("text".equals(detectedType)) {
            List<List<String>> groups = similarGroups(values);
            if (!groups.isEmpty()) {
                issues.add(new ColumnIssue("similar_values", groups.size(), List.of(), groups));
            }
        }
        return issues;
    }

    private String detectType(List<String> values) {
        if (values.isEmpty()) {
            return "text";
        }
        if (ratio(values, this::parseNumber) >= 0.8) {
            return "number";
        }
        if (ratio(values, this::parseDate) >= 0.8) {
            return "date";
        }
        if (ratio(values, value -> PHONE_PATTERN.matcher(removeSpaces(value)).matches()) >= 0.8) {
            return "phone";
        }
        if (ratio(values, value -> ID_CARD_PATTERN.matcher(removeSpaces(value)).matches()) >= 0.8) {
            return "id_card";
        }
        if (ratio(values, value -> EMAIL_PATTERN.matcher(value.trim()).matches()) >= 0.8) {
            return "email";
        }
        return "text";
    }

    private double ratio(List<String> values, java.util.function.Predicate<String> predicate) {
        long matched = values.stream().filter(predicate).count();
        return (double) matched / values.size();
    }

    private boolean parseNumber(String value) {
        try {
            new BigDecimal(normalizeNumberString(value));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean parseDate(String value) {
        return parseLocalDate(value) != null;
    }

    static String normalizeNumberString(String value) {
        return value == null
                ? ""
                : value.trim()
                        .replace(",", "")
                        .replace(" ", "")
                        .replace("$", "")
                        .replace("\u20ac", "")
                        .replace("\u00a3", "")
                        .replace("\u00a5", "")
                        .replace("\uffe5", "")
                        .replace("\u5143", "");
    }

    static LocalDate parseLocalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        List<DateTimeFormatter> formatters =
                List.of(
                        DateTimeFormatter.ISO_LOCAL_DATE,
                        DateTimeFormatter.ofPattern("yyyy/M/d"),
                        DateTimeFormatter.ofPattern("M/d/yy"),
                        DateTimeFormatter.ofPattern("yyyy.M.d"),
                        DateTimeFormatter.ofPattern("yyyyMMdd"),
                        DateTimeFormatter.ofPattern("yyyy\u5e74M\u6708d\u65e5"));
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // Try next format.
            }
        }
        try {
            double excelDate = Double.parseDouble(trimmed);
            return DateUtil.getJavaDate(excelDate)
                    .toInstant()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isPlainNumber(String value) {
        return value != null && value.trim().matches("^-?\\d+(\\.\\d+)?$");
    }

    private String dateFormatKey(String value) {
        if (value.contains("/")) {
            return "slash";
        }
        if (value.contains(".")) {
            return "dot";
        }
        if (value.contains("\u5e74")) {
            return "cn";
        }
        return "dash";
    }

    private List<List<String>> similarGroups(List<String> values) {
        Map<String, Set<String>> groups = new HashMap<>();
        for (String value : values) {
            String key = normalizeTextKey(value);
            groups.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(value);
        }
        return groups.values().stream()
                .filter(group -> group.size() > 1)
                .map(group -> (List<String>) new ArrayList<>(group))
                .toList();
    }

    private String normalizeTextKey(String value) {
        return normalizeFullWidth(value).trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    static String normalizeFullWidth(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (char ch : value.toCharArray()) {
            if (ch == 12288) {
                builder.append(' ');
            } else if (ch >= 65281 && ch <= 65374) {
                builder.append((char) (ch - 65248));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    static String removeSpaces(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private String toUniqueHeader(String header, List<String> existingHeaders) {
        String uniqueHeader = header;
        int suffix = 2;
        while (existingHeaders.contains(uniqueHeader)) {
            uniqueHeader = header + "_" + suffix;
            suffix++;
        }
        return uniqueHeader;
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("\u8bf7\u4e0a\u4f20 Excel \u6587\u4ef6");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "\u0045\u0078\u0063\u0065\u006c\u6587\u4ef6\u8fc7\u5927\uff0c\u8bf7\u4e0a\u4f20 50MB \u4ee5\u5185\u7684\u6587\u4ef6");
        }
        String extension = fileExtension(file.getOriginalFilename());
        if (!XLSX_EXTENSION.equals(extension) && !XLS_EXTENSION.equals(extension)) {
            throw new IllegalArgumentException("\u8bf7\u4e0a\u4f20 .xlsx \u6216 .xls \u6587\u4ef6");
        }
    }

    private String fileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        int index = lowerName.lastIndexOf('.');
        return index < 0 ? "" : lowerName.substring(index);
    }

    public record UploadedCleanFile(Path path, String displayName) {}

    public record CleanAnalyzeResponse(
            boolean success,
            String message,
            String fileToken,
            String sheetName,
            int rowCount,
            int columnCount,
            List<String> headers,
            List<List<String>> previewData,
            CleanIssues issues) {}

    public record CleanIssues(
            int emptyRowCount,
            int duplicateRowCount,
            int blankCellCount,
            int trimIssueCount,
            List<ColumnQuality> columns) {}

    public record ColumnQuality(String column, String detectedType, List<ColumnIssue> issues) {}

    public record ColumnIssue(
            String type, int count, List<String> examples, List<List<String>> groups) {}
}
