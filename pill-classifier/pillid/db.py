"""식약처 의약품 낱알식별 정보를 담는 SQLite DB."""
from __future__ import annotations

import json
import sqlite3
from pathlib import Path

# API 응답 필드 중 식별에 쓰는 것들. 첫 항목은 반드시 ITEM_SEQ(품목기준코드).
FIELDS = [
    "ITEM_SEQ", "ITEM_NAME", "ENTP_NAME", "CHART", "ITEM_IMAGE",
    "PRINT_FRONT", "PRINT_BACK", "DRUG_SHAPE", "COLOR_CLASS1", "COLOR_CLASS2",
    "LINE_FRONT", "LINE_BACK", "LENG_LONG", "LENG_SHORT", "THICK",
    "FORM_CODE_NAME", "CLASS_NAME", "ETC_OTC_NAME",
]
_COLS = [f.lower() for f in FIELDS]


def _clean(v):
    if v is None:
        return None
    s = str(v).strip()
    return s or None


class PillDB:
    def __init__(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(path))
        self.conn.row_factory = sqlite3.Row
        cols = ", ".join(f"{c} TEXT" for c in _COLS[1:])
        self.conn.execute(
            f"CREATE TABLE IF NOT EXISTS pills (item_seq TEXT PRIMARY KEY, {cols}, raw_json TEXT)")

    def upsert_many(self, items: list[dict]) -> int:
        rows = []
        for it in items:
            seq = _clean(it.get("ITEM_SEQ"))
            if not seq:
                continue
            rows.append([seq] + [_clean(it.get(f)) for f in FIELDS[1:]]
                        + [json.dumps(it, ensure_ascii=False)])
        placeholders = ",".join("?" * (len(FIELDS) + 1))
        self.conn.executemany(
            f"INSERT OR REPLACE INTO pills ({', '.join(_COLS)}, raw_json) VALUES ({placeholders})", rows)
        self.conn.commit()
        return len(rows)

    def all_items(self) -> list[dict]:
        return [dict(r) for r in self.conn.execute("SELECT * FROM pills ORDER BY item_seq")]

    def get(self, item_seq: str) -> dict | None:
        r = self.conn.execute("SELECT * FROM pills WHERE item_seq = ?", (item_seq,)).fetchone()
        return dict(r) if r else None

    def count(self) -> int:
        return self.conn.execute("SELECT COUNT(*) FROM pills").fetchone()[0]

    def close(self) -> None:
        self.conn.close()
