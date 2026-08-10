import { useMemo, useState } from "react";
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Checkbox,
  Group,
  Modal,
  NumberInput,
  Paper,
  PasswordInput,
  Select,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  Title,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import AddRoundedIcon from "@mui/icons-material/AddRounded";
import DeleteRoundedIcon from "@mui/icons-material/DeleteRounded";
import DownloadRoundedIcon from "@mui/icons-material/DownloadRounded";
import SettingsRoundedIcon from "@mui/icons-material/SettingsRounded";
import TuneRoundedIcon from "@mui/icons-material/TuneRounded";
import {
  ExcelAiRequirementCard,
  ExcelDataPreview,
  ExcelResultCard,
  ExcelStatusMessage,
  ExcelToolShell,
  ExcelUploadZone,
} from "@app/components/excel/ExcelToolShell";
import apiClient from "@app/services/apiClient";
import { BaseToolProps, ToolComponent } from "@app/types/tool";

type PreviewResponse = {
  success: boolean;
  message: string;
  fileToken: string | null;
  sheets: string[];
  headers: string[];
  previewData: string[][];
  sheetHeaders?: Record<string, string[]>;
  sheetPreviews?: Record<string, string[][]>;
};

type GenerateResponse = {
  success: boolean;
  message: string;
  downloadUrl: string | null;
  fileName: string | null;
  rowCount: number;
};

type AiRules = {
  mode?: "extract" | "summary";
  selectedColumns?: string[];
  filters?: FilterRule[];
  removeEmptyRows?: boolean;
  deduplicate?: boolean;
  sort?: {
    column: string;
    direction: "asc" | "desc";
  } | null;
  groupByColumn?: string | null;
  groupBy?: string[];
  metrics?: SummaryMetric[];
  fieldMappings?: FieldMapping[];
};

type AiParseResponse = {
  success: boolean;
  message: string;
  request?: AiRules;
  rules?: AiRules | null;
  warnings?: string[];
};

type AiStatusResponse = {
  enabled: boolean;
  baseUrlConfigured: boolean;
  apiKeyConfigured: boolean;
  model: string;
};

type AiConfigurationResponse = {
  available: boolean;
  enabled: boolean;
  baseUrl: string;
  apiKeyConfigured: boolean;
  model: string;
  timeoutSeconds: number;
  environmentConfigured: boolean;
};

type AiConnectionTestResponse = {
  success: boolean;
  message: string;
};

type FieldMapping = {
  userField: string;
  matchedHeader: string;
  confidence: number;
};

type SummaryMetric = {
  column: string;
  operation: "sum" | "count" | "avg" | "max" | "min";
  alias: string;
};

type FilterRule = {
  column: string;
  operator: string;
  value: string;
  type: "text" | "number" | "date";
  startDate?: string;
  endDate?: string;
};

const ACCEPTED_MIME_TYPES = [
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/vnd.ms-excel",
];

