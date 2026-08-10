import { Badge, Button, Group, Paper, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import TableChartRoundedIcon from "@mui/icons-material/TableChartRounded";
import { useTranslation } from "react-i18next";
import { ExcelToolShell } from "@app/components/excel/ExcelToolShell";
import { BaseToolProps, ToolComponent } from "@app/types/tool";

const ExcelToolsHome = (_props: BaseToolProps) => {
  const { t } = useTranslation();
  const tools = [
    {
      title: t("home.tableExtractExcel.title", "Extract Table to Excel"),
      description: t(
        "excelToolsHome.tableExtract.desc",
        "Recognize tables from PDFs, screenshots, and scanned files, then generate Excel.",
      ),
      tags: ["PDF", "OCR", "Excel"],
      href: "/table-extract-excel",
    },
    {
      title: t("home.smartExtractExcel.title", "Smart Extract & Regenerate"),
      description: t(
        "excelToolsHome.smartExtract.desc",
        "Filter data by fields, conditions, and natural language requirements, then regenerate Excel.",
      ),
      tags: ["AI filter", "Field match", "Summary"],
      href: "/smart-extract-excel",
    },
    {
      title: t("excelToolsHome.summary.title", "AI Summary Statistics"),
      description: t(
        "excelToolsHome.summary.desc",
        "Summarize amount, count, average, max and min by department, project, or person.",
      ),
      tags: ["Summary", "Metrics", "AI"],
      href: "/smart-extract-excel",
    },
    {
      title: t("home.smartMergeExcel.title", "Smart Table Merge"),
      description: t(
        "excelToolsHome.smartMerge.desc",
        "Merge multiple Excel files or sheets and automatically unify fields.",
      ),
      tags: ["Multi-file", "Join", "Append"],
      href: "/smart-merge-excel",
    },
    {
      title: t("home.smartCleanExcel.title", "Smart Data Clean"),
      description: t(
        "excelToolsHome.smartClean.desc",
        "Detect and clean duplicates, blanks, dates, amount formats, and other data issues.",
      ),
      tags: ["Clean", "Deduplicate", "Normalize"],
      href: "/smart-clean-excel",
    },
    {
      title: t("home.imageInfoExtractExcel.title", "图片信息提取表格"),
      description: t(
        "excelToolsHome.imageInfoExtract.desc",
        "OCR text from invoices, licenses, express sheets, contracts, and images, then export structured Excel.",
      ),
      tags: ["OCR", "AI extract", "Images"],
      href: "/image-info-extract-excel",
    },
  ];

  return (
    <ExcelToolShell
      title={t("excelToolsHome.title", "Excel Tools")}
      description={t(
        "excelToolsHome.description",
        "Choose an Excel workflow for extraction, filtering, summary, merge, or data cleaning.",
      )}
      badges={["Excel", "AI assisted", "Local execution"]}
      steps={[
        { label: t("excelToolsHome.steps.choose", "Choose tool"), state: "active" },
        { label: t("excelToolsHome.steps.upload", "Upload data"), state: "idle" },
        { label: t("excelToolsHome.steps.process", "Process"), state: "idle" },
        { label: t("excelToolsHome.steps.download", "Download Excel"), state: "idle" },
      ]}
    >
      <SimpleGrid cols={1} spacing="md">
        {tools.map((tool) => (
          <Paper key={tool.title} withBorder radius="md" p="md">
            <Stack gap="sm" h="100%">
              <Group gap="sm" align="center">
                <TableChartRoundedIcon style={{ fontSize: 22 }} />
                <Title order={3} size="h4">
                  {tool.title}
                </Title>
              </Group>
              <Text c="dimmed" size="sm" style={{ flex: 1 }}>
                {tool.description}
              </Text>
              <Group gap="xs">
                {tool.tags.map((tag) => (
                  <Badge key={tag} variant="light" size="sm">
                    {tag}
                  </Badge>
                ))}
              </Group>
              <Button component="a" href={tool.href} variant="light">
                {t("excelToolsHome.openButton", "Open tool")}
              </Button>
            </Stack>
          </Paper>
        ))}
      </SimpleGrid>
    </ExcelToolShell>
  );
};

export default ExcelToolsHome as ToolComponent;
