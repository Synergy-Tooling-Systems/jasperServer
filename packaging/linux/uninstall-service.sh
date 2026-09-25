#!/usr/bin/env bash
#
# Stops and removes the Jasper Report Service systemd service.
#
#     sudo ./uninstall-service.sh            remove the service, keep the files
#     sudo ./uninstall-service.sh --purge    also delete the install directory
#
# Without --purge the install directory is left alone, so config/db.properties
# and the report templates in reports/ survive. --purge deletes all of it.
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/jasper-report-service}"
SERVICE_USER="${SERVICE_USER:-jasper}"
SERVICE_NAME="jasper-report-service"
UNIT_FILE="/etc/systemd/system/$SERVICE_NAME.service"
PURGE=false

for arg in "$@"; do
  case "$arg" in
    --purge) PURGE=true ;;
    *) echo "error: unknown option '$arg' (expected --purge)" >&2; exit 1 ;;
  esac
done

[ "$(id -u)" -eq 0 ] || { echo "error: this script must be run as root (try: sudo ./uninstall-service.sh)" >&2; exit 1; }

systemctl stop "$SERVICE_NAME" 2>/dev/null || true
systemctl disable "$SERVICE_NAME" 2>/dev/null || true
rm -f "$UNIT_FILE"
systemctl daemon-reload
systemctl reset-failed "$SERVICE_NAME" 2>/dev/null || true
echo "Removed the $SERVICE_NAME service."

if [ "$PURGE" = true ]; then
  # Deliberately destructive, and only on an explicit --purge: this removes the
  # database credentials and every report template under the install directory.
  rm -rf "$INSTALL_DIR"
  echo "Deleted $INSTALL_DIR."
  if id "$SERVICE_USER" >/dev/null 2>&1; then
    userdel "$SERVICE_USER" 2>/dev/null && echo "Deleted the $SERVICE_USER account." \
      || echo "Left the $SERVICE_USER account in place (still in use)."
  fi
else
  echo "Left $INSTALL_DIR in place (config and reports kept). Re-run with --purge to delete it."
fi
