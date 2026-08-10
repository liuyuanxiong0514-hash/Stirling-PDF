import {
  Alert,
  Badge,
  Button,
  Group,
  Paper,
  ScrollArea,
  Stack,
  Table,
  Text,
  Textarea,
  Title,
  Tooltip,
} from "@mantine/core";
import { Dropzone } from "@mantine/dropzone";
import type { ReactNode } from "react";
import CheckRoundedIcon from "@mui/icons-material/CheckRounded";
import ErrorOutlineRoundedIcon from "@mui/icons-material/ErrorOutlineRounded";
import FileUploadRoundedIcon from "@mui/icons-material/FileUploadRounded";
import RadioButtonUncheckedRoundedIcon from "@mui/icons-material/RadioButtonUncheckedRounded";

export type ExcelStepState = "idle" | "active" | "done" | "error";

export type ExcelStep = {
  label: string;
  state: ExcelStepState;
};

type ExcelToolShellProps = {
  title: string;
  description: string;
  badges?: string[];
  steps: ExcelStep[];
  children: ReactNode;
};

type ExcelUploadZoneProps = {
  accept: string[];
  title: string;
  hint: string;
  files?: FileSummary[];
  disabled?: boolean;
  multiple?: boolean;
  maxFiles?: number;
  onDrop: (files: File[]) => void;
  onReject: () => void;
};

type FileSummary = {
  name: string;
  size?: number;
  type?: string;
  status?: string;
};

type ExcelStatusMessageProps = {
  message: string;
  tone?: "info" | "success" | "warning" | "error";
};

type ExcelDataPreviewProps = {
  title: string;
  sheetName?: string;
  totalRows?: number;
  columnCount?: number;
  headers: string[];
  rows: string[][];
  emptyText: string;
  action?: ReactNode;
};

type ExcelAiRequirementCardProps = {
  title: string;
  description: string;
  value: string;
  placeholder: string;
  buttonLabel: string;
  examples?: string[];
  loading?: boolean;
  disabled?: boolean;
  status?: ReactNode;
  onChange: (value: string) => void;
  onAnalyze: () => void;
};

type ExcelResultCardProps = {
  title: string;
  stats: Array<{ label: string; value: ReactNode }>;
  action?: ReactNode;
  children?: ReactNode;
};

export const ExcelToolShell = ({
  title,
  description,
  badges = [],
  steps,
  children,
}: ExcelToolShellProps) => (
  <Stack gap="md" p="md" maw={1280} mx="auto" w="100%">
    <Paper withBorder radius="md" p="lg">
      <Stack gap="md">
        <Group justify="space-between" align="flex-start">
          <Stack gap={6} maw={820}>
            <Title order={2} size="h3">
              {title}
            </Title>
            <Text c="dimmed" size="sm">
              {description}
            </Text>
          </Stack>
          <Group gap="xs" justify="flex-end">
            {badges.map((badge) => (
              <Badge key={badge} variant="light" size="sm">
                {badge}
              </Badge>
            ))}
          </Group>
        </Group>
        <ExcelStepper steps={steps} />
      </Stack>
    </Paper>
    {children}
  </Stack>
);

export const ExcelStepper = ({ steps }: { steps: ExcelStep[] }) => (
  <ScrollArea type="auto" offsetScrollbars>
    <Group gap="xs" wrap="nowrap" miw="max-content">
      {steps.map((step, index) => (
        <Group key={`${step.label}-${index}`} gap="xs" wrap="nowrap">
          <Badge
            color={stepColor(step.state)}
            variant={step.state === "active" ? "filled" : "light"}
            leftSection={stepIcon(step.state)}
            size="lg"
          >
            {index + 1}. {step.label}
          </Badge>
          {index < steps.length - 1 ? (
            <Text c="dimmed" size="sm">
              /
            </Text>
          ) : null}
        </Group>
      ))}
    </Group>
  </ScrollArea>
);

export const ExcelUploadZone = ({
  accept,
  title,
  hint,
  files = [],
  disabled,
  multiple,
  maxFiles,
  onDrop,
  onReject,
}: ExcelUploadZoneProps) => (
  <Paper withBorder radius="md" p="md">
    <Stack gap="sm">
      <Dropzone
        accept={accept}
        disabled={disabled}
        maxFiles={maxFiles}
        multiple={multiple}
        onDrop={onDrop}
        onReject={onReject}
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
          <Text fw={600}>{title}</Text>
          <Text c="dimmed" size="sm">
            {hint}
          </Text>
        </Stack>
      </Dropzone>

      {files.length ? (
        <Stack gap="xs">
          {files.map((file) => (
            <Paper key={file.name} withBorder radius="sm" p="sm">
              <Group justify="space-between" gap="sm">
                <Stack gap={2}>
                  <Text fw={600} size="sm">
                    {file.name}
                  </Text>
                  <Text c="dimmed" size="xs">
                    {[file.type, file.size == null ? null : formatFileSize(file.size)]
                      .filter(Boolean)
                      .join(" · ")}
                  </Text>
                </Stack>
                {file.status ? (
                  <Badge variant="light" color="blue">
                    {file.status}
                  </Badge>
                ) : null}
              </Group>
            </Paper>
          ))}
        </Stack>
      ) : null}
    </Stack>
  </Paper>
);

export const ExcelStatusMessage = ({
  message,
  tone = "info",
}: ExcelStatusMessageProps) => (
  <Alert color={statusColor(tone)} variant="light">
    {message}
  </Alert>
);

