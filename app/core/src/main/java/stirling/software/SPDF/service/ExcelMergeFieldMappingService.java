package stirling.software.SPDF.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import stirling.software.SPDF.service.ExcelMergeService.MergeFieldMapping;
import stirling.software.SPDF.service.ExcelMergeService.MergeSourceData;

@Service
public class ExcelMergeFieldMappingService {

    public List<String> outputColumns(ExcelMergeService.MergeRules rules) {
        if (rules.selectedColumns() != null && !rules.selectedColumns().isEmpty()) {
            return rules.selectedColumns();
        }
        if (rules.fieldMappings() != null && !rules.fieldMappings().isEmpty()) {
            return rules.fieldMappings().stream().map(MergeFieldMapping::targetField).toList();
        }
        return List.of();
    }

    public Map<String, String> normalizeRow(
            MergeSourceData source, Map<String, String> row, ExcelMergeService.MergeRules rules) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String outputColumn : outputColumns(rules)) {
            normalized.put(
                    outputColumn, valueFor(outputColumn, source, row, rules.fieldMappings()));
        }
        return normalized;
    }

    private String valueFor(
            String outputColumn,
            MergeSourceData source,
            Map<String, String> row,
            List<MergeFieldMapping> mappings) {
        MergeFieldMapping mapping = mappingFor(outputColumn, mappings);
        List<String> candidateFields = new ArrayList<>();
        if (mapping != null && mapping.sourceFields() != null) {
            candidateFields.addAll(mapping.sourceFields());
        }
        candidateFields.add(outputColumn);

        for (String candidate : candidateFields) {
            if (source.headers().contains(candidate)) {
                return row.getOrDefault(candidate, "");
            }
        }
        return "";
    }

    private MergeFieldMapping mappingFor(String outputColumn, List<MergeFieldMapping> mappings) {
        if (mappings == null) {
            return null;
        }
        for (MergeFieldMapping mapping : mappings) {
            if (outputColumn.equals(mapping.targetField())) {
                return mapping;
            }
        }
        return null;
    }
}
