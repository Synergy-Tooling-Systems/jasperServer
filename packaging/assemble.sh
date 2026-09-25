#!/usr/bin/env bash
#
# Assembles the installable distribution packages from an already-built jar.
#
# Run from the project root, after ./mvnw package, with VERSION set:
#
#   VERSION=1.2.3 packaging/assemble.sh
#
# Produces, under build/dist/:
#   jasper-report-service-<version>-windows.zip
#   jasper-report-service-<version>-linux.tar.gz
#   SHA256SUMS.txt
#
# This is what .github/workflows/release.yml runs. The Windows package is the
# same layout build-dist.ps1 assembles locally, plus the WinSW binary and a
# VERSION file.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${VERSION:?VERSION must be set, e.g. VERSION=1.2.3 packaging/assemble.sh}"

# Fail with a clear message rather than part-way through the assembly. zip is
# preinstalled on GitHub's ubuntu runners but is often missing from Git Bash on
# Windows - use build-dist.ps1 there.
for tool in curl zip tar sha256sum find sed; do
  command -v "$tool" >/dev/null 2>&1 || { echo "error: '$tool' is required but not installed." >&2; exit 1; }
done

# WinSW (https://github.com/winsw/winsw, MIT) is the Windows Service wrapper.
# Overridable so a server without .NET Framework 4.6.1 can be served by
# rebuilding with WINSW_ASSET=WinSW-x64.exe (self-contained, ~18 MB).
WINSW_VERSION="${WINSW_VERSION:-v2.12.0}"
WINSW_ASSET="${WINSW_ASSET:-WinSW.NET461.exe}"

BUILD="$ROOT/build"
STAGE="$BUILD/stage"
DIST="$BUILD/dist"
WIN_NAME="jasper-report-service-$VERSION-windows"
LINUX_NAME="jasper-report-service-$VERSION-linux"
WIN="$STAGE/$WIN_NAME"
LINUX="$STAGE/$LINUX_NAME"

rm -rf "$BUILD"
mkdir -p "$WIN/config" "$WIN/reports" "$WIN/third-party" \
         "$LINUX/config" "$LINUX/reports" "$DIST"

# ---------------------------------------------------------------- the jar ----
# Built as target/jasper-report-service-<version>.jar; the .jar.original left
# behind by the Spring Boot repackage step is not the executable one.
JAR="$(find "$ROOT/target" -maxdepth 1 -name 'jasper-report-service-*.jar' ! -name '*.original' | head -n 1)"
if [ -z "$JAR" ]; then
  echo "error: no built jar under target/. Run ./mvnw package first." >&2
  exit 1
fi
echo "Packaging $(basename "$JAR") as version $VERSION"

# ------------------------------------------------------- Windows package ----
cp "$JAR"                                       "$WIN/jasper-report-service.jar"
cp "$ROOT/windows-service/jasper-report-service.xml" "$WIN/"
cp "$ROOT/windows-service/install-service.ps1"       "$WIN/"
cp "$ROOT/windows-service/uninstall-service.ps1"     "$WIN/"
cp "$ROOT/windows-service/README.txt"                "$WIN/"
cp "$ROOT/config/db.properties.example"              "$WIN/config/"
cp "$ROOT/config/service.properties.example"         "$WIN/config/"
cp "$ROOT/LICENSE"                                   "$WIN/"
printf '%s\n' "$VERSION" > "$WIN/VERSION"

# Report templates are not in the repository (reports/ is gitignored), so the
# package ships the directory empty and explains what belongs in it.
cp "$ROOT/packaging/common/reports-README.txt" "$WIN/reports/README.txt"

echo "Downloading WinSW $WINSW_VERSION ($WINSW_ASSET)"
curl -fsSL --retry 3 --retry-delay 5 \
  -o "$WIN/jasper-report-service.exe" \
  "https://github.com/winsw/winsw/releases/download/$WINSW_VERSION/$WINSW_ASSET"
curl -fsSL --retry 3 --retry-delay 5 \
  -o "$WIN/third-party/WinSW-LICENSE.txt" \
  "https://raw.githubusercontent.com/winsw/winsw/$WINSW_VERSION/LICENSE.txt"
printf 'WinSW %s (%s), MIT licensed, from https://github.com/winsw/winsw\nShipped here as jasper-report-service.exe.\n' \
  "$WINSW_VERSION" "$WINSW_ASSET" > "$WIN/third-party/README.txt"

# The text files are checked out with LF on the Linux runner; give them CRLF so
# they read correctly in Notepad and other plain Windows editors.
while IFS= read -r -d '' f; do
  sed -i 's/\r\{0,1\}$/\r/' "$f"
done < <(find "$WIN" -type f \( -name '*.txt' -o -name '*.ps1' -o -name '*.xml' -o -name '*.example' -o -name 'VERSION' \) -print0)

# --------------------------------------------------------- Linux package ----
cp "$JAR"                                      "$LINUX/jasper-report-service.jar"
cp "$ROOT/packaging/linux/jasper-report-service.service" "$LINUX/"
cp "$ROOT/packaging/linux/install-service.sh"            "$LINUX/"
cp "$ROOT/packaging/linux/uninstall-service.sh"          "$LINUX/"
cp "$ROOT/packaging/linux/README.txt"                    "$LINUX/"
cp "$ROOT/config/db.properties.example"                  "$LINUX/config/"
cp "$ROOT/config/service.properties.example"             "$LINUX/config/"
cp "$ROOT/LICENSE"                                       "$LINUX/"
cp "$ROOT/packaging/common/reports-README.txt"           "$LINUX/reports/README.txt"
printf '%s\n' "$VERSION" > "$LINUX/VERSION"
chmod +x "$LINUX/install-service.sh" "$LINUX/uninstall-service.sh"

# ------------------------------------------------------------- archives ----
( cd "$STAGE" && zip -q -r "$DIST/$WIN_NAME.zip" "$WIN_NAME" )
( cd "$STAGE" && tar -czf "$DIST/$LINUX_NAME.tar.gz" "$LINUX_NAME" )

( cd "$DIST" && sha256sum "$WIN_NAME.zip" "$LINUX_NAME.tar.gz" > SHA256SUMS.txt )

echo
echo "Packages in build/dist:"
( cd "$DIST" && ls -lh && echo && cat SHA256SUMS.txt )
