#!/usr/bin/env bash
#
# Installs (or upgrades) the Jasper Report Service as a systemd service.
#
# Run as root from inside the extracted package directory:
#
#     sudo ./install-service.sh
#
# Override the defaults with environment variables:
#
#     sudo INSTALL_DIR=/srv/jasper SERVICE_USER=jasper ./install-service.sh
#
# Re-running it upgrades an existing installation: the jar and the scripts are
# replaced, and config/db.properties, config/service.properties and reports/
# are left exactly as they are.
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${INSTALL_DIR:-/opt/jasper-report-service}"
SERVICE_USER="${SERVICE_USER:-jasper}"
SERVICE_NAME="jasper-report-service"
UNIT_FILE="/etc/systemd/system/$SERVICE_NAME.service"

die() { echo "error: $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "this script must be run as root (try: sudo ./install-service.sh)"
command -v systemctl >/dev/null 2>&1 || die "systemctl not found; this package installs a systemd service"

# ------------------------------------------------------------------ java ----
JAVA="${JAVA:-}"
if [ -z "$JAVA" ] && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
fi
[ -n "$JAVA" ] || JAVA="$(command -v java || true)"
[ -n "$JAVA" ] || die "java not found. Install a Java 21 runtime (e.g. apt install openjdk-21-jre-headless), or set JAVA=/path/to/java."
# systemd needs an absolute path in ExecStart.
JAVA="$(readlink -f "$JAVA")"

JAVA_MAJOR="$("$JAVA" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/')"
case "$JAVA_MAJOR" in
  ''|*[!0-9]*) echo "warning: could not determine the Java version of $JAVA; continuing." >&2 ;;
  *) [ "$JAVA_MAJOR" -ge 21 ] || die "Java 21 or newer is required; $JAVA reports version $JAVA_MAJOR." ;;
esac
echo "Using java: $JAVA (version $JAVA_MAJOR)"

[ -f "$SRC/jasper-report-service.jar" ] || die "jasper-report-service.jar not found next to this script."

# ------------------------------------------------------------------ user ----
if id "$SERVICE_USER" >/dev/null 2>&1; then
  echo "Service account $SERVICE_USER already exists."
else
  echo "Creating system account $SERVICE_USER"
  useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi

# --------------------------------------------------------------- payload ----
UPGRADE=false
if [ -f "$UNIT_FILE" ]; then
  UPGRADE=true
  echo "Existing installation found; stopping $SERVICE_NAME before replacing the jar."
  systemctl stop "$SERVICE_NAME" || true
fi

mkdir -p "$INSTALL_DIR/config" "$INSTALL_DIR/reports"

if [ "$SRC" != "$INSTALL_DIR" ]; then
  install -m 0644 "$SRC/jasper-report-service.jar" "$INSTALL_DIR/jasper-report-service.jar"
  install -m 0644 "$SRC/config/db.properties.example"      "$INSTALL_DIR/config/db.properties.example"
  install -m 0644 "$SRC/config/service.properties.example" "$INSTALL_DIR/config/service.properties.example"
  install -m 0755 "$SRC/uninstall-service.sh" "$INSTALL_DIR/uninstall-service.sh"
  install -m 0644 "$SRC/README.txt" "$INSTALL_DIR/README.txt"
  [ -f "$SRC/VERSION" ] && install -m 0644 "$SRC/VERSION" "$INSTALL_DIR/VERSION"
  [ -f "$SRC/LICENSE" ] && install -m 0644 "$SRC/LICENSE" "$INSTALL_DIR/LICENSE"
  # Only seeded on a first install - never overwrite report templates.
  if [ -f "$SRC/reports/README.txt" ] && [ ! -e "$INSTALL_DIR/reports/README.txt" ]; then
    install -m 0644 "$SRC/reports/README.txt" "$INSTALL_DIR/reports/README.txt"
  fi
else
  echo "Installing in place from $INSTALL_DIR."
fi

# db.properties holds database credentials: readable by the service account
# only. Never overwritten, so an upgrade keeps the existing settings.
if [ -f "$INSTALL_DIR/config/db.properties" ]; then
  echo "Keeping existing config/db.properties."
  chmod 0640 "$INSTALL_DIR/config/db.properties"
fi

chown -R "$SERVICE_USER":"$SERVICE_USER" "$INSTALL_DIR"

# ------------------------------------------------------------------ unit ----
echo "Writing $UNIT_FILE"
sed -e "s|__INSTALL_DIR__|$INSTALL_DIR|g" \
    -e "s|__SERVICE_USER__|$SERVICE_USER|g" \
    -e "s|__JAVA__|$JAVA|g" \
    "$SRC/jasper-report-service.service" > "$UNIT_FILE"
chmod 0644 "$UNIT_FILE"
systemctl daemon-reload

# --------------------------------------------------------------- startup ----
# Without config/db.properties the application fails to start with "Failed to
# configure a DataSource", so on a first install stop here rather than leaving
# a service in a restart loop.
if [ ! -f "$INSTALL_DIR/config/db.properties" ]; then
  cat <<EOF

Installed to $INSTALL_DIR, but NOT started yet: the database connection is not
configured, and the service does not start without it.

Next steps:
  1. cp $INSTALL_DIR/config/db.properties.example $INSTALL_DIR/config/db.properties
  2. Edit $INSTALL_DIR/config/db.properties and fill in the SQL Server host,
     database, username and password.
  3. chown $SERVICE_USER:$SERVICE_USER $INSTALL_DIR/config/db.properties
     chmod 640 $INSTALL_DIR/config/db.properties
  4. Copy your .jrxml report templates into $INSTALL_DIR/reports/
  5. systemctl enable --now $SERVICE_NAME

EOF
  exit 0
fi

systemctl enable "$SERVICE_NAME" >/dev/null
systemctl start "$SERVICE_NAME"

PORT="$(sed -nE 's/^[[:space:]]*server\.port[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' \
  "$INSTALL_DIR/config/service.properties" 2>/dev/null | tail -n 1)"
PORT="${PORT:-8080}"

cat <<EOF

$([ "$UPGRADE" = true ] && echo "Upgraded" || echo "Installed") and started $SERVICE_NAME from $INSTALL_DIR.

  systemctl status $SERVICE_NAME
  journalctl -u $SERVICE_NAME -f
  curl http://localhost:$PORT/actuator/health

{"status":"UP"} means it is running and can reach the database.
EOF
