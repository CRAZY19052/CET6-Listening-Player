#!/usr/bin/env python3
"""Build the private offline catalog and copy local audio into app assets.

This script is intentionally inert: it does not download anything. You must
provide a manifest describing audio files you are allowed to use.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path


SECTION_TEMPLATE = [
    ("长对话 1", "Q1–4", 0.145),
    ("长对话 2", "Q5–8", 0.145),
    ("听力篇章 1", "Q9–11", 0.125),
    ("听力篇章 2", "Q12–15", 0.145),
    ("讲座 / 讲话 1", "Q16–18", 0.145),
    ("讲座 / 讲话 2", "Q19–21", 0.145),
    ("讲座 / 讲话 3", "Q22–25", 0.150),
]


def fallback_sections(duration_ms: int) -> list[dict]:
    sections = []
    cursor = 0
    for index, (title, questions, ratio) in enumerate(SECTION_TEMPLATE):
        if index == len(SECTION_TEMPLATE) - 1:
            end = duration_ms
        else:
            end = round(duration_ms * min(1.0, sum(s[2] for s in SECTION_TEMPLATE[: index + 1])))
        sections.append(
            {
                "title": title,
                "questions": questions,
                "startMs": cursor,
                "endMs": end,
            }
        )
        cursor = end
    return sections


def normalize_sections(raw: list[dict]) -> list[dict]:
    result = []
    for section in raw:
        questions = section.get("questions", "")
        if isinstance(questions, list):
            if questions:
                questions = f"Q{questions[0]}–{questions[-1]}"
            else:
                questions = "Q"
        title = str(section["title"])
        title = title.replace("长对话", "长对话 ").replace("篇章", "听力篇章 ")
        if title.startswith("Recording"):
            title = title.replace("Recording", "讲座 / 讲话 ")
        title = " ".join(title.split())
        result.append(
            {
                "title": title,
                "questions": str(questions),
                "startMs": int(section["startMs"]),
                "endMs": int(section["endMs"]),
            }
        )
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--sections", type=Path)
    parser.add_argument("--project", required=True, type=Path)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    entries = manifest["entries"] if isinstance(manifest, dict) else manifest
    section_map = {}
    if args.sections and args.sections.exists():
        section_map = json.loads(args.sections.read_text(encoding="utf-8"))
        section_map = section_map.get("items", section_map)

    assets_dir = args.project / "app" / "src" / "main" / "assets"
    audio_dir = assets_dir / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)
    for old in audio_dir.glob("*.m4a"):
        old.unlink()

    catalog = []
    ready = []
    for entry in entries:
        if entry.get("status") != "ok":
            continue
        source = Path(entry["output_path"])
        if not source.exists():
            raise FileNotFoundError(f"Audio not found: {source}")
        file_name = source.name
        duration_ms = int(
            round(
                float(
                    entry.get("measured_duration")
                    or entry.get("playlist_duration")
                    or entry.get("duration")
                )
                * 1000
            )
        )
        item_id = f'{entry["exam_ym"]}-{entry["set_no"]}'
        raw_sections = section_map.get(item_id) or section_map.get(str(entry["id"]))
        if isinstance(raw_sections, dict):
            raw_sections = raw_sections.get("sections")
        sections = normalize_sections(raw_sections) if raw_sections else fallback_sections(duration_ms)
        if len(sections) != 7:
            raise ValueError(f"{item_id}: expected 7 sections, got {len(sections)}")
        if sections[0]["startMs"] != 0 or abs(sections[-1]["endMs"] - duration_ms) > 100:
            raise ValueError(f"{item_id}: sections must span the full track")
        sections[-1]["endMs"] = duration_ms

        shutil.copy2(source, audio_dir / file_name)
        ready.append((source, file_name))
        catalog.append(
            {
                "id": item_id,
                "year": int(entry["exam_ym"][:4]),
                "examYm": entry["exam_ym"],
                "setNo": int(entry["set_no"]),
                "title": f'{entry["exam_ym"][:4]} 年 {int(entry["exam_ym"][5:7])} 月六级听力 · 第 {entry["set_no"]} 套',
                "fileName": file_name,
                "durationMs": duration_ms,
                "sections": sections,
            }
        )

    catalog.sort(key=lambda item: (item["examYm"], item["setNo"]), reverse=True)
    (assets_dir / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    marker_source = "chapter markers" if section_map else "fallback weights"
    total_mb = sum((audio_dir / name).stat().st_size for _, name in ready) / 1024 / 1024
    print(f"catalog items: {len(catalog)}")
    print(f"audio copied: {total_mb:.2f} MiB")
    print(f"section source: {marker_source}")
    print(f"output: {assets_dir / 'catalog.json'}")


if __name__ == "__main__":
    main()
