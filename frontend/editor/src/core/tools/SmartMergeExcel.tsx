import { useMemo, useState } from "react";
import {
  Alert,
  Button,
  Checkbox,
  Group,
  Paper,
  Stack,
  Table,
  Text,
  Title,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
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

type MergeSheetInfo = {
  sheetName: string;
  headers: string[];
  previewData: string[][];
  rowCount: number;
};

type MergeFileInfo = {
  fileToken: string;
  fileName: string;
  fileSize: number;
  sheets: MergeSheetInfo[];
};

type MergeSource = {
  fileToken: string;
  fileName: string;
  sheetName: string;
  headers: string[];
  previewData: string[][];
};

type FieldMapping = {
  targetField: string;
  sourceFields: string[];
  confidence?: number;
};

type MergeRules = {
  mode: "merge";
  mergeType: "append" | "join" | "multi_sheet_append";
  sources?: MergeSource[];
  fieldMappings?: FieldMapping[];
  selectedColumns?: string[];
  removeEmptyRows?: boolean;
  deduplicate?: boolean;
  deduplicateColumns?: string[];
  addSourceColumn?: boolean;
  sourceColumnName?: string;
};

type UploadResponse = {
  success: boolean;
  message: string;
  files: MergeFileInfo[];
};

type AiParseResponse = {
  success: boolean;
  message: string;
  rules: MergeRules | null;
  warnings: string[];
};

type GenerateResponse = {
  success: boolean;
  message: string;
  downloadUrl: string | null;
  fileName: string | null;
  sourceCount: number;
  sourceRowCount: number;
  resultRowCount: number;
  removedDuplicateCount: number;
  outputColumns: string[];
};

const ACCEPTED_MIME_TYPES = [
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/vnd.ms-excel",
];

const SmartMergeExcel = ({ onError }: BaseToolProps) => {
  const { t } = useTranslation();
  const [files, setFiles] = useState<MergeFileInfo[]>([]);
  const [selectedSources, setSelectedSources] = useState<string[]>([]);
  const [requirement, setRequirement] = useState("");
  const [rules, setRules] = useState<MergeRules | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [result, setResult] = useState<GenerateResponse | null>(null);
  const [statusMessage, setStatusMessage] = useState(
    t("smartMergeExcel.status.waiting", "Waiting for Excel files"),
  );
  const [isUploading, setIsUploading] = useState(false);
  const [isParsing, setIsParsing] = useState(false);
  const [isGenerating, setIsGenerating] = useState(false);

  const sources = useMemo(
    () =>
      files.flatMap((file) =>
        file.sheets.map((sheet) => ({
          fileToken: file.fileToken,
          fileName: file.fileName,
          sheetName: sheet.sheetName,
          headers: sheet.headers,
          previewData: sheet.previewData,
        })),
      ),
    [files],
  );

  const chosenSources = sources.filter((source) =>
    selectedSources.includes(sourceKey(source)),
  );

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

  const handleFiles = async (acceptedFiles: File[]) => {
    if (!acceptedFiles.length) {
      return;
    }
    setIsUploading(true);
    setResult(null);
    setRules(null);
    setWarnings([]);
    setStatusMessage(t("smartMergeExcel.status.uploading", "Uploading and parsing files..."));

    const formData = new FormData();
    acceptedFiles.forEach((file) => formData.append("files", file));

    try {
      const response = await apiClient.post<UploadResponse>(
        "/api/excel/merge/upload",
        formData,
        { headers: { "Content-Type": "multipart/form-data" } },
      );
      if (!response.data.success) {
        setStatusMessage(response.data.message);
        return;
      }
      setFiles(response.data.files);
      const nextSources = response.data.files.flatMap((file) =>
        file.sheets.map((sheet) =>
          sourceKey({ fileToken: file.fileToken, sheetName: sheet.sheetName }),
        ),
      );
      setSelectedSources(nextSources);
      setStatusMessage(response.data.message);
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : t("smartMergeExcel.status.failed", "The request failed.");
      setStatusMessage(message);
      onError?.(message);
    } finally {
      setIsUploading(false);
    }
  };

  const toggleSource = (source: MergeSource, checked: boolean) => {
    const key = sourceKey(source);
    setSelectedSources((current) =>
      checked ? [...new Set([...current, key])] : current.filter((item) => item !== key),
    );
  };

  const handleAiParse = async () => {
    if (!chosenSources.length) {
      setStatusMessage(t("smartMergeExcel.status.noSource", "Please select data sources first."));
      return;
    }
    if (!requirement.trim()) {
      setStatusMessage(
        t("smartMergeExcel.status.noRequirement", "Please enter merge requirement first."),
      );
      return;
    }

    setIsParsing(true);
    setRules(null);
    setWarnings([]);
    setStatusMessage(t("smartMergeExcel.status.parsing", "AI is analyzing merge plan..."));
    try {
      const response = await apiClient.post<AiParseResponse>("/api/excel/merge/ai-parse", {
        sources: chosenSources,
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
          : t("smartMergeExcel.status.failed", "The request failed.");
      setStatusMessage(message);
      onError?.(message);
    } finally {
      setIsParsing(false);
    }
  };

  const handleGenerate = async () => {
    if (!rules) {
      setStatusMessage(t("smartMergeExcel.status.noRules", "Please analyze or confirm merge rules first."));
      return;
    }
    setIsGenerating(true);
    setResult(null);
    setStatusMessage(t("smartMergeExcel.status.generating", "Merging tables..."));
    try {
      const response = await apiClient.post<GenerateResponse>("/api/excel/merge/generate", {
        sources: chosenSources,
        rules,
      });
      setResult(response.data);
      setStatusMessage(response.data.message);
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : t("smartMergeExcel.status.failed", "The request failed.");
      setStatusMessage(message);
      onError?.(message);
    } finally {
      setIsGenerating(false);
    }
  };

  return (
    <ExcelToolShell
      title={t("smartMergeExcel.title", "Smart Table Merge")}
      description={t(
        "smartMergeExcel.description",
        "Upload multiple Excel files or choose multiple sheets, describe how to merge them, and generate a new Excel file.",
      )}
      badges={["AI", "Field mapping", "Multi-file", "XLSX"]}
      steps={[
        {
          label: t("excelCommon.steps.upload", "Upload file"),
          state: files.length ? "done" : "active",
        },
        {
          label: t("excelCommon.steps.selectData", "Select data"),
          state: files.length ? (chosenSources.length ? "done" : "active") : "idle",
        },
        {
          label: t("excelCommon.steps.requirement", "Set requirement"),
          state: isParsing ? "active" : rules ? "done" : files.length ? "active" : "idle",
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
        multiple
        maxFiles={20}
        onDrop={handleFiles}
        onReject={() =>
          setStatusMessage(
            t("smartMergeExcel.status.unsupported", "Please upload .xlsx or .xls files."),
          )
        }
        title={t("smartMergeExcel.upload.title", "Drag Excel files here, or click to choose")}
        hint={
          isUploading
            ? t("smartMergeExcel.status.uploading", "Uploading and parsing files...")
            : t("smartMergeExcel.upload.hint", "Up to 20 Excel files")
        }
        files={files.map((file) => ({
          name: file.fileName,
          size: file.fileSize,
          type: "Excel",
          status: t("excelCommon.upload.parsed", "Parsed"),
        }))}
      />

      <ExcelStatusMessage
        message={statusMessage}
        tone={result?.success ? "success" : warnings.length ? "warning" : "info"}
      />

      {files.length ? (
        <Paper withBorder radius="md" p="md">
          <Stack gap="sm">
            <Title order={3} size="h4">
              {t("smartMergeExcel.sources.title", "Data sources")}
            </Title>
            <Table striped withTableBorder>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("smartMergeExcel.sources.use", "Use")}</Table.Th>
                  <Table.Th>{t("smartMergeExcel.sources.file", "File")}</Table.Th>
                  <Table.Th>{t("smartMergeExcel.sources.sheet", "Sheet")}</Table.Th>
                  <Table.Th>{t("smartMergeExcel.sources.rows", "Rows")}</Table.Th>
                  <Table.Th>{t("smartMergeExcel.sources.headers", "Headers")}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {files.flatMap((file) =>
                  file.sheets.map((sheet) => {
                    const source = {
                      fileToken: file.fileToken,
                      fileName: file.fileName,
                      sheetName: sheet.sheetName,
                      headers: sheet.headers,
                      previewData: sheet.previewData,
                    };
                    return (
                      <Table.Tr key={sourceKey(source)}>
                        <Table.Td>
                          <Checkbox
                            checked={selectedSources.includes(sourceKey(source))}
                            onChange={(event) =>
                              toggleSource(source, event.currentTarget.checked)
                            }
                          />
                        </Table.Td>
                        <Table.Td>{file.fileName}</Table.Td>
                        <Table.Td>{sheet.sheetName}</Table.Td>
                        <Table.Td>{sheet.rowCount}</Table.Td>
                        <Table.Td>{sheet.headers.join(", ")}</Table.Td>
                      </Table.Tr>
                    );
                  }),
                )}
              </Table.Tbody>
            </Table>
          </Stack>
        </Paper>
      ) : null}

      {files.length ? (
        <ExcelAiRequirementCard
          title={t("excelCommon.aiRequirement.title", "Describe your requirement")}
          description={t(
            "excelCommon.aiRequirement.description",
            "Use one sentence to describe how you want to process the current Excel data.",
          )}
          value={requirement}
          onChange={setRequirement}
          placeholder={t(
            "smartMergeExcel.requirement.placeholder",
            "Example: merge all reimbursement tables into one sheet, map reimbursement amount and expense amount to amount, keep name, department, amount and date, and deduplicate by name plus date.",
          )}
          examples={[
            t(
              "smartMergeExcel.examples.append",
              "Merge all reimbursement tables into one summary sheet.",
            ),
            t(
              "smartMergeExcel.examples.mapFields",
              "Unify amount and date fields, then deduplicate by name plus date.",
            ),
            t(
              "smartMergeExcel.examples.multiSheet",
              "Append all sheets with the same structure into one workbook.",
            ),
          ]}
          buttonLabel={t("excelCommon.buttons.aiAnalyze", "AI analyze requirement")}
          loading={isParsing}
          disabled={!chosenSources.length}
          onAnalyze={handleAiParse}
        />
      ) : null}

      {rules ? (
        <Paper withBorder radius="md" p="md">
          <Stack gap="sm">
            <Title order={3} size="h4">
              {t("excelCommon.aiResult.title", "AI understanding result")}
            </Title>
            <Text size="sm">
              {t("smartMergeExcel.rules.mergeType", "Merge type")}: {rules.mergeType}
            </Text>
            <Text size="sm">
              {t("smartMergeExcel.rules.columns", "Output columns")}:{" "}
              {(rules.selectedColumns ?? []).join(", ") || "-"}
            </Text>
            <Text size="sm">
              {t("smartMergeExcel.rules.deduplicate", "Deduplicate")}:{" "}
              {rules.deduplicate
                ? (rules.deduplicateColumns ?? []).join(" + ") ||
                  t("smartMergeExcel.rules.fullRow", "Full row")
                : "-"}
            </Text>
            {rules.fieldMappings?.length ? (
              <Table striped withTableBorder withColumnBorders>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t("smartMergeExcel.rules.target", "Target field")}</Table.Th>
                    <Table.Th>{t("smartMergeExcel.rules.sources", "Source fields")}</Table.Th>
                    <Table.Th>{t("smartMergeExcel.rules.confidence", "Confidence")}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rules.fieldMappings.map((mapping) => (
                    <Table.Tr key={mapping.targetField}>
                      <Table.Td>{mapping.targetField}</Table.Td>
                      <Table.Td>{mapping.sourceFields.join(", ")}</Table.Td>
                      <Table.Td>
                        {mapping.confidence == null
                          ? "-"
                          : `${Math.round(mapping.confidence * 100)}%`}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
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
              <Button onClick={handleGenerate} loading={isGenerating}>
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
            { label: t("smartMergeExcel.result.sourceCount", "Sources"), value: result.sourceCount },
            {
              label: t("smartMergeExcel.result.sourceRows", "Source rows"),
              value: result.sourceRowCount,
            },
            {
              label: t("smartMergeExcel.result.resultRows", "Result rows"),
              value: result.resultRowCount,
            },
            {
              label: t("smartMergeExcel.result.duplicates", "Removed duplicates"),
              value: result.removedDuplicateCount,
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
        >
          <Text size="sm">
            {t("smartMergeExcel.result.columns", "Output columns")}:{" "}
            {result.outputColumns.join(", ")}
          </Text>
        </ExcelResultCard>
      ) : null}
    </ExcelToolShell>
  );
};

const sourceKey = (source: Pick<MergeSource, "fileToken" | "sheetName">) =>
  `${source.fileToken}::${source.sheetName}`;

export default SmartMergeExcel as ToolComponent;
