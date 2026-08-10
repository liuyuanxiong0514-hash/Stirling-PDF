import { useMemo, useState } from "react";
import {
  Button,
  Group,
  Text,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import FileUploadRoundedIcon from "@mui/icons-material/FileUploadRounded";
import DownloadRoundedIcon from "@mui/icons-material/DownloadRounded";
import {
  ExcelDataPreview,
  ExcelStatusMessage,
  ExcelToolShell,
  ExcelUploadZone,
} from "@app/components/excel/ExcelToolShell";
import apiClient from "@app/services/apiClient";
import { BaseToolProps, ToolComponent } from "@app/types/tool";

type ExtractTableResponse = {
  success: boolean;
  message: string;
  previewData: string[][];
  downloadUrl: string | null;
  fileName?: string | null;
  quality?: string | null;
};

const ACCEPTED_MIME_TYPES = [
  "image/png",
  "image/jpeg",
  "application/pdf",
];

const TableExtractExcel = ({ onError }: BaseToolProps) => {
  const { t } = useTranslation();
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [isProcessing, setIsProcessing] = useState(false);
  const [result, setResult] = useState<ExtractTableResponse | null>(null);
  const [statusMessage, setStatusMessage] = useState<string>(
    t("tableExtractExcel.status.waiting", "Waiting for a file"),
  );

  const hasPreviewRows = (result?.previewData?.length ?? 0) > 0;
  const previewData = result?.previewData ?? [];
  const previewHeaders = previewData[0] ?? [];
  const previewRows = previewData.slice(1);
  const downloadHref = useMemo(() => {
    if (!result?.downloadUrl) {
      return undefined;
    }

    if (/^https?:\/\//i.test(result.downloadUrl)) {
      return result.downloadUrl;
    }

    const baseUrl = apiClient.defaults.baseURL;
    if (typeof baseUrl === "string" && /^https?:\/\//i.test(baseUrl)) {
      return new URL(result.downloadUrl, baseUrl).toString();
    }

    return result.downloadUrl;
  }, [result?.downloadUrl]);
  const isPdfFile = (file: File) =>
    file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf");

  const handleFiles = (files: File[]) => {
    const nextFile = files[0] ?? null;
    setSelectedFile(nextFile);
    setResult(null);
    setStatusMessage(
      nextFile
        ? t("tableExtractExcel.status.ready", "File selected. Ready to upload.")
        : t("tableExtractExcel.status.waiting", "Waiting for a file"),
    );
  };

  const handleReset = () => {
    setSelectedFile(null);
    setResult(null);
    setStatusMessage(t("tableExtractExcel.status.waiting", "Waiting for a file"));
  };

  const handleSubmit = async () => {
    if (!selectedFile) {
      setStatusMessage(
        t("tableExtractExcel.status.noFile", "Please select a file first."),
      );
      return;
    }

    setIsProcessing(true);
    setStatusMessage(
      isPdfFile(selectedFile)
        ? t(
            "tableExtractExcel.status.detectingType",
            "Detecting PDF type and extracting tables...",
          )
        : t(
            "tableExtractExcel.status.ocrProcessing",
            "Running OCR table recognition...",
          ),
    );

    const formData = new FormData();
    formData.append("file", selectedFile);

    try {
      const response = await apiClient.post<ExtractTableResponse>(
        "/api/excel/extract-table",
        formData,
        {
          headers: {
            "Content-Type": "multipart/form-data",
          },
        },
      );
      setResult(response.data);
      setStatusMessage(
        response.data.success
          ? t("tableExtractExcel.status.completed", "Extraction completed.")
          : response.data.message,
      );
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : t("tableExtractExcel.status.failed", "The test request failed.");
      setStatusMessage(message);
      onError?.(message);
    } finally {
      setIsProcessing(false);
    }
  };

  return (
    <ExcelToolShell
      title={t("tableExtractExcel.title", "Extract Table to Excel")}
      description={t(
        "tableExtractExcel.description",
        "Upload a screenshot, image, or PDF. This tool detects tables and exports them to Excel.",
      )}
      badges={["PDF", "OCR", "PNG", "JPG", "Excel"]}
      steps={[
        {
          label: t("excelCommon.steps.upload", "Upload file"),
          state: selectedFile ? "done" : "active",
        },
        {
          label: t("excelCommon.steps.recognize", "Recognize table"),
          state: isProcessing ? "active" : result ? (result.success ? "done" : "error") : "idle",
        },
        {
          label: t("excelCommon.steps.confirm", "Confirm result"),
          state: result ? (result.success ? "done" : "error") : "idle",
        },
        {
          label: t("excelCommon.steps.download", "Download Excel"),
          state: downloadHref ? "active" : "idle",
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
              "tableExtractExcel.status.unsupported",
              "Please choose a PNG, JPG, JPEG, or PDF file.",
            ),
          )
        }
        title={t("tableExtractExcel.upload.title", "Drag a file here, or click to choose")}
        hint={t("tableExtractExcel.upload.hint", "PNG, JPG, JPEG or PDF")}
        files={
          selectedFile
            ? [
                {
                  name: selectedFile.name,
                  size: selectedFile.size,
                  type: selectedFile.type || selectedFile.name.split(".").pop(),
                  status: result
                    ? result.success
                      ? t("excelCommon.upload.done", "Parsed")
                      : t("excelCommon.upload.failed", "Failed")
                    : selectedFile
                      ? t("excelCommon.upload.ready", "Ready")
                      : undefined,
                },
              ]
            : []
        }
      />

      <Group justify="space-between" align="center">
        <Button
          leftSection={<FileUploadRoundedIcon fontSize="small" />}
          onClick={handleSubmit}
          loading={isProcessing}
          disabled={!selectedFile}
        >
          {t("tableExtractExcel.uploadButton", "Upload")}
        </Button>
        <Button variant="subtle" onClick={handleReset}>
          {t("tableExtractExcel.reuploadButton", "Upload again")}
        </Button>
      </Group>

      <ExcelStatusMessage
        message={statusMessage}
        tone={result ? (result.success ? "success" : "warning") : "info"}
      />

      <Text c="dimmed" size="sm">
        {t(
          "tableExtractExcel.testVersionNotice",
          "This version supports text-based PDF extraction, screenshot table recognition, image table recognition, and scanned PDF table recognition.",
        )}
      </Text>

      <ExcelDataPreview
        title={t("tableExtractExcel.preview.title", "Result preview")}
        headers={previewHeaders}
        rows={previewRows}
        totalRows={hasPreviewRows ? previewRows.length : undefined}
        columnCount={previewHeaders.length || undefined}
        emptyText={t(
          "tableExtractExcel.preview.empty",
          "Upload a file to show extracted table data here.",
        )}
        action={
          <Button
            leftSection={<DownloadRoundedIcon fontSize="small" />}
            disabled={!downloadHref}
            component={downloadHref ? "a" : "button"}
            href={downloadHref}
            download={result?.fileName ?? "table_extract_result.xlsx"}
          >
            {t("tableExtractExcel.downloadButton", "Download Excel")}
          </Button>
        }
      />
      {result?.quality ? (
        <Text size="sm" c="dimmed">
          {t("tableExtractExcel.qualityLabel", "Recognition quality")}: {result.quality}
        </Text>
      ) : null}
    </ExcelToolShell>
  );
};

export default TableExtractExcel as ToolComponent;
