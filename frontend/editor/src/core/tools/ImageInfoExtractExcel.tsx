import { useMemo, useState } from "react";
import {
  Button,
  Group,
  Image,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Text,
  Textarea,
  Title,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import AutoAwesomeRoundedIcon from "@mui/icons-material/AutoAwesomeRounded";
import DownloadRoundedIcon from "@mui/icons-material/DownloadRounded";
import RestartAltRoundedIcon from "@mui/icons-material/RestartAltRounded";
import {
  ExcelDataPreview,
  ExcelResultCard,
  ExcelStatusMessage,
  ExcelToolShell,
  ExcelUploadZone,
} from "@app/components/excel/ExcelToolShell";
import apiClient from "@app/services/apiClient";
import { BaseToolProps, ToolComponent } from "@app/types/tool";

type ImageInfoExtractResponse = {
  success: boolean;
  message: string;
  extractType?: string | null;
  ocrText?: string | null;
  previewData: string[][];
  downloadUrl?: string | null;
  fileName?: string | null;
  warnings: string[];
};

const ACCEPTED_IMAGE_TYPES = ["image/png", "image/jpeg", "image/webp"];

const extractTypes = [
  { value: "auto", label: "自动识别" },
  { value: "table", label: "表格/清单" },
  { value: "invoice", label: "发票/收据" },
  { value: "id_card", label: "身份证/证件" },
  { value: "business_license", label: "营业执照" },
  { value: "express", label: "快递单" },
  { value: "contract", label: "合同/协议" },
  { value: "custom", label: "自定义字段" },
];

const ImageInfoExtractExcel = ({ onError }: BaseToolProps) => {
  const { t } = useTranslation();
  const [files, setFiles] = useState<File[]>([]);
  const [extractType, setExtractType] = useState("auto");
  const [customFields, setCustomFields] = useState("");
  const [statusMessage, setStatusMessage] = useState("等待上传图片");
  const [tone, setTone] = useState<"info" | "success" | "warning" | "error">("info");
  const [isProcessing, setIsProcessing] = useState(false);
  const [result, setResult] = useState<ImageInfoExtractResponse | null>(null);

  const imagePreviews = useMemo(
    () =>
      files.map((file) => ({
        name: file.name,
        url: URL.createObjectURL(file),
      })),
    [files],
  );

  const previewData = result?.previewData ?? [];
  const headers = previewData[0] ?? [];
  const rows = previewData.slice(1);
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

  const handleFiles = (nextFiles: File[]) => {
    setFiles(nextFiles);
    setResult(null);
    setTone("info");
    setStatusMessage(
      nextFiles.length
        ? `已选择 ${nextFiles.length} 张图片，准备开始识别。`
        : "等待上传图片",
    );
  };

  const handleReset = () => {
    setFiles([]);
    setResult(null);
    setStatusMessage("等待上传图片");
    setTone("info");
  };

  const handleSubmit = async () => {
    if (!files.length) {
      setStatusMessage("请先选择图片。");
      setTone("warning");
      return;
    }
    if (extractType === "custom" && !customFields.trim()) {
      setStatusMessage("请输入要提取的自定义字段。");
      setTone("warning");
      return;
    }

    setIsProcessing(true);
    setTone("info");
    setStatusMessage("正在上传图片...");

    const formData = new FormData();
    files.forEach((file) => formData.append("files", file));
    formData.append("extractType", extractType);
    formData.append("customFields", customFields);

    try {
      setStatusMessage("正在OCR识别...");
      const response = await apiClient.post<ImageInfoExtractResponse>(
        "/api/excel/image-info-extract",
        formData,
        { headers: { "Content-Type": "multipart/form-data" } },
      );
      setStatusMessage(
        response.data.success ? "处理完成" : response.data.message || "图片信息提取失败",
      );
      setTone(response.data.success ? "success" : "error");
      setResult(response.data);
    } catch (error) {
      setStatusMessage("图片信息提取失败，请检查 OCR 服务和 AI 配置。");
      setTone("error");
      onError?.(error);
    } finally {
      setIsProcessing(false);
    }
  };

  return (
    <ExcelToolShell
      title={t("home.imageInfoExtractExcel.title", "图片信息提取表格")}
      description={t(
        "home.imageInfoExtractExcel.desc",
        "上传图片后，先OCR识别文字，再由AI整理成结构化表格并生成Excel。",
      )}
      badges={["OCR", "AI", "Excel"]}
      steps={[
        { label: "上传图片", state: files.length ? "done" : "active" },
        { label: "OCR识别", state: isProcessing ? "active" : result ? "done" : "idle" },
        { label: "AI提取字段", state: isProcessing ? "active" : result ? "done" : "idle" },
        { label: "生成Excel", state: result?.success ? "done" : "idle" },
      ]}
    >
      <Stack gap="md">
        <ExcelUploadZone
          accept={ACCEPTED_IMAGE_TYPES}
          multiple
          maxFiles={10}
          disabled={isProcessing}
          title="拖拽图片到这里，或点击选择图片"
          hint="支持 PNG、JPG、JPEG、WEBP；一次最多 10 张。"
          files={files.map((file) => ({
            name: file.name,
            size: file.size,
            type: file.type || "image",
          }))}
          onDrop={handleFiles}
          onReject={() => {
            setStatusMessage("请上传 PNG、JPG、JPEG 或 WEBP 图片。");
            setTone("warning");
          }}
        />

        <Paper withBorder radius="md" p="md">
          <Stack gap="sm">
            <Title order={3} size="h4">
              提取设置
            </Title>
            <Select
              label="提取类型"
              data={extractTypes}
              value={extractType}
              onChange={(value) => setExtractType(value ?? "auto")}
              disabled={isProcessing}
            />
            {extractType === "custom" ? (
              <Textarea
                label="自定义字段"
                minRows={3}
                value={customFields}
                onChange={(event) => setCustomFields(event.currentTarget.value)}
                placeholder="例如：姓名、电话、地址、金额、日期、备注"
                disabled={isProcessing}
              />
            ) : null}
            <Group justify="flex-end">
              <Button
                variant="light"
                leftSection={<RestartAltRoundedIcon fontSize="small" />}
                onClick={handleReset}
                disabled={isProcessing}
              >
                重新上传
              </Button>
              <Button
                leftSection={<AutoAwesomeRoundedIcon fontSize="small" />}
                loading={isProcessing}
                onClick={handleSubmit}
              >
                开始提取
              </Button>
            </Group>
          </Stack>
        </Paper>

        <ExcelStatusMessage message={statusMessage} tone={tone} />

        {imagePreviews.length ? (
          <Paper withBorder radius="md" p="md">
            <Stack gap="sm">
              <Title order={3} size="h4">
                图片预览
              </Title>
              <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }} spacing="sm">
                {imagePreviews.map((preview) => (
                  <Paper key={preview.name} withBorder radius="sm" p="xs">
                    <Stack gap="xs">
                      <Image src={preview.url} alt={preview.name} radius="sm" h={160} fit="contain" />
                      <Text size="xs" truncate>
                        {preview.name}
                      </Text>
                    </Stack>
                  </Paper>
                ))}
              </SimpleGrid>
            </Stack>
          </Paper>
        ) : null}

        {result ? (
          <ExcelResultCard
            title="AI提取结果"
            stats={[
              { label: "状态", value: result.success ? "成功" : "失败" },
              { label: "类型", value: result.extractType || extractType },
              { label: "字段数", value: headers.length },
              { label: "数据行", value: rows.length },
            ]}
            action={
              <Button
                component="a"
                href={downloadHref}
                leftSection={<DownloadRoundedIcon fontSize="small" />}
                disabled={!downloadHref}
              >
                下载Excel
              </Button>
            }
          >
            {result.warnings?.length ? (
              <ExcelStatusMessage message={result.warnings.join("；")} tone="warning" />
            ) : null}
          </ExcelResultCard>
        ) : null}

        {result?.ocrText ? (
          <Paper withBorder radius="md" p="md">
            <Stack gap="sm">
              <Title order={3} size="h4">
                原始OCR文本
              </Title>
              <Textarea value={result.ocrText} readOnly minRows={6} autosize maxRows={12} />
            </Stack>
          </Paper>
        ) : null}

        <ExcelDataPreview
          title="表格预览"
          sheetName="图片信息提取"
          totalRows={rows.length}
          columnCount={headers.length}
          headers={headers}
          rows={rows}
          emptyText="识别完成后会在这里显示结构化表格。"
        />
      </Stack>
    </ExcelToolShell>
  );
};

export default ImageInfoExtractExcel as ToolComponent;