export const ExcelDataPreview = ({
  title,
  sheetName,
  totalRows,
  columnCount,
  headers,
  rows,
  emptyText,
  action,
}: ExcelDataPreviewProps) => (
  <Paper withBorder radius="md" p="md">
    <Stack gap="sm">
      <Group justify="space-between" align="flex-start">
        <Stack gap={4}>
          <Title order={3} size="h4">
            {title}
          </Title>
          <Group gap="xs">
            {sheetName ? <Badge variant="light">{sheetName}</Badge> : null}
            {totalRows != null ? (
              <Badge variant="light">{totalRows} rows</Badge>
            ) : null}
            {columnCount != null ? (
              <Badge variant="light">{columnCount} fields</Badge>
            ) : null}
            <Badge variant="light">preview {Math.min(rows.length, 20)} rows</Badge>
          </Group>
        </Stack>
        {action}
      </Group>
      {headers.length && rows.length ? (
        <ScrollArea type="auto" offsetScrollbars>
          <Table striped withTableBorder withColumnBorders miw={720}>
            <Table.Thead>
              <Table.Tr>
                {headers.map((header) => (
                  <Table.Th
                    key={header}
                    style={{
                      position: "sticky",
                      top: 0,
                      zIndex: 1,
                      background: "var(--bg-elevated)",
                    }}
                  >
                    {header}
                  </Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.slice(0, 20).map((row, rowIndex) => (
                <Table.Tr key={`preview-row-${rowIndex}`}>
                  {headers.map((header, cellIndex) => {
                    const value = row[cellIndex] ?? "";
                    return (
                      <Table.Td
                        key={`${header}-${rowIndex}`}
                        maw={220}
                        style={{
                          maxWidth: 220,
                          overflow: "hidden",
                          textOverflow: "ellipsis",
                          whiteSpace: "nowrap",
                          textAlign: isNumeric(value) ? "right" : "left",
                        }}
                      >
                        <Tooltip label={value || "Empty"} disabled={!value}>
                          <Text c={value ? undefined : "dimmed"} size="sm" truncate>
                            {value || "Empty"}
                          </Text>
                        </Tooltip>
                      </Table.Td>
                    );
                  })}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      ) : (
        <Text c="dimmed" size="sm">
          {emptyText}
        </Text>
      )}
    </Stack>
  </Paper>
);

export const ExcelAiRequirementCard = ({
  title,
  description,
  value,
  placeholder,
  buttonLabel,
  examples = [],
  loading,
  disabled,
  status,
  onChange,
  onAnalyze,
}: ExcelAiRequirementCardProps) => (
  <Paper withBorder radius="md" p="md">
    <Stack gap="sm">
      <Stack gap={4}>
        <Title order={3} size="h4">
          {title}
        </Title>
        <Text c="dimmed" size="sm">
          {description}
        </Text>
      </Stack>
      <Textarea
        minRows={3}
        value={value}
        onChange={(event) => onChange(event.currentTarget.value)}
        placeholder={placeholder}
      />
      {examples.length ? (
        <Group gap="xs">
          {examples.slice(0, 5).map((example) => (
            <Button
              key={example}
              variant="subtle"
              size="xs"
              onClick={() => onChange(example)}
            >
              {example}
            </Button>
          ))}
        </Group>
      ) : null}
      {status}
      <Group justify="flex-end">
        <Button variant="light" loading={loading} disabled={disabled} onClick={onAnalyze}>
          {buttonLabel}
        </Button>
      </Group>
    </Stack>
  </Paper>
);

export const ExcelResultCard = ({
  title,
  stats,
  action,
  children,
}: ExcelResultCardProps) => (
  <Paper withBorder radius="md" p="md">
    <Stack gap="sm">
      <Group justify="space-between" align="flex-start">
        <Title order={3} size="h4">
          {title}
        </Title>
        {action}
      </Group>
      <Group gap="lg">
        {stats.map((stat) => (
          <Stack key={stat.label} gap={2}>
            <Text c="dimmed" size="xs">
              {stat.label}
            </Text>
            <Text fw={600} size="sm">
              {stat.value}
            </Text>
          </Stack>
        ))}
      </Group>
      {children}
    </Stack>
  </Paper>
);

const stepColor = (state: ExcelStepState) => {
  if (state === "done") {
    return "green";
  }
  if (state === "active") {
    return "blue";
  }
  if (state === "error") {
    return "red";
  }
  return "gray";
};

const stepIcon = (state: ExcelStepState) => {
  if (state === "done") {
    return <CheckRoundedIcon style={{ fontSize: 14 }} />;
  }
  if (state === "error") {
    return <ErrorOutlineRoundedIcon style={{ fontSize: 14 }} />;
  }
  return <RadioButtonUncheckedRoundedIcon style={{ fontSize: 14 }} />;
};

const statusColor = (tone: ExcelStatusMessageProps["tone"]) => {
  if (tone === "success") {
    return "green";
  }
  if (tone === "warning") {
    return "yellow";
  }
  if (tone === "error") {
    return "red";
  }
  return "blue";
};

const formatFileSize = (size: number) => {
  if (size < 1024 * 1024) {
    return `${Math.max(1, Math.round(size / 1024))} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
};

const isNumeric = (value: string) => /^-?\d+(\.\d+)?$/.test(value.trim());
