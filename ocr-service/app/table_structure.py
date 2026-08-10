from __future__ import annotations

from dataclasses import dataclass
from statistics import median


@dataclass(frozen=True)
class OcrBox:
    text: str
    x1: float
    y1: float
    x2: float
    y2: float
    confidence: float

    @property
    def cx(self) -> float:
        return (self.x1 + self.x2) / 2

    @property
    def cy(self) -> float:
        return (self.y1 + self.y2) / 2

    @property
    def height(self) -> float:
        return max(1.0, self.y2 - self.y1)


def boxes_to_table(boxes: list[OcrBox]) -> list[list[str]]:
    clean_boxes = [box for box in boxes if box.text.strip()]
    if len(clean_boxes) < 4:
        return []

    rows = cluster_rows(clean_boxes)
    if len(rows) < 2:
        return []

    columns = cluster_columns(clean_boxes)
    if len(columns) < 2:
        return []

    table: list[list[str]] = []
    for row_boxes in rows:
        row = [""] * len(columns)
        for box in sorted(row_boxes, key=lambda item: item.cx):
            col_index = nearest_column(box.cx, columns)
            row[col_index] = (row[col_index] + " " + box.text).strip()
        if any(cell.strip() for cell in row):
            table.append(row)

    return trim_empty_edges(table)


def cluster_rows(boxes: list[OcrBox]) -> list[list[OcrBox]]:
    heights = [box.height for box in boxes]
    row_tolerance = max(8.0, median(heights) * 0.75)
    rows: list[list[OcrBox]] = []
    row_centers: list[float] = []

    for box in sorted(boxes, key=lambda item: item.cy):
        matched_index = None
        for index, center in enumerate(row_centers):
            if abs(box.cy - center) <= row_tolerance:
                matched_index = index
                break

        if matched_index is None:
            rows.append([box])
            row_centers.append(box.cy)
        else:
            rows[matched_index].append(box)
            row_centers[matched_index] = sum(item.cy for item in rows[matched_index]) / len(
                rows[matched_index]
            )

    return [sorted(row, key=lambda item: item.cx) for row in rows]


def cluster_columns(boxes: list[OcrBox]) -> list[float]:
    xs = sorted(box.cx for box in boxes)
    if not xs:
        return []

    gaps = [right - left for left, right in zip(xs, xs[1:]) if right > left]
    tolerance = max(130.0, median(gaps) * 0.65) if gaps else 130.0
    columns: list[list[float]] = []

    for x in xs:
        if not columns or abs(x - mean(columns[-1])) > tolerance:
            columns.append([x])
        else:
            columns[-1].append(x)

    return [mean(column) for column in columns]


def nearest_column(x: float, columns: list[float]) -> int:
    return min(range(len(columns)), key=lambda index: abs(columns[index] - x))


def trim_empty_edges(table: list[list[str]]) -> list[list[str]]:
    rows = [row for row in table if any(cell.strip() for cell in row)]
    if not rows:
        return []

    column_count = max(len(row) for row in rows)
    padded = [row + [""] * (column_count - len(row)) for row in rows]

    used_columns = [
        index for index in range(column_count) if any(row[index].strip() for row in padded)
    ]
    if not used_columns:
        return []

    first = used_columns[0]
    last = used_columns[-1]
    return [row[first : last + 1] for row in padded]


def mean(values: list[float]) -> float:
    return sum(values) / len(values)


def estimate_quality(boxes: list[OcrBox], table: list[list[str]]) -> str:
    if not boxes or not table:
        return "\u8f83\u5dee"

    avg_confidence = sum(box.confidence for box in boxes) / len(boxes)
    filled_cells = sum(1 for row in table for cell in row if cell.strip())
    total_cells = sum(len(row) for row in table)
    fill_ratio = filled_cells / total_cells if total_cells else 0

    if avg_confidence >= 0.88 and fill_ratio >= 0.65:
        return "\u8f83\u597d"
    if avg_confidence >= 0.65 and fill_ratio >= 0.35:
        return "\u4e00\u822c"
    return "\u8f83\u5dee"
