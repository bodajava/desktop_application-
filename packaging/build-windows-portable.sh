#!/usr/bin/env bash
# ---------------------------------------------------------------------------------------------
#  Builds the self-contained Windows x64 edition: the lab bundle + an embedded Eclipse Temurin 21
#  JRE in runtime\ — unzip on any Windows PC and double-click run-app.bat (no Java install needed).
#
#  Usage:   packaging/build-windows-portable.sh [--skip-build]
#  Output:  target/exam-halls-proctoring-<version>-windows-x64-portable.zip
#  Needs:   bash, curl, unzip, zip, python3 (JSON parsing), Maven + JDK 21+ (unless --skip-build)
#
#  The JRE is downloaded once from the official Adoptium API into target/jre-cache/ and verified
#  against the SHA-256 checksum Adoptium publishes. Override the Java feature release with
#  JRE_FEATURE (default 21).
# ---------------------------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

JRE_FEATURE="${JRE_FEATURE:-21}"
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -n 1)
NAME="exam-halls-proctoring-${VERSION}"
DIST_ZIP="target/${NAME}-dist.zip"
OUT_ZIP="target/${NAME}-windows-x64-portable.zip"
CACHE="target/jre-cache"
STAGE="target/windows-portable"

# ---- 1. application bundle ------------------------------------------------------------------
if [ "${1:-}" != "--skip-build" ]; then
    mvn -B -q -Pdist clean package
fi
[ -f "$DIST_ZIP" ] || { echo "Missing $DIST_ZIP — run: mvn -Pdist clean package"; exit 1; }

# ---- 2. Temurin JRE metadata (link + checksum) from the Adoptium API --------------------------
mkdir -p "$CACHE"
API="https://api.adoptium.net/v3/assets/latest/${JRE_FEATURE}/hotspot?os=windows&architecture=x64&image_type=jre&vendor=eclipse"
curl -fsSL "$API" -o "$CACHE/metadata.json"
read -r JRE_LINK JRE_SHA JRE_FILE JRE_SEMVER < <(python3 - "$CACHE/metadata.json" <<'PY'
import json, sys
a = json.load(open(sys.argv[1]))[0]
p = a["binary"]["package"]
print(p["link"], p["checksum"], p["name"], a["version"]["semver"])
PY
)
echo "Temurin JRE ${JRE_SEMVER} (windows-x64): ${JRE_FILE}"

# ---- 3. download once, verify SHA-256 ---------------------------------------------------------
if [ ! -f "$CACHE/$JRE_FILE" ]; then
    curl -fL --progress-bar "$JRE_LINK" -o "$CACHE/$JRE_FILE.part"
    mv "$CACHE/$JRE_FILE.part" "$CACHE/$JRE_FILE"
fi
ACTUAL_SHA=$( (command -v sha256sum >/dev/null && sha256sum "$CACHE/$JRE_FILE" || shasum -a 256 "$CACHE/$JRE_FILE") | cut -d' ' -f1)
if [ "$ACTUAL_SHA" != "$JRE_SHA" ]; then
    echo "[ERROR] Checksum mismatch for $JRE_FILE"; rm -f "$CACHE/$JRE_FILE"; exit 1
fi
echo "Checksum OK ($JRE_SHA)"

# ---- 4. assemble: bundle + runtime\ -------------------------------------------------------------
rm -rf "$STAGE"
mkdir -p "$STAGE"
unzip -q "$DIST_ZIP" -d "$STAGE"
APP_DIR="$STAGE/$NAME"
unzip -q "$CACHE/$JRE_FILE" -d "$STAGE/jre-tmp"
mv "$STAGE"/jre-tmp/*/ "$APP_DIR/runtime"
rm -rf "$STAGE/jre-tmp"
[ -f "$APP_DIR/runtime/bin/javaw.exe" ] || { echo "[ERROR] unexpected JRE layout"; exit 1; }

cat > "$APP_DIR/PORTABLE-EDITION.txt" <<TXT
Exam Halls & Proctoring ${VERSION} - portable edition for Windows x64

1. Unzip this folder anywhere (e.g. Desktop or a USB stick). Do not run it from inside the zip.
2. Copy config\\application.properties.example to config\\application.properties and set db.url,
   db.username and db.password for the Oracle server.
3. Double-click run-app.bat.

Java is included in runtime\\ (Eclipse Temurin JRE ${JRE_SEMVER}); nothing else needs to be installed.
If Windows SmartScreen asks, choose "More info" > "Run anyway" (or right-click the zip >
Properties > Unblock before unzipping).
Documentation: DOCUMENTATION.md / DOCUMENTATION.pdf.
TXT
# Windows line endings for the text file opened in Notepad
python3 - "$APP_DIR/PORTABLE-EDITION.txt" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read().replace("\r\n", "\n"); open(p, "w", newline="\r\n").write(s)
PY
[ -f DOCUMENTATION.pdf ] && cp DOCUMENTATION.pdf "$APP_DIR/"

# ---- 5. zip -------------------------------------------------------------------------------------
rm -f "$OUT_ZIP"
(cd "$STAGE" && zip -q -r -X "../$(basename "$OUT_ZIP")" "$NAME")
echo "Built $OUT_ZIP ($(du -h "$OUT_ZIP" | cut -f1)) — runtime: Temurin ${JRE_SEMVER}"