const SmartExtractExcel = ({ onError }: BaseToolProps) => {
  const { t } = useTranslation();
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<PreviewResponse | null>(null);
  const [selectedSheet, setSelectedSheet] = useState<string | null>(null);
  const [selectedColumns, setSelectedColumns] = useState<string[]>([]);
  const [filters, setFilters] = useState<FilterRule[]>([]);
  const [removeEmptyRows, setRemoveEmptyRows] = useState(true);
  const [deduplicate, setDeduplicate] = useState(false);
  const [sortColumn, setSortColumn] = useState<string | null>(null);
  const [sortDirection, setSortDirection] = useState<"asc" | "desc">("asc");
  const [groupByColumn, setGroupByColumn] = useState<string | null>(null);
  const [requirementText, setRequirementText] = useState("");
  const [aiRules, setAiRules] = useState<AiRules | null>(null);
  const [aiWarnings, setAiWarnings] = useState<string[]>([]);
  const [aiStatusMessage, setAiStatusMessage] = useState("");
  const [generateResult, setGenerateResult] = useState<GenerateResponse | null>(
    null,
  );
  const [statusMessage, setStatusMessage] = useState(
    t("smartExtractExcel.status.waiting", "Waiting for an Excel file"),
  );
  const [isPreviewing, setIsPreviewing] = useState(false);
  const [isGenerating, setIsGenerating] = useState(false);
  const [isParsingRequirement, setIsParsingRequirement] = useState(false);
  const [isCheckingAiStatus, setIsCheckingAiStatus] = useState(false);
  const [isAiConfigOpen, setIsAiConfigOpen] = useState(false);
  const [isSavingAiConfig, setIsSavingAiConfig] = useState(false);
  const [isTestingAiConfig, setIsTestingAiConfig] = useState(false);
  const [aiConfigEnabled, setAiConfigEnabled] = useState(true);
  const [aiBaseUrl, setAiBaseUrl] = useState("");
  const [aiApiKey, setAiApiKey] = useState("");
  const [aiApiKeyConfigured, setAiApiKeyConfigured] = useState(false);
  const [aiModel, setAiModel] = useState("gpt-4o-mini");
  const [aiTimeoutSeconds, setAiTimeoutSeconds] = useState(30);
  const [aiConfigMessage, setAiConfigMessage] = useState("");

  const headers = useMemo(() => {
    if (!preview) {
      return [];
    }
    if (selectedSheet && preview.sheetHeaders?.[selectedSheet]) {
      return preview.sheetHeaders[selectedSheet];
    }
    return preview.headers;
  }, [preview, selectedSheet]);

  const previewData = useMemo(() => {
    if (!preview) {
      return [];
    }
    if (selectedSheet && preview.sheetPreviews?.[selectedSheet]) {
      return preview.sheetPreviews[selectedSheet];
    }
    return preview.previewData;
  }, [preview, selectedSheet]);

  const columnOptions = headers.map((header) => ({
    value: header,
    label: header,
  }));

  const downloadHref = useMemo(() => {
    if (!generateResult?.downloadUrl) {
      return undefined;
    }
    const baseUrl = apiClient.defaults.baseURL;
    if (typeof baseUrl === "string" && /^https?:\/\//i.test(baseUrl)) {
      return new URL(generateResult.downloadUrl, baseUrl).toString();
    }
    return generateResult.downloadUrl;
  }, [generateResult?.downloadUrl]);

  const uploadPreview = async (file: File) => {
    setIsPreviewing(true);
    setStatusMessage(
      t("smartExtractExcel.status.parsing", "Parsing Excel headers..."),
    );
    setGenerateResult(null);

    const formData = new FormData();
    formData.append("file", file);

    try {
      const response = await apiClient.post<PreviewResponse>(
        "/api/excel/smart-extract/preview",
        formData,
        { headers: { "Content-Type": "multipart/form-data" } },
      );
      if (!response.data.success) {
        setStatusMessage(response.data.message);
        return;
      }
      const firstSheet = response.data.sheets[0] ?? null;
      const firstHeaders =
        firstSheet && response.data.sheetHeaders?.[firstSheet]
          ? response.data.sheetHeaders[firstSheet]
          : response.data.headers;
      setPreview(response.data);
      setSelectedSheet(firstSheet);
      setSelectedColumns(firstHeaders);
      setFilters([]);
      setSortColumn(null);
      setGroupByColumn(null);
      setAiRules(null);
      setAiWarnings([]);
      setStatusMessage(response.data.message);
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : t("smartExtractExcel.status.failed", "The request failed.");
      setStatusMessage(message);
      onError?.(message);
    } finally {
      setIsPreviewing(false);
    }
  };

  const handleFiles = (files: File[]) => {
    const nextFile = files[0] ?? null;
    setSelectedFile(nextFile);
    setPreview(null);
    setGenerateResult(null);
    setAiRules(null);
    setAiWarnings([]);
    if (nextFile) {
      void uploadPreview(nextFile);
    }
  };

  const addFilter = () => {
    setFilters((current) => [
      ...current,
      {
        column: headers[0] ?? "",
        operator: "contains",
        value: "",
        type: "text",
      },
    ]);
  };

  const updateFilter = (index: number, next: Partial<FilterRule>) => {
    setFilters((current) =>
      current.map((filter, filterIndex) =>
        filterIndex === index ? { ...filter, ...next } : filter,
      ),
    );
  };

  const removeFilter = (index: number) => {
    setFilters((current) =>
      current.filter((_, filterIndex) => filterIndex !== index),
    );
  };

  const handleSheetChange = (sheetName: string | null) => {
    setSelectedSheet(sheetName);
    const nextHeaders =
      sheetName && preview?.sheetHeaders?.[sheetName]
        ? preview.sheetHeaders[sheetName]
        : preview?.headers ?? [];
    setSelectedColumns(nextHeaders);
    setFilters([]);
    setSortColumn(null);
    setGroupByColumn(null);
    setAiRules(null);
    setAiWarnings([]);
  };

  const handleGenerate = async () => {
    if (!preview?.fileToken || !selectedSheet) {
      setStatusMessage(
        t("smartExtractExcel.status.noFile", "Please upload an Excel file first."),
      );
      return;
    }

    setIsGenerating(true);
    setStatusMessage(
      t("smartExtractExcel.status.generating", "Generating Excel..."),
    );

    try {
      const response = await apiClient.post<GenerateResponse>(
        "/api/excel/smart-extract/generate",
        {
          fileToken: preview.fileToken,
          sheetName: selectedSheet,
          mode: aiRules?.mode === "summary" ? "summary" : "extract",
          selectedColumns,
          filters,
          removeEmptyRows,
          deduplicate,
          sort: sortColumn
            ? { column: sortColumn, direction: sortDirection }
            : null,
          groupByColumn: groupByColumn || null,
          groupBy: aiRules?.mode === "summary" ? aiRules.groupBy ?? [] : [],
          metrics: aiRules?.mode === "summary" ? aiRules.metrics ?? [] : [],
        },
      );
      setGenerateResult(response.data);
      setStatusMessage(response.data.message);
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : t("smartExtractExcel.status.failed", "The request failed.");
      setStatusMessage(message);
      onError?.(message);
    } finally {
      setIsGenerating(false);
    }
  };

  const applyRulesToForm = (rules: AiRules) => {
    if (rules.selectedColumns?.length) {
      setSelectedColumns(rules.selectedColumns);
    }
    setFilters(rules.filters ?? []);
    setRemoveEmptyRows(rules.removeEmptyRows ?? false);
    setDeduplicate(rules.deduplicate ?? false);
    setSortColumn(rules.sort?.column ?? null);
    setSortDirection(rules.sort?.direction ?? "asc");
    setGroupByColumn(rules.groupByColumn ?? null);
  };

  const handleParseRequirement = async () => {
    if (!preview?.fileToken || !selectedSheet) {
      setStatusMessage(
        t("smartExtractExcel.status.noFile", "Please upload an Excel file first."),
      );
      return;
    }
    if (!requirementText.trim()) {
      setStatusMessage(
        t(
          "smartExtractExcel.requirement.empty",
          "Please enter the extraction requirement first.",
        ),
      );
      return;
    }

    setIsParsingRequirement(true);
    setStatusMessage(
      t("smartExtractExcel.status.parsingRequirement", "AI is parsing requirement..."),
    );

    try {
      const response = await apiClient.post<AiParseResponse>(
        "/api/excel/smart-extract/ai-parse",
        {
          fileToken: preview.fileToken,
          sheetName: selectedSheet,
          headers,
          previewData,
          userRequirement: requirementText,
        },
      );
      const rules = response.data.rules ?? response.data.request;
      if (!response.data.success || !rules) {
        setStatusMessage(response.data.message);
        setAiWarnings(response.data.warnings ?? []);
        return;
      }
      applyRulesToForm(rules);
      setAiRules(rules);
      setAiWarnings(response.data.warnings ?? []);
      setStatusMessage(response.data.message);
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : t("smartExtractExcel.status.failed", "The request failed.");
      setStatusMessage(message);
      onError?.(message);
    } finally {
      setIsParsingRequirement(false);
    }
  };

  const handleCheckAiStatus = async () => {
    setIsCheckingAiStatus(true);
    try {
      const response = await apiClient.get<AiStatusResponse>(
        "/api/excel/smart-extract/ai-status",
      );
      setAiStatusMessage(
        response.data.enabled
          ? t("smartExtractExcel.requirement.aiEnabled", "AI service is enabled.")
          : t(
              "smartExtractExcel.requirement.aiDisabled",
              "AI service is not enabled. Please check EXCEL_AI_BASE_URL, EXCEL_AI_API_KEY, and EXCEL_AI_MODEL.",
            ),
      );
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : t("smartExtractExcel.status.failed", "The request failed.");
      setAiStatusMessage(message);
      onError?.(message);
    } finally {
      setIsCheckingAiStatus(false);
    }
  };

  const handleOpenAiConfiguration = async () => {
    setIsAiConfigOpen(true);
    setAiConfigMessage("");
    try {
      const response = await apiClient.get<AiConfigurationResponse>(
        "/api/excel/smart-extract/ai-config",
      );
      setAiConfigEnabled(response.data.enabled);
      setAiBaseUrl(response.data.baseUrl);
      setAiApiKey("");
      setAiApiKeyConfigured(response.data.apiKeyConfigured);
      setAiModel(response.data.model || "gpt-4o-mini");
      setAiTimeoutSeconds(response.data.timeoutSeconds || 30);
    } catch (error) {
      setAiConfigMessage(
        error instanceof Error
          ? error.message
          : t("smartExtractExcel.status.failed", "The request failed."),
      );
    }
  };

  const handleSaveAiConfiguration = async () => {
    setIsSavingAiConfig(true);
    setAiConfigMessage("");
    try {
      const response = await apiClient.post<AiConfigurationResponse>(
        "/api/excel/smart-extract/ai-config",
        {
          enabled: aiConfigEnabled,
          baseUrl: aiBaseUrl,
          apiKey: aiApiKey,
          model: aiModel,
          timeoutSeconds: aiTimeoutSeconds,
          keepExistingApiKey: !aiApiKey,
        },
      );
      setAiApiKey("");
      setAiApiKeyConfigured(response.data.apiKeyConfigured);
      setAiConfigMessage(
        response.data.available
          ? t("smartExtractExcel.aiConfig.saved", "AI configuration saved.")
          : t(
              "smartExtractExcel.aiConfig.incomplete",
              "Configuration saved, but the AI service is not fully configured.",
            ),
      );
      setAiStatusMessage(
        response.data.available
          ? t("smartExtractExcel.requirement.aiEnabled", "AI service is enabled.")
          : t(
              "smartExtractExcel.requirement.aiDisabled",
              "AI service is not enabled. Please check the AI configuration.",
            ),
      );
    } catch (error) {
      setAiConfigMessage(
        error instanceof Error
          ? error.message
          : t("smartExtractExcel.status.failed", "The request failed."),
      );
    } finally {
      setIsSavingAiConfig(false);
    }
  };

  const handleTestAiConfiguration = async () => {
    setIsTestingAiConfig(true);
    setAiConfigMessage("");
    try {
      const response = await apiClient.post<AiConnectionTestResponse>(
        "/api/excel/smart-extract/ai-config/test",
      );
      setAiConfigMessage(response.data.message);
    } catch (error) {
      setAiConfigMessage(
        error instanceof Error
          ? error.message
          : t("smartExtractExcel.status.failed", "The request failed."),
      );
    } finally {
      setIsTestingAiConfig(false);
    }
  };

  const mappingStatus = (confidence: number) => {
    if (confidence < 0.6) {
      return {
        color: "red",
        label: t("smartExtractExcel.aiResult.mappingUncertain", "Uncertain"),
      };
    }
    if (confidence < 0.8) {
      return {
        color: "yellow",
        label: t("smartExtractExcel.aiResult.mappingReview", "Review suggested"),
      };
    }
    return {
      color: "green",
      label: t("smartExtractExcel.aiResult.mappingMatched", "Matched"),
    };
  };

  return (
    <ExcelToolShell
      title={t("smartExtractExcel.title", "Excel Smart Extract")}
      description={t(
        "smartExtractExcel.description",
        "Upload an Excel file, extract rows by fields, keywords, numbers, dates, grouping, and regenerate a new Excel file.",
      )}
      badges={["AI", "Filter", "Summary", "XLSX"]}
      steps={[
        {
          label: t("excelCommon.steps.upload", "Upload file"),
          state: preview ? "done" : "active",
        },
        {
          label: t("excelCommon.steps.selectData", "Select data"),
          state: preview ? (selectedSheet ? "done" : "active") : "idle",
        },
        {
          label: t("excelCommon.steps.requirement", "Set requirement"),
          state: isParsingRequirement
            ? "active"
            : aiRules
              ? "done"
              : preview
                ? "active"
                : "idle",
        },
        {
          label: t("excelCommon.steps.confirmPlan", "Confirm plan"),
          state: aiRules || selectedColumns.length ? "done" : "idle",
        },
        {
          label: t("excelCommon.steps.generate", "Generate result"),
          state: isGenerating
            ? "active"
            : generateResult
              ? generateResult.success
                ? "done"
                : "error"
              : "idle",
        },
      ]}
    >
      <ExcelUploadZone
        accept={ACCEPTED_MIME_TYPES}
        maxFiles={1}
        multiple={false}
        onDrop={handleFiles}
        onReject={() =>
          setStatusMessage(
            t(
              "smartExtractExcel.status.unsupported",
              "Please upload a .xlsx or .xls file.",
            ),
          )
        }
        title={t("smartExtractExcel.upload.title", "Drag an Excel file here, or click to choose")}
        hint={
          selectedFile?.name ??
          t("smartExtractExcel.upload.noFile", "No file selected")
        }
        files={
          selectedFile
            ? [
                {
                  name: selectedFile.name,
                  size: selectedFile.size,
                  type: selectedFile.type || "Excel",
                  status: preview
                    ? t("excelCommon.upload.parsed", "Parsed")
                    : isPreviewing
                      ? t("excelCommon.upload.parsing", "Parsing")
                      : t("excelCommon.upload.ready", "Ready"),
                },
              ]
            : []
        }
      />

      <ExcelStatusMessage
        message={statusMessage}
        tone={generateResult?.success ? "success" : aiWarnings.length ? "warning" : "info"}
      />

      {preview ? (
        <>
          <Paper withBorder radius="md" p="md">
            <Stack gap="md">
              <Group grow align="end">
                <Select
                  label={t("smartExtractExcel.sheet.label", "Sheet")}
                  data={preview.sheets}
                  value={selectedSheet}
                  onChange={handleSheetChange}
                />
                <Button
                  leftSection={<TuneRoundedIcon fontSize="small" />}
                  onClick={handleGenerate}
                  loading={isGenerating}
                  disabled={!selectedColumns.length}
                >
                  {t("smartExtractExcel.generateButton", "Generate Excel")}
                </Button>
              </Group>

              <Stack gap="xs">
                <Text fw={600} size="sm">
                  {t("smartExtractExcel.columns.title", "Columns to keep")}
                </Text>
                <Checkbox.Group value={selectedColumns} onChange={setSelectedColumns}>
                  <Group gap="sm">
                    {headers.map((header) => (
                      <Checkbox key={header} value={header} label={header} />
                    ))}
                  </Group>
                </Checkbox.Group>
              </Stack>
            </Stack>
          </Paper>

          <ExcelAiRequirementCard
            title={t("excelCommon.aiRequirement.title", "Describe your requirement")}
            description={t(
              "excelCommon.aiRequirement.description",
              "Use one sentence to describe how you want to process the current Excel data.",
            )}
            value={requirementText}
            onChange={setRequirementText}
            placeholder={t(
              "smartExtractExcel.requirement.placeholder",
              "Example: extract finance department records with amount greater than 1000, keep name, amount and date, and sort amount from high to low.",
            )}
            examples={[
              t(
                "smartExtractExcel.examples.extract",
                "Extract finance records with amount greater than 1000, keep name, amount and date.",
              ),
              t(
                "smartExtractExcel.examples.summary",
                "Summarize amount by department and count records.",
              ),
              t(
                "smartExtractExcel.examples.remark",
                "Find records whose remark contains reimbursement and regenerate Excel.",
              ),
            ]}
            buttonLabel={t("excelCommon.buttons.aiAnalyze", "AI analyze requirement")}
            loading={isParsingRequirement}
            onAnalyze={handleParseRequirement}
            status={
              <Stack gap="xs">
                <Group justify="space-between">
                  <Text c="dimmed" size="sm">
                    {t(
                      "smartExtractExcel.requirement.hint",
                      "AI converts the description into the rules below. You can still adjust them manually before generating Excel.",
                    )}
                  </Text>
                  <Button
                    variant="subtle"
                    onClick={handleCheckAiStatus}
                    loading={isCheckingAiStatus}
                  >
                    {t("smartExtractExcel.requirement.checkAiButton", "Check AI config")}
                  </Button>
                  <Button
                    variant="light"
                    leftSection={<SettingsRoundedIcon fontSize="small" />}
                    onClick={handleOpenAiConfiguration}
                  >
                    {t("smartExtractExcel.aiConfig.openButton", "Configure AI service")}
                  </Button>
                </Group>
                {aiStatusMessage ? (
                  <Alert
                    color={
                      aiStatusMessage ===
                      t("smartExtractExcel.requirement.aiEnabled", "AI service is enabled.")
                        ? "green"
                        : "yellow"
                    }
                    variant="light"
                  >
                    {aiStatusMessage}
                  </Alert>
                ) : null}
              </Stack>
            }
          />

              {aiRules ? (
                <Paper withBorder radius="sm" p="sm">
                  <Stack gap={6}>
                    <Text fw={600} size="sm">
                      {t("excelCommon.aiResult.title", "AI understanding result")}
                    </Text>
                    <Text size="sm">
                      {t("smartExtractExcel.aiResult.mode", "Mode")}:{" "}
                      {aiRules.mode === "summary"
                        ? t("smartExtractExcel.aiResult.summaryMode", "Summary")
                        : t("smartExtractExcel.aiResult.extractMode", "Extract")}
                    </Text>
                    <Text size="sm">
                      {t("smartExtractExcel.aiResult.columns", "Columns")}:{" "}
                      {(aiRules.selectedColumns ?? []).join(", ") || "-"}
                    </Text>
                    <Text size="sm">
                      {t("smartExtractExcel.aiResult.filters", "Filters")}:{" "}
                      {(aiRules.filters ?? [])
                        .map(
                          (filter) =>
                            `${filter.column} ${filter.operator} ${
                              filter.value || filter.startDate || ""
                            }${filter.endDate ? ` - ${filter.endDate}` : ""}`,
                        )
                        .join("; ") || "-"}
                    </Text>
                    <Text size="sm">
                      {t("smartExtractExcel.aiResult.sort", "Sort")}:{" "}
                      {aiRules.sort
                        ? `${aiRules.sort.column} ${aiRules.sort.direction}`
                        : "-"}
                    </Text>
                    {aiRules.mode === "summary" ? (
                      <Stack gap={4}>
                        <Text size="sm">
                          {t("smartExtractExcel.aiResult.groupBy", "Group by")}:{" "}
                          {(aiRules.groupBy ?? []).join(", ") || "-"}
                        </Text>
                        <Text size="sm">
                          {t("smartExtractExcel.aiResult.metrics", "Metrics")}:{" "}
                          {(aiRules.metrics ?? [])
                            .map(
                              (metric) =>
                                `${metric.column} -> ${metric.operation} -> ${metric.alias}`,
                            )
                            .join("; ") || "-"}
                        </Text>
                      </Stack>
                    ) : null}
                    <Text size="sm">
                      {t("smartExtractExcel.aiResult.options", "Options")}:{" "}
                      {[
                        aiRules.removeEmptyRows
                          ? t("smartExtractExcel.advanced.removeEmptyRows", "Remove empty rows")
                          : null,
                        aiRules.deduplicate
                          ? t("smartExtractExcel.advanced.deduplicate", "Deduplicate")
                          : null,
                        aiRules.groupByColumn
                          ? `${t(
                              "smartExtractExcel.advanced.groupBy",
                              "Group by column",
                            )}: ${aiRules.groupByColumn}`
                          : null,
                      ]
                        .filter(Boolean)
                        .join(", ") || "-"}
                    </Text>
                    {aiRules.fieldMappings?.length ? (
                      <Stack gap={6}>
                        <Text fw={600} size="sm">
                          {t("smartExtractExcel.aiResult.mappings", "Field mappings")}
                        </Text>
                        <Table striped withTableBorder withColumnBorders>
                          <Table.Thead>
                            <Table.Tr>
                              <Table.Th>
                                {t("smartExtractExcel.aiResult.userField", "User field")}
                              </Table.Th>
                              <Table.Th>
                                {t("smartExtractExcel.aiResult.matchedHeader", "Excel field")}
                              </Table.Th>
                              <Table.Th>
                                {t("smartExtractExcel.aiResult.confidence", "Confidence")}
                              </Table.Th>
                              <Table.Th>
                                {t("smartExtractExcel.aiResult.mappingStatus", "Status")}
                              </Table.Th>
                            </Table.Tr>
                          </Table.Thead>
                          <Table.Tbody>
                            {aiRules.fieldMappings.map((mapping) => {
                              const status = mappingStatus(mapping.confidence);
                              return (
                                <Table.Tr
                                  key={`${mapping.userField}-${mapping.matchedHeader}`}
                                >
                                  <Table.Td>{mapping.userField}</Table.Td>
                                  <Table.Td>{mapping.matchedHeader}</Table.Td>
                                  <Table.Td>
                                    {Math.round(mapping.confidence * 100)}%
                                  </Table.Td>
                                  <Table.Td>
                                    <Badge color={status.color} variant="light">
                                      {status.label}
                                    </Badge>
                                  </Table.Td>
                                </Table.Tr>
                              );
                            })}
                          </Table.Tbody>
                        </Table>
                      </Stack>
                    ) : null}
                    {aiWarnings.length ? (
                      <Alert color="yellow" variant="light">
                        <Stack gap={4}>
                          {aiWarnings.map((warning) => (
                            <Text key={warning} size="sm">
                              {warning}
                            </Text>
                          ))}
                        </Stack>
                      </Alert>
                    ) : null}
                  </Stack>
                </Paper>
              ) : null}

          <Paper withBorder radius="md" p="md">
            <Group justify="space-between" mb="sm">
              <Title order={3} size="h4">
                {t("smartExtractExcel.filters.title", "Filters")}
              </Title>
              <Button
                variant="light"
                leftSection={<AddRoundedIcon fontSize="small" />}
                onClick={addFilter}
              >
                {t("smartExtractExcel.filters.add", "Add filter")}
              </Button>
            </Group>

            <Stack gap="sm">
              {filters.map((filter, index) => (
                <Group key={`filter-${index}`} align="end" grow>
                  <Select
                    label={t("smartExtractExcel.filters.column", "Column")}
                    data={columnOptions}
                    value={filter.column}
                    onChange={(value) => updateFilter(index, { column: value ?? "" })}
                  />
                  <Select
                    label={t("smartExtractExcel.filters.type", "Type")}
                    data={[
                      { value: "text", label: t("smartExtractExcel.filters.text", "Text") },
                      { value: "number", label: t("smartExtractExcel.filters.number", "Number") },
                      { value: "date", label: t("smartExtractExcel.filters.date", "Date") },
                    ]}
                    value={filter.type}
                    onChange={(value) =>
                      updateFilter(index, {
                        type: (value as FilterRule["type"]) ?? "text",
                        operator: value === "number" ? ">" : "contains",
                      })
                    }
                  />
                  {filter.type === "date" ? (
                    <>
                      <TextInput
                        label={t("smartExtractExcel.filters.startDate", "Start date")}
                        placeholder="2026-01-01"
                        value={filter.startDate ?? ""}
                        onChange={(event) =>
                          updateFilter(index, { startDate: event.currentTarget.value })
                        }
                      />
                      <TextInput
                        label={t("smartExtractExcel.filters.endDate", "End date")}
                        placeholder="2026-12-31"
                        value={filter.endDate ?? ""}
                        onChange={(event) =>
                          updateFilter(index, { endDate: event.currentTarget.value })
                        }
                      />
                    </>
                  ) : (
                    <>
                      <Select
                        label={t("smartExtractExcel.filters.operator", "Operator")}
                        data={
                          filter.type === "number"
                            ? [">", ">=", "<", "<=", "="]
                            : [{ value: "contains", label: t("smartExtractExcel.filters.contains", "Contains") }]
                        }
                        value={filter.operator}
                        onChange={(value) =>
                          updateFilter(index, { operator: value ?? "contains" })
                        }
                      />
                      <TextInput
                        label={t("smartExtractExcel.filters.value", "Value")}
                        value={filter.value}
                        onChange={(event) =>
                          updateFilter(index, { value: event.currentTarget.value })
                        }
                      />
                    </>
                  )}
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    onClick={() => removeFilter(index)}
                    aria-label={t("smartExtractExcel.filters.remove", "Remove filter")}
                  >
                    <DeleteRoundedIcon fontSize="small" />
                  </ActionIcon>
                </Group>
              ))}
              {!filters.length ? (
                <Text c="dimmed" size="sm">
                  {t("smartExtractExcel.filters.empty", "No filters added.")}
                </Text>
              ) : null}
            </Stack>
          </Paper>

          <Paper withBorder radius="md" p="md">
            <Stack gap="md">
              <Title order={3} size="h4">
                {t("smartExtractExcel.advanced.title", "Advanced options")}
              </Title>
              <Group gap="xl">
                <Checkbox
                  checked={removeEmptyRows}
                  onChange={(event) => setRemoveEmptyRows(event.currentTarget.checked)}
                  label={t("smartExtractExcel.advanced.removeEmptyRows", "Remove empty rows")}
                />
                <Checkbox
                  checked={deduplicate}
                  onChange={(event) => setDeduplicate(event.currentTarget.checked)}
                  label={t("smartExtractExcel.advanced.deduplicate", "Deduplicate")}
                />
              </Group>
              <Group grow>
                <Select
                  label={t("smartExtractExcel.advanced.sortColumn", "Sort column")}
                  data={columnOptions}
                  value={sortColumn}
                  clearable
                  onChange={setSortColumn}
                />
                <Select
                  label={t("smartExtractExcel.advanced.sortDirection", "Direction")}
                  data={[
                    { value: "asc", label: t("smartExtractExcel.advanced.asc", "Ascending") },
                    { value: "desc", label: t("smartExtractExcel.advanced.desc", "Descending") },
                  ]}
                  value={sortDirection}
                  onChange={(value) => setSortDirection((value as "asc" | "desc") ?? "asc")}
                />
                <Select
                  label={t("smartExtractExcel.advanced.groupBy", "Group by column")}
                  data={columnOptions}
                  value={groupByColumn}
                  clearable
                  onChange={setGroupByColumn}
                />
              </Group>
            </Stack>
          </Paper>

          <ExcelDataPreview
            title={t("smartExtractExcel.preview.title", "Data preview")}
            sheetName={selectedSheet ?? undefined}
            totalRows={previewData.length}
            columnCount={headers.length}
            headers={headers}
            rows={previewData}
            emptyText={t(
              "smartExtractExcel.preview.empty",
              "Upload an Excel file to preview sheets and headers.",
            )}
            action={
              <Button
                leftSection={<DownloadRoundedIcon fontSize="small" />}
                disabled={!downloadHref}
                component={downloadHref ? "a" : "button"}
                href={downloadHref}
                download={generateResult?.fileName ?? "smart_extract_result.xlsx"}
              >
                {t("excelCommon.buttons.downloadExcel", "Download Excel")}
              </Button>
            }
          />
          {generateResult?.success ? (
            <ExcelResultCard
              title={t("excelCommon.result.title", "Processing completed")}
              stats={[
                {
                  label: t("smartExtractExcel.result.rowCount", "Rows generated"),
                  value: generateResult.rowCount,
                },
                {
                  label: t("smartExtractExcel.aiResult.mode", "Mode"),
                  value:
                    aiRules?.mode === "summary"
                      ? t("smartExtractExcel.aiResult.summaryMode", "Summary")
                      : t("smartExtractExcel.aiResult.extractMode", "Extract"),
                },
                {
                  label: t("smartExtractExcel.aiResult.columns", "Columns"),
                  value: selectedColumns.length,
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
        </>
      ) : (
        <Paper withBorder radius="md" p="md">
          <Text c="dimmed" size="sm">
            {isPreviewing
              ? t("smartExtractExcel.status.parsing", "Parsing Excel headers...")
              : t(
                  "smartExtractExcel.preview.empty",
                  "Upload an Excel file to preview sheets and headers.",
                )}
          </Text>
        </Paper>
      )}

      <Modal
        opened={isAiConfigOpen}
        onClose={() => setIsAiConfigOpen(false)}
        title={t("smartExtractExcel.aiConfig.title", "AI service configuration")}
        centered
      >
        <Stack gap="md">
          <Alert color="blue" variant="light">
            {t(
              "smartExtractExcel.aiConfig.securityHint",
              "The API Key is sent only to this server and is never displayed again. Runtime configuration is cleared when the server restarts.",
            )}
          </Alert>
          <Switch
            checked={aiConfigEnabled}
            onChange={(event) => setAiConfigEnabled(event.currentTarget.checked)}
            label={t("smartExtractExcel.aiConfig.enabled", "Enable AI parsing")}
          />
          <TextInput
            label={t("smartExtractExcel.aiConfig.baseUrl", "Service URL")}
            placeholder="https://api.openai.com/v1"
            value={aiBaseUrl}
            onChange={(event) => setAiBaseUrl(event.currentTarget.value)}
            required
          />
          <PasswordInput
            label={t("smartExtractExcel.aiConfig.apiKey", "API Key")}
            placeholder={
              aiApiKeyConfigured
                ? t(
                    "smartExtractExcel.aiConfig.keyConfigured",
                    "Configured; leave blank to keep it",
                  )
                : "sk-..."
            }
            value={aiApiKey}
            onChange={(event) => setAiApiKey(event.currentTarget.value)}
            required={!aiApiKeyConfigured}
          />
          <TextInput
            label={t("smartExtractExcel.aiConfig.model", "Model")}
            placeholder="gpt-4o-mini"
            value={aiModel}
            onChange={(event) => setAiModel(event.currentTarget.value)}
            required
          />
          <NumberInput
            label={t("smartExtractExcel.aiConfig.timeout", "Timeout (seconds)")}
            value={aiTimeoutSeconds}
            min={1}
            max={300}
            onChange={(value) => setAiTimeoutSeconds(Number(value) || 30)}
          />
          {aiConfigMessage ? (
            <Alert color="blue" variant="light">
              {aiConfigMessage}
            </Alert>
          ) : null}
          <Group justify="flex-end">
            <Button
              variant="default"
              onClick={handleTestAiConfiguration}
              loading={isTestingAiConfig}
              disabled={!aiApiKeyConfigured && !aiApiKey}
            >
              {t("smartExtractExcel.aiConfig.testButton", "Test connection")}
            </Button>
            <Button onClick={handleSaveAiConfiguration} loading={isSavingAiConfig}>
              {t("smartExtractExcel.aiConfig.saveButton", "Save configuration")}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </ExcelToolShell>
  );
};

export default SmartExtractExcel as ToolComponent;
