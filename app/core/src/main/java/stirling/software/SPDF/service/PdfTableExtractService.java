package stirling.software.SPDF.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.pdf.parser.PdfModels.RawPage;
import stirling.software.SPDF.pdf.parser.PdfModels.TableFragment;
import stirling.software.SPDF.pdf.parser.TabulaTableParser;
import stirling.software.SPDF.service.ExcelExportService.ExcelSheet;

@Service
@RequiredArgsConstructor
public class PdfTableExtractService {

    private static final int MIN_TABLE_ROWS = 2;
    private static final int MIN_TABLE_COLUMNS = 2;

    private final TabulaTableParser tabulaTableParser;

    public PdfTableExtractResult extractTables(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            if (!hasExtractableText(document)) {
                return PdfTableExtractResult.failure(
                        "\u672a\u8bc6\u522b\u5230\u53ef\u63d0\u53d6\u7684\u6587\u5b57\u578b\u8868\u683c\u3002\u5982\u679c\u662f\u626b\u63cf\u4ef6\u6216\u622a\u56fe\uff0c\u8bf7\u5728\u4e0b\u4e00\u9636\u6bb5\u63a5\u5165OCR\u8bc6\u522b\u3002");
            }

            List<ExcelSheet> sheets = new ArrayList<>();
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                int pageNumber = pageIndex + 1;
                List<TableFragment> fragments =
                        tabulaTableParser.parse(
                                document, new RawPage(pageNumber, 0f, 0f, List.of()));

                if (fragments.isEmpty()) {
                    fragments =
                            tabulaTableParser.parseStream(
                                    document, new RawPage(pageNumber, 0f, 0f, List.of()));
                }

                List<List<String>> pageRows = toPageRows(fragments);
                if (!pageRows.isEmpty()) {
                    sheets.add(new ExcelSheet("Page" + pageNumber, pageRows));
                }
            }

            if (sheets.isEmpty()) {
                return PdfTableExtractResult.failure(
                        "\u672a\u8bc6\u522b\u5230\u53ef\u63d0\u53d6\u7684\u6587\u5b57\u578b\u8868\u683c\u3002\u5982\u679c\u662f\u626b\u63cf\u4ef6\u6216\u622a\u56fe\uff0c\u8bf7\u5728\u4e0b\u4e00\u9636\u6bb5\u63a5\u5165OCR\u8bc6\u522b\u3002");
            }

            return PdfTableExtractResult.success(sheets);
        }
    }

    private boolean hasExtractableText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        return !stripper.getText(document).trim().isEmpty();
    }

    private List<List<String>> toPageRows(List<TableFragment> fragments) {
        List<List<String>> pageRows = new ArrayList<>();
        for (TableFragment fragment : fragments) {
            List<List<String>> rows = normaliseRows(fragment.rawRows());
            if (!isUsableTable(rows)) {
                continue;
            }

            if (!pageRows.isEmpty()) {
                pageRows.add(List.of());
            }
            pageRows.addAll(rows);
        }
        return pageRows;
    }

    private List<List<String>> normaliseRows(List<List<String>> rows) {
        int columnCount =
                rows.stream()
                        .map(this::trimEmptyTrailingCells)
                        .mapToInt(List::size)
                        .max()
                        .orElse(0);
        if (columnCount == 0) {
            return List.of();
        }

        List<List<String>> normalisedRows = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> trimmedRow = trimEmptyTrailingCells(row);
            if (trimmedRow.stream().allMatch(String::isBlank)) {
                continue;
            }

            List<String> normalisedRow = new ArrayList<>(trimmedRow);
            while (normalisedRow.size() < columnCount) {
                normalisedRow.add("");
            }
            normalisedRows.add(List.copyOf(normalisedRow));
        }
        return List.copyOf(normalisedRows);
    }

    private List<String> trimEmptyTrailingCells(List<String> row) {
        List<String> cells = row.stream().map(this::normaliseCell).toList();
        int lastNonEmpty = cells.size() - 1;
        while (lastNonEmpty >= 0 && cells.get(lastNonEmpty).isBlank()) {
            lastNonEmpty--;
        }
        if (lastNonEmpty < 0) {
            return List.of();
        }
        return List.copyOf(cells.subList(0, lastNonEmpty + 1));
    }

    private String normaliseCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private boolean isUsableTable(List<List<String>> rows) {
        if (rows.size() < MIN_TABLE_ROWS) {
            return false;
        }

        int maxColumns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (maxColumns < MIN_TABLE_COLUMNS) {
            return false;
        }

        long tableLikeRows =
                rows.stream()
                        .filter(row -> row.size() >= MIN_TABLE_COLUMNS)
                        .filter(row -> row.stream().filter(cell -> !cell.isBlank()).count() >= 2)
                        .count();
        return tableLikeRows >= MIN_TABLE_ROWS;
    }

    public record PdfTableExtractResult(boolean success, String message, List<ExcelSheet> sheets) {

        public static PdfTableExtractResult success(List<ExcelSheet> sheets) {
            return new PdfTableExtractResult(
                    true, "\u0050\u0044\u0046\u8868\u683c\u63d0\u53d6\u5b8c\u6210", sheets);
        }

        public static PdfTableExtractResult failure(String message) {
            return new PdfTableExtractResult(false, message, List.of());
        }
    }
}
