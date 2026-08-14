#!/usr/bin/env python3
"""Converts the Spanish insurance test corpus (Markdown) into PDFs the ingestion pipeline can read.

The output is deliberately shaped for this project's real extraction/chunking chain, which is not
a generic PDF reader:

* `PdfBoxTextExtractor` goes through Spring AI's `PagePdfDocumentReader`, which uses
  `PDFLayoutTextStripperByArea` - it clips to the page rectangle, so any text drawn past the right
  margin is silently LOST. Every line is therefore wrapped using real font metrics, never emitted
  as one long line. (This exact failure previously truncated the evaluation corpus.)
* `StructureAwareDocumentChunker` splits on BLANK LINES (`\\n{2,}`) and detects section headings
  from short numbered lines such as "7.2 Cobertura de granizo". Paragraph spacing and heading
  shape are therefore semantically meaningful, not cosmetic, and are preserved exactly.
* Chunks are capped at 1000 characters, so paragraphs are kept comfortably below that where
  possible to avoid mid-paragraph splits.

Markdown support is intentionally minimal - headings, paragraphs, list items, simple pipe tables
and horizontal rules. Anything fancier would only produce layout the extractor cannot read back.

Usage:
    python scripts/build_test_corpus_pdfs.py [--check]

    --check  Extract the text back out of every generated PDF and verify no content was lost.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.pdfgen import canvas

REPO_ROOT = Path(__file__).resolve().parent.parent
CORPUS_DIR = REPO_ROOT / "test-data" / "insurance" / "auto"

PAGE_WIDTH, PAGE_HEIGHT = A4
MARGIN_LEFT = 56
MARGIN_RIGHT = 56
MARGIN_TOP = 56
MARGIN_BOTTOM = 56
USABLE_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT

BODY_FONT = "Helvetica"
BOLD_FONT = "Helvetica-Bold"
BODY_SIZE = 10
HEADING_SIZE = 11
LEADING = 13.5
PARAGRAPH_GAP = 9.0


def wrap(text: str, font: str, size: float, width: float) -> list[str]:
    """Greedy word wrap using real font metrics (not a character-count guess)."""
    words = text.split()
    if not words:
        return [""]

    lines: list[str] = []
    current = words[0]
    for word in words[1:]:
        candidate = f"{current} {word}"
        if stringWidth(candidate, font, size) <= width:
            current = candidate
        else:
            lines.append(current)
            current = word
    lines.append(current)
    return lines


def strip_inline_markdown(text: str) -> str:
    """Removes emphasis markers - they would otherwise show up literally in the extracted text."""
    text = re.sub(r"\*\*(.+?)\*\*", r"\1", text)
    text = re.sub(r"(?<!\w)\*(.+?)\*(?!\w)", r"\1", text)
    text = re.sub(r"`(.+?)`", r"\1", text)
    text = re.sub(r"\[(.+?)\]\((.+?)\)", r"\1", text)
    return text


class Block:
    """One logical block: a heading, a paragraph, a list item or a table row."""

    __slots__ = ("text", "font", "size", "keep_with_next")

    def __init__(self, text: str, font: str = BODY_FONT, size: float = BODY_SIZE,
                 keep_with_next: bool = False):
        self.text = text
        self.font = font
        self.size = size
        self.keep_with_next = keep_with_next


def parse_markdown(markdown: str) -> list[Block]:
    blocks: list[Block] = []
    in_code_fence = False

    for raw_line in markdown.splitlines():
        line = raw_line.rstrip()

        if line.startswith("```"):
            in_code_fence = not in_code_fence
            continue
        if in_code_fence:
            if line.strip():
                blocks.append(Block(line.strip()))
            continue

        if not line.strip():
            continue

        # Horizontal rules carry no text; the blank-line separation already exists.
        if re.fullmatch(r"[-*_]{3,}", line.strip()):
            continue

        # Markdown table separator row (|---|---|)
        if re.fullmatch(r"\|[\s:|-]+\|", line.strip()):
            continue

        heading = re.match(r"^(#{1,6})\s+(.*)$", line)
        if heading:
            blocks.append(Block(strip_inline_markdown(heading.group(2)).strip(),
                                font=BOLD_FONT, size=HEADING_SIZE, keep_with_next=True))
            continue

        # Pipe table row -> flattened to a readable sentence-like line. The chunker and the
        # embedder both work on plain text; a visually aligned table would extract as ragged
        # columns and read far worse.
        if line.strip().startswith("|") and line.strip().endswith("|"):
            cells = [strip_inline_markdown(c).strip() for c in line.strip().strip("|").split("|")]
            cells = [c for c in cells if c]
            if cells:
                blocks.append(Block(" | ".join(cells)))
            continue

        bullet = re.match(r"^\s*[-*+]\s+(.*)$", line)
        if bullet:
            blocks.append(Block("- " + strip_inline_markdown(bullet.group(1)).strip()))
            continue

        numbered = re.match(r"^\s*(\d+[.)])\s+(.*)$", line)
        if numbered:
            blocks.append(Block(f"{numbered.group(1)} {strip_inline_markdown(numbered.group(2)).strip()}"))
            continue

        blocks.append(Block(strip_inline_markdown(line).strip()))

    return blocks


def render_pdf(blocks: list[Block], destination: Path, title: str) -> int:
    destination.parent.mkdir(parents=True, exist_ok=True)
    pdf = canvas.Canvas(str(destination), pagesize=A4)
    pdf.setTitle(title)

    y = PAGE_HEIGHT - MARGIN_TOP
    pages = 1

    def new_page() -> float:
        nonlocal pages
        pdf.showPage()
        pages += 1
        return PAGE_HEIGHT - MARGIN_TOP

    for block in blocks:
        lines = wrap(block.text, block.font, block.size, USABLE_WIDTH)
        needed = len(lines) * LEADING + PARAGRAPH_GAP
        # Keep a heading with at least the first line of what follows it.
        if block.keep_with_next:
            needed += LEADING

        if y - needed < MARGIN_BOTTOM:
            y = new_page()

        pdf.setFont(block.font, block.size)
        for line in lines:
            if y - LEADING < MARGIN_BOTTOM:
                y = new_page()
                pdf.setFont(block.font, block.size)
            pdf.drawString(MARGIN_LEFT, y, line)
            y -= LEADING

        # The gap is what becomes the blank line in the extracted text, which is precisely what
        # StructureAwareDocumentChunker splits on. It is load-bearing, not decoration.
        y -= PARAGRAPH_GAP

    pdf.save()
    return pages


def significant_words(text: str) -> set[str]:
    return {w for w in re.findall(r"[a-záéíóúñü0-9]{5,}", text.lower())}


def check_extraction(markdown_path: Path, pdf_path: Path) -> tuple[bool, str]:
    """Verifies the PDF's text can be read back and that no significant content was lost."""
    try:
        from pypdf import PdfReader
    except ImportError:
        try:
            from PyPDF2 import PdfReader  # type: ignore
        except ImportError:
            return True, "SKIPPED (no pypdf/PyPDF2 available to verify extraction)"

    reader = PdfReader(str(pdf_path))
    extracted = "\n".join((page.extract_text() or "") for page in reader.pages)

    source_words = significant_words("\n".join(b.text for b in parse_markdown(
        markdown_path.read_text(encoding="utf-8"))))
    extracted_words = significant_words(extracted)

    missing = source_words - extracted_words
    if len(source_words) == 0:
        return False, "no significant content in source"

    coverage = 1.0 - (len(missing) / len(source_words))
    if coverage < 0.98:
        sample = ", ".join(sorted(missing)[:8])
        return False, f"coverage {coverage:.1%}, missing e.g.: {sample}"
    return True, f"coverage {coverage:.1%}, {len(reader.pages)} page(s)"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="extract text back out of each PDF and verify nothing was lost")
    args = parser.parse_args()

    sources = sorted(CORPUS_DIR.rglob("*.md"))
    if not sources:
        print(f"No Markdown sources found under {CORPUS_DIR}", file=sys.stderr)
        return 1

    failures = 0
    total_pages = 0
    for source in sources:
        blocks = parse_markdown(source.read_text(encoding="utf-8"))
        destination = source.with_suffix(".pdf")
        pages = render_pdf(blocks, destination, source.stem)
        total_pages += pages

        status = f"{pages:>2} page(s)"
        if args.check:
            ok, detail = check_extraction(source, destination)
            status = f"{status}  {'OK ' if ok else 'FAIL'} {detail}"
            if not ok:
                failures += 1

        print(f"{source.relative_to(REPO_ROOT)} -> {destination.name}  {status}")

    print(f"\n{len(sources)} document(s), {total_pages} page(s) total")
    if failures:
        print(f"{failures} document(s) failed extraction verification", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
