package stirling.software.SPDF.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class ExcelFieldMatchService {

    private static final Map<String, List<String>> SYNONYMS =
            Map.of(
                    "\u5de5\u8d44",
                            List.of(
                                    "\u5de5\u8d44",
                                    "\u85aa\u8d44",
                                    "\u5b9e\u53d1\u5de5\u8d44",
                                    "\u5e94\u53d1\u5de5\u8d44",
                                    "\u53d1\u653e\u91d1\u989d",
                                    "\u5b9e\u53d1\u91d1\u989d"),
                    "\u91d1\u989d",
                            List.of(
                                    "\u91d1\u989d",
                                    "\u5408\u8ba1\u91d1\u989d",
                                    "\u62a5\u9500\u91d1\u989d",
                                    "\u8d39\u7528",
                                    "\u603b\u989d",
                                    "\u5c0f\u8ba1",
                                    "\u5408\u8ba1\u8d39\u7528"),
                    "\u65e5\u671f",
                            List.of(
                                    "\u65e5\u671f",
                                    "\u65f6\u95f4",
                                    "\u53d1\u653e\u65e5\u671f",
                                    "\u62a5\u9500\u65e5\u671f",
                                    "\u7533\u8bf7\u65e5\u671f",
                                    "\u7533\u8bf7\u65f6\u95f4",
                                    "\u5165\u8d26\u65e5\u671f"),
                    "\u90e8\u95e8",
                            List.of(
                                    "\u90e8\u95e8",
                                    "\u79d1\u5ba4",
                                    "\u5355\u4f4d",
                                    "\u6240\u5c5e\u90e8\u95e8",
                                    "\u6240\u5c5e\u5355\u4f4d"),
                    "\u59d3\u540d",
                            List.of(
                                    "\u59d3\u540d",
                                    "\u540d\u5b57",
                                    "\u5458\u5de5\u59d3\u540d",
                                    "\u4eba\u5458\u59d3\u540d"),
                    "\u8eab\u4efd\u8bc1",
                            List.of(
                                    "\u8eab\u4efd\u8bc1",
                                    "\u8eab\u4efd\u8bc1\u53f7",
                                    "\u8bc1\u4ef6\u53f7\u7801"),
                    "\u7535\u8bdd",
                            List.of(
                                    "\u7535\u8bdd",
                                    "\u624b\u673a\u53f7",
                                    "\u624b\u673a\u53f7\u7801",
                                    "\u8054\u7cfb\u65b9\u5f0f"),
                    "\u5907\u6ce8",
                            List.of(
                                    "\u5907\u6ce8",
                                    "\u8bf4\u660e",
                                    "\u6458\u8981",
                                    "\u7528\u9014",
                                    "\u7528\u9014\u8bf4\u660e"));

    public Optional<FieldMatch> match(String userField, List<String> headers) {
        if (userField == null || userField.isBlank() || headers == null || headers.isEmpty()) {
            return Optional.empty();
        }

        String normalizedUserField = normalize(userField);
        Map<String, String> normalizedHeaders = normalizedHeaders(headers);

        for (Map.Entry<String, String> entry : normalizedHeaders.entrySet()) {
            if (entry.getKey().equals(normalizedUserField)) {
                return Optional.of(new FieldMatch(userField, entry.getValue(), 1.0));
            }
        }

        for (Map.Entry<String, String> entry : normalizedHeaders.entrySet()) {
            if (entry.getKey().contains(normalizedUserField)) {
                return Optional.of(new FieldMatch(userField, entry.getValue(), 0.9));
            }
        }

        for (Map.Entry<String, String> entry : normalizedHeaders.entrySet()) {
            if (normalizedUserField.contains(entry.getKey())) {
                return Optional.of(new FieldMatch(userField, entry.getValue(), 0.85));
            }
        }

        Optional<FieldMatch> synonymMatch = matchSynonym(userField, normalizedUserField, headers);
        if (synonymMatch.isPresent()) {
            return synonymMatch;
        }

        FieldMatch best = null;
        for (String header : headers) {
            double score = similarity(normalizedUserField, normalize(header));
            if (score >= 0.6 && (best == null || score > best.confidence())) {
                best = new FieldMatch(userField, header, Math.min(0.75, score));
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<FieldMatch> matchSynonym(
            String userField, String normalizedUserField, List<String> headers) {
        for (List<String> words : SYNONYMS.values()) {
            boolean userMatchesGroup =
                    words.stream()
                            .map(this::normalize)
                            .anyMatch(word -> word.equals(normalizedUserField));
            if (!userMatchesGroup) {
                continue;
            }
            for (String header : headers) {
                String normalizedHeader = normalize(header);
                boolean headerMatchesGroup =
                        words.stream()
                                .map(this::normalize)
                                .anyMatch(
                                        word ->
                                                normalizedHeader.equals(word)
                                                        || normalizedHeader.contains(word)
                                                        || word.contains(normalizedHeader));
                if (headerMatchesGroup) {
                    return Optional.of(new FieldMatch(userField, header, 0.8));
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, String> normalizedHeaders(List<String> headers) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String header : headers) {
            normalized.put(normalize(header), header);
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private double similarity(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return 0;
        }
        List<Integer> previous = new ArrayList<>();
        for (int index = 0; index <= right.length(); index++) {
            previous.add(index);
        }
        for (int i = 1; i <= left.length(); i++) {
            List<Integer> current = new ArrayList<>();
            current.add(i);
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current.add(
                        Math.min(
                                Math.min(current.get(j - 1) + 1, previous.get(j) + 1),
                                previous.get(j - 1) + cost));
            }
            previous = current;
        }
        int distance = previous.get(right.length());
        int maxLength = Math.max(left.length(), right.length());
        return 1.0 - ((double) distance / maxLength);
    }

    public record FieldMatch(String userField, String matchedHeader, double confidence) {}
}
