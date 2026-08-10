from __future__ import annotations

from pathlib import Path

import pandas as pd


def export_tables_to_excel(tables: list[list[list[str]]], output_path: Path) -> Path:
    with pd.ExcelWriter(output_path, engine="openpyxl") as writer:
        for index, table in enumerate(tables, start=1):
            frame = pd.DataFrame(table)
            frame.to_excel(writer, sheet_name=f"Sheet{index}", index=False, header=False)
    return output_path
