import { useMemo, useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Group,
  Paper,
  Stack,
  Table,
  Text,
  Title,
} from "@mantine/core";
import { Dropzone } from "@mantine/dropzone";
import { useTranslation } from "react-i18next";
import FileUploadRoundedIcon from "@mui/icons-material/FileUploadRounded";
import DownloadRoundedIcon from "@mui/icons-material/DownloadRounded";
import apiClient from "@app/services/apiClient";
import { BaseToolProps, ToolComponent } from "@app/types/tool";

type ExtractTableResponse = {
  success: boolean;
  message: string;
  previewData: string[][];
  downloadUrl: string | null;
  fileName?: string | null;
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
  const fileLabel = useMemo(() => {
    if (!selectedFile) {
      return t("tableExtractExcel.upload.noFile", "No file selected");
    }
    return selectedFile.name;
  }, [selectedFile, t]);

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

  const handleSubmit = async () => {
    if (!selectedFile) {
      setStatusMessage(
        t("tableExtractExcel.status.noFile", "Please select a file first."),
      );
      return;
    }

    setIsProcessing(true);
    setStatusMessage(
      t("tableExtractExcel.status.processing", "Processing test request..."),
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
      setStatusMessage(response.data.message);
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
    <Stack gap="md" p="md">
      <Stack gap={4}>
        <Title order={2} size="h3">
          {t("tableExtractExcel.title", "Extract Table to Excel")}
        </Title>
        <Text c="dimmed" size="sm">
          {t(
            "tableExtractExcel.description",
            "Upload a screenshot, image, or PDF. Later this tool will automatically detect tables and export them to Excel.",
          )}
        </Text>
        <Group gap="xs" mt={4}>
          <Text size="xs" c="dimmed">
            {t("tableExtractExcel.supportedFormats", "Supported formats")}
          </Text>
          {["PNG", "JPG", "JPEG", "PDF"].map((format) => (
            <Badge key={format} variant="light" size="sm">
              {format}
            </Badge>
          ))}
        </Group>
      </Stack>

      <Dropzone
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
        p="lg"
        styles={{
          root: {
            borderStyle: "dashed",
            borderColor: "var(--border-color)",
            backgroundColor: "var(--bg-elevated)",
          },
        }}
      >
        <Stack align="center" gap="xs">
          <FileUploadRoundedIcon fontSize="large" />
          <Text fw={600}>
            {t(
              "tableExtractExcel.upload.title",
              "Drag a file here, or click to choose",
            )}
          </Text>
          <Text size="sm" c="dimmed">
            {fileLabel}
          </Text>
        </Stack>
      </Dropzone>

      <Group justify="space-between" align="center">
        <Button
          leftSection={<FileUploadRoundedIcon fontSize="small" />}
          onClick={handleSubmit}
          loading={isProcessing}
          disabled={!selectedFile}
        >
          {t("tableExtractExcel.uploadButton", "Upload")}
        </Button>
        <Text size="sm" c={result?.success ? "green" : "dimmed"}>
          {statusMessage}
        </Text>
      </Group>

      <Alert color="blue" variant="light">
        {t(
          "tableExtractExcel.testVersionNotice",
          "This version supports generating Excel files. Real PDF table extraction and screenshot OCR will be enabled in the next stage.",
        )}
      </Alert>

      <Paper withBorder radius="md" p="md">
        <Group justify="space-between" mb="sm">
          <Title order={3} size="h4">
            {t("tableExtractExcel.preview.title", "Result preview")}
          </Title>
          <Button
            leftSection={<DownloadRoundedIcon fontSize="small" />}
            disabled={!downloadHref}
            component={downloadHref ? "a" : "button"}
            href={downloadHref}
            download={result?.fileName ?? "table_extract_result.xlsx"}
          >
            {t("tableExtractExcel.downloadButton", "Download Excel")}
          </Button>
        </Group>

        {hasPreviewRows ? (
          <Table striped withTableBorder withColumnBorders>
            <Table.Tbody>
              {previewData.map((row, rowIndex) => (
                <Table.Tr key={`row-${rowIndex}`}>
                  {row.map((cell, cellIndex) =>
                    rowIndex === 0 ? (
                      <Table.Th key={`cell-${rowIndex}-${cellIndex}`}>
                        {cell}
                      </Table.Th>
                    ) : (
                      <Table.Td key={`cell-${rowIndex}-${cellIndex}`}>
                        {cell}
                      </Table.Td>
                    ),
                  )}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        ) : (
          <Text c="dimmed" size="sm">
            {t(
              "tableExtractExcel.preview.empty",
              "Upload a file to show mock preview data here.",
            )}
          </Text>
        )}
      </Paper>
    </Stack>
  );
};

export default TableExtractExcel as ToolComponent;
