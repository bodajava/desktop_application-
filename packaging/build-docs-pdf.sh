#!/usr/bin/env bash
# ---------------------------------------------------------------------------------------------
#  Regenerates DOCUMENTATION.pdf (A4, cover, clickable TOC, rendered Mermaid diagrams) from
#  DOCUMENTATION.md. Needs the gstack make-pdf tool (set MAKE_PDF_BIN or install gstack).
#
#  The print copy differs from the Markdown only in layout: chapters ("## 1. ...") start new
#  pages, the manual TOC is replaced by the generated one, and diagrams get captions / page
#  orientation (fence options GitHub would not understand, so they are added here).
# ---------------------------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."
PDF_TOOL="${MAKE_PDF_BIN:-$HOME/.claude/skills/gstack/make-pdf/dist/pdf}"
[ -x "$PDF_TOOL" ] || { echo "make-pdf not found (set MAKE_PDF_BIN)"; exit 1; }
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -n 1)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

python3 - DOCUMENTATION.md "$WORK/print.md" <<'PY'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
body = src[src.index("## 1. Executive summary"):]          # cover + generated TOC replace the top
figures = iter([
    'title="Figure 1 - Layered architecture"',
    'title="Figure 2 - Error-handling pipeline"',
    'title="Figure 3 - Entity-relationship model"',
    'title="Figure 4 - Soft delete through views"',
    'title="Figure 5 - Fair proctor allocation"',
    'title="Figure 6 - Emergency 1-click substitution" page=portrait',
    'title="Figure 7 - Control Officer workflow"',
])
out, in_code = [], False
for line in body.split("\n"):
    if line.startswith("```"):
        if not in_code and line.strip() == "```mermaid":
            line = "```mermaid " + next(figures)
        in_code = not in_code
    elif not in_code:
        if re.match(r"^## \d+\. ", line):
            line = "#" + line[2:]                             # chapters -> H1 (new page)
        elif line.startswith("### "):
            line = line[1:]
        elif line.strip() == "---":
            continue
    out.append(line)
open(sys.argv[2], "w", encoding="utf-8").write("\n".join(out))
PY

"$PDF_TOOL" generate --cover --toc --page-size a4 --no-confidential \
    --title "Exam Halls & Proctoring Allocation Management System" \
    --author "Graduation Project - Technical Documentation - v${VERSION}" \
    --date "$(date '+%B %Y')" \
    "$WORK/print.md" DOCUMENTATION.pdf
