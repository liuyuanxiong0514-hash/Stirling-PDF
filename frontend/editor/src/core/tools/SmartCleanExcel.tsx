import { useMemo, useState } from "react";
import {
  Alert,
  Button,
  Group,
  Paper,
  Stack,
  Table,
  Text,
  Title,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import CleaningServicesRoundedIcon from "@mui/icons-material/CleaningServicesRounded";
import DownloadRoundedIcon from "@mui/icons-material/DownloadRounded";
import {
  ExcelAiRequirementCard,
  ExcelResultCard,
  ExcelStatusMessage,
  ExcelToolShell,
  ExcelUploadZone,
} from "@app/components/excel/ExcelToolShell";
import apiClient from "@app/services/apiClient";
import { BaseToolProps, ToolComponent } from "@app/types/tool";

type ColumnIssue = {
  type: string;
  count: number;
  examples: string[];
  groups: string[][];
};

type ColumnQuality = {
  column: string;
  detectedType: string;
  issues: ColumnIssue[];
};

type CleanIssues = {
  emptyRowCount: number;
  duplicateRowCount: number;
  blankCellCount: number;
  trimIssueCount: number;
  columns: ColumnQuality[];
};

type AnalyzeResponse = {
  success: boolean;
  message: string;
  fileToken: string;
  sheetName: string;
  rowCount: number;
  columnCount: number;
  headers: string[];
  previewData: string[][];
  issues: CleanIssues;
};

type DeduplicateRule = {
  enabled?: boolean;
  columns?: string[];
};

type ColumnRule = {
  column: string;
  operation: string;
  options?: Record<string, unknown>;
};

type ValueMapping = {
  column: string;
  mappings: Record<string, string>;
};

type CleanRules = {
  mode: "clean";
  removeEmptyRows?: boolean;
  deduplicate?: DeduplicateRule;
  trimText?: boolean;
  normalizeFullWidth?: boolean;
  columnRules?: ColumnRule[];
  valueMappings?: ValueMapping[];
  invalidValuePolicy?: "keep" | "keep_and_mark" | "clear";
};

type AiParseResponse = {
  success: boolean;
  message: string;
  rules: CleanRules | null;
  warnings: string[];
};

type CleanStatistics = {
  sourceRowCount: number;
  resultRowCount: number;
  removedEmptyRows: number;
  removedDuplicates: number;
  trimmedCells: number;
  normalizedNumberCells: number;
  normalizedDateCells: number;
  mappedValues: number;
  invalidValueCount: number;
};

type GenerateResponse = {
  success: boolean;
  message: string;
  downloadUrl: string | null;
  fileName: string | null;
  statistics: CleanStatistics;
  warnings: string[];
};

const ACCEPTED_MIME_TYPES = [
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/vnd.ms-excel",
];

const SmartCleanExcel = ({ onError }: BaseToolProps) => {
  const { t } = useTranslation();
  const [analysis, setAnalysis] = useState<AnalyzeResponse | null>(null);
  const [requirement, setRequirement] = useState("");
  const [rules, setRules] = useState<CleanRules | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [result, setResult] = useState<GenerateResponse | null>(null);
  const [statusMessage, setStatusMessage] = useState(
    t("smartCleanExcel.status.waiting", "Waiting for an Excel file"),
  );
  const [isUploading, setIsUploading] = useState(false);
  const [isParsing, setIsParsing] = useState(false);
  const [isGenerating, setIsGenerating] = useState(false);

  const downloadHref = useMemo(() => {
    if (!result?.downloadUrl) {
      return undefined;
    }
    const baseUrl = apiClient.defaults.baseURL;
    if (typeof baseUrl === "string" && /^https?:\/\//i.test(baseUrl)) {
      return new URL(result.downloadUrl, baseUrl).toString();
    }
    return result.downloadUrl;
  }, [result?.downloadUrl]);

  const handleFile = async (acceptedFiles: File[]) => {
    const file = acceptedFiles[0];
    if (!file) {
      return;
    }
    setIsUploading(true);
    setAnalysis(null);
    setRules(null);
    setWarnings([]);
    setResult(null);
    setStatusMessage(t("smartCleanExcel.status.analyzing", "Uploading and analyzing quality..."));
    const formData = new FormData();
    formData.append("file", file);

    try {
      const response = await apiClient.post<AnalyzeResponse>(
        "/api/excel/clean/analyze",
        formData,
        { headers: { "Content-Type": "multipart/form-data" } },
      );
      if (!response.data.success) {
        setStatusMessage(response.data.message);
        return;
      }
      setAnalysis(response.data);
      setStatusMessage(response.data.message);
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : t("smartCleanExcel.status.failed", "The request failed.");
      setStatusMessage(message);
      onError?.(message);
    } finally {
      setIsUploading(false);
    }
  };

  const handleAiParse = async () => {
    if (!analysis) {
      setStatusMessage(t("smartCleanExcel.status.noFile", "Please upload an Excel file first."));
      return;
    }
    if (!requirement.trim()) {
      setStatusMessage(
        t("smartCleanExcel.status.noRequirement", "Please enter a cleaning requirement first."),
      );
      return;
    }

    setIsParsing(true);
    setRules(null);
    setWarnings([]);
    setResult(null);
    setStatusMessage(t("smartCleanExcel.status.parsing", "AI is creating cleaning rules..."));
    try {
      const response = await apiClient.post<AiParseResponse>("/api/excel/clean/ai-parse", {
        fileToken: analysis.fileToken,
        sheetName: analysis.sheetName,
        headers: analysis.headers,
        issues: analysis.issues,
        previewData: analysis.previewData,
        userRequirement: requirement,
      });
      if (!response.data.success || !response.data.rules) {
        setStatusMessage(response.data.message);
        setWarnings(response.data.warnings ?? []);
        return;
      }
      setRules(response.data.rules);
      setWarnings(response.data.warnings ?? []);
      setStatusMessage(response.data.message);
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : t("smartCleanExcel.status.failed", "The request failed.");
      setStatusMessage(message);
      onError?.(message);
    } finally {
      setIsParsing(false);
    }
  };

  const handleGenerate = async () => {
    if (!analysis || !rules) {
      setStatusMessage(
        t("smartCleanExcel.status.noRules", "Please analyze or confirm cleaning rules first."),
      );
      return;
    }
    setIsGenerating(true);
    setResult(null);
    setStatusMessage(t("smartCleanExcel.status.generating", "Generating cleaned Excel..."));
    try {
      const response = await apiClient.post<GenerateResponse>("/api/excel/clean/generate", {
        fileToken: analysis.fileToken,
        sheetName: analysis.sheetName,
        rules,
      });
      setResult(response.data);
      setWarnings(response.data.warnings ?? []);
      setStatusMessage(response.data.message);
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : t("smartCleanExcel.status.failed", "The request failed.");
      setStatusMessage(message);
      onError?.(message);
    } finally {
      setIsGenerating(false);
    }
  };

  return (
    <ExcelToolShell
      title={t("smartCleanExcel.title", "Excel Smart Data Clean")}
      description={t(
        "smartCleanExcel.description",
        "Upload Excel, detect empty rows, duplicates and mixed formats, then use AI to create cleaning rules and generate a clean workbook.",
      )}
      badges={["AI", "Data cleaning", "Deduplicate", "XLSX"]}
      steps={[
        {
          label: t("excelCommon.steps.upload", "Upload file"),
          state: analysis ? "done" : "active",
        },
        {
          label: t("excelCommon.steps.analyzeData", "Analyze data"),
          state: isUploading ? "active" : analysis ? "done" : "idle",
        },
        {
          label: t("excelCommon.steps.requirement", "Set requirement"),
          state: isParsing ? "active" : rules ? "done" : analysis ? "active" : "idle",
        },
        {
          label: t("excelCommon.steps.confirmPlan", "Confirm plan"),
          state: rules ? "done" : "idle",
        },
        {
          label: t("excelCommon.steps.generate", "Generate result"),
          state: isGenerating ? "active" : result ? (result.success ? "done" : "error") : "idle",
        },
      ]}
    >
      <ExcelUploadZone
        accept={ACCEPTED_MIME_TYPES}
        disabled={isUploading}
        maxFiles={1}
        multiple={false}
        onDrop={handleFile}
        onReject={() =>
          setStatusMessage(
            t("smartCleanExcel.status.unsupported", "Please upload .xlsx or .xls files."),
          )
        }
        title={t("smartCleanExcel.upload.title", "Drag an Excel file here, or click to choose")}
        hint={
          isUploading
            ? t("smartCleanExcel.status.analyzing", "Uploading and analyzing quality...")
            : t("smartCleanExcel.upload.hint", "One .xlsx or .xls file")
        }
        files={
          analysis
            ? [
                {
                  name: analysis.sheetName,
                  type: "Excel",
                  status: t("excelCommon.upload.parsed", "Parsed"),
                },
              ]
            : []
        }
      />

      <ExcelStatusMessage
        message={statusMessage}
        tone={result?.success ? "success" : warnings.length ? "warning" : "info"}
      />

      {analysis ? (
        <Paper withBorder radius="md" p="md">
          <Stack gap="sm">
            <Title order={3} size="h4">
              {t("smartCleanExcel.quality.title", "Data quality report")}
            </Title>
            <Group gap="lg">
              <Text size="sm">
                {t("smartCleanExcel.quality.sheet", "Sheet")}: {analysis.sheetName}
              </Text>
              <Text size="sm">
                {t("smartCleanExcel.quality.rows", "Rows")}: {analysis.rowCount}
              </Text>
              <Text size="sm">
                {t("smartCleanExcel.quality.columns", "Columns")}: {analysis.columnCount}
              </Text>
            </Group>
            <Group gap="xs">
              <Button size="xs" variant="light" color="orange">
                {t("smartCleanExcel.quality.emptyRows", "Empty rows")}:{" "}
                {analysis.issues.emptyRowCount}
              </Button>
              <Button size="xs" variant="light" color="orange">
                {t("smartCleanExcel.quality.duplicates", "Duplicates")}:{" "}
                {analysis.issues.duplicateRowCount}
              </Button>
              <Button size="xs" variant="light" color="orange">
                {t("smartCleanExcel.quality.blankCells", "Blank cells")}:{" "}
                {analysis.issues.blankCellCount}
              </Button>
              <Button size="xs" variant="light" color="orange">
                {t("smartCleanExcel.quality.trimIssues", "Trim issues")}:{" "}
                {analysis.issues.trimIssueCount}
              </Button>
            </Group>
            <Table striped withTableBorder>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("smartCleanExcel.quality.column", "Column")}</Table.Th>
                  <Table.Th>{t("smartCleanExcel.quality.detectedType", "Detected type")}</Table.Th>
                  <Table.Th>{t("smartCleanExcel.quality.issues", "Issues")}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {analysis.issues.columns.map((column) => (
                  <Table.Tr key={column.column}>
                    <Table.Td>{column.column}</Table.Td>
                    <Table.Td>{column.detectedType}</Table.Td>
                    <Table.Td>
                      {column.issues.length
                        ? column.issues.map((issue) => issue.type).join(", ")
                        : "-"}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Stack>
        </Paper>
      ) : null}

      {analysis ? (
        <ExcelAiRequirementCard
          title={t("excelCommon.aiRequirement.title", "Describe your requirement")}
          description={t(
            "excelCommon.aiRequirement.description",
            "Use one sentence to describe how you want to process the current Excel data.",
          )}
          value={requirement}
          onChange={setRequirement}
          placeholder={t(
            "smartCleanExcel.requirement.placeholder",
            "Example: remove empty rows and duplicates, trim text, normalize amount to numbers, normalize date to yyyy-MM-dd, and map Finance to Finance Dept.",
          )}
          examples={[
            t(
              "smartCleanExcel.examples.normalize",
              "Normalize amount and date formats, remove duplicates and trim extra spaces.",
            ),
            t(
              "smartCleanExcel.examples.empty",
              "Remove empty rows and keep invalid values in an exception sheet.",
            ),
            t(
              "smartCleanExcel.examples.mapping",
              "Map Finance and Finance Dept to the same department value.",
            ),
          ]}
          buttonLabel={t("excelCommon.buttons.aiAnalyze", "AI analyze requirement")}
          loading={isParsing}
          onAnalyze={handleAiParse}
        />
      ) : null}

      {rules ? (
        <Paper withBorder radius="md" p="md">
          <Stack gap="sm">
            <Title order={3} size="h4">
              {t("excelCommon.aiResult.title", "AI understanding result")}
            </Title>
            <Group gap="xs">
              {rules.removeEmptyRows ? (
                <Button size="xs" variant="light">{t("smartCleanExcel.rules.removeEmptyRows", "Remove empty rows")}</Button>
              ) : null}
              {rules.trimText ? (
                <Button size="xs" variant="light">{t("smartCleanExcel.rules.trimText", "Trim text")}</Button>
              ) : null}
              {rules.normalizeFullWidth ? (
                <Button size="xs" variant="light">{t("smartCleanExcel.rules.normalizeFullWidth", "Normalize full-width text")}</Button>
              ) : null}
              {rules.deduplicate?.enabled ? (
                <Button size="xs" variant="light">
                  {t("smartCleanExcel.rules.deduplicate", "Deduplicate")}:{" "}
                  {rules.deduplicate.columns?.join(" + ") || t("smartCleanExcel.rules.fullRow", "Full row")}
                </Button>
              ) : null}
              <Button size="xs" variant="light">
                {t("smartCleanExcel.rules.invalidPolicy", "Invalid values")}:{" "}
                {rules.invalidValuePolicy ?? "keep_and_mark"}
              </Button>
            </Group>
            {rules.columnRules?.length ? (
              <Table striped withTableBorder>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t("smartCleanExcel.rules.column", "Column")}</Table.Th>
                    <Table.Th>{t("smartCleanExcel.rules.operation", "Operation")}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rules.columnRules.map((rule) => (
                    <Table.Tr key={`${rule.column}-${rule.operation}`}>
                      <Table.Td>{rule.column}</Table.Td>
                      <Table.Td>{rule.operation}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            ) : null}
            {rules.valueMappings?.length ? (
              <Text size="sm">
                {t("smartCleanExcel.rules.valueMappings", "Value mappings")}:{" "}
                {rules.valueMappings.map((mapping) => mapping.column).join(", ")}
              </Text>
            ) : null}
            {warnings.length ? (
              <Alert color="yellow" variant="light">
                <Stack gap={4}>
                  {warnings.map((warning) => (
                    <Text key={warning} size="sm">
                      {warning}
                    </Text>
                  ))}
                </Stack>
              </Alert>
            ) : null}
            <Group justify="flex-end">
              <Button
                leftSection={<CleaningServicesRoundedIcon fontSize="small" />}
                onClick={handleGenerate}
                loading={isGenerating}
              >
                {t("excelCommon.buttons.confirmProcess", "Confirm and process")}
              </Button>
            </Group>
          </Stack>
        </Paper>
      ) : null}

      {result?.success ? (
        <ExcelResultCard
          title={t("excelCommon.result.title", "Processing completed")}
          stats={[
            {
              label: t("smartCleanExcel.result.sourceRows", "Source rows"),
              value: result.statistics.sourceRowCount,
            },
            {
              label: t("smartCleanExcel.result.resultRows", "Result rows"),
              value: result.statistics.resultRowCount,
            },
            {
              label: t("smartCleanExcel.result.emptyRows", "Removed empty rows"),
              value: result.statistics.removedEmptyRows,
            },
            {
              label: t("smartCleanExcel.result.duplicates", "Removed duplicates"),
              value: result.statistics.removedDuplicates,
            },
            {
              label: t("smartCleanExcel.result.invalidValues", "Invalid values"),
              value: result.statistics.invalidValueCount,
            },
          ]}
          action={
            <Button
              component="a"
              href={downloadHref}
              leftSection={<DownloadRoundedIcon fontSize="small" />}
            >
              {t("excelCommon.buttons.downloadExcel", "Download Excel")}
            </Button>
          }
        />
      ) : null}
    </ExcelToolShell>
  );
};

export default SmartCleanExcel as ToolComponent;
