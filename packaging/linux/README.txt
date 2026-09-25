Jasper Report Service - Linux Installation
===========================================

This directory is a self-contained distribution of the Jasper Report Service.
It contains everything needed to run it as a systemd service.

Contents
--------
  jasper-report-service.jar          the application
  jasper-report-service.service      systemd unit template
  install-service.sh                 installs/upgrades and starts the service
  uninstall-service.sh               stops and removes the service
  reports/                           .jrxml report files served by the app
                                     (ships empty - see reports/README.txt)
  config/db.properties.example       template for the database connection
                                     (required - see step 2)
  config/service.properties.example  template for the API port and other
                                     runtime settings
  VERSION                            the version in this package

Prerequisites
-------------
  - A Linux distribution using systemd (Debian/Ubuntu, RHEL/Rocky/Alma,
    SUSE, ...).
  - Java 21 (JRE is enough). Verify with:
        java -version
    If it is missing:
        apt install openjdk-21-jre-headless      (Debian/Ubuntu)
        dnf install java-21-openjdk-headless     (RHEL/Rocky/Alma)
  - root (sudo) access, to install a systemd service.
  - Network access to a SQL Server instance. This is required: the service
    does not start without connection settings, and every report render
    opens a connection, including reports that use only parameters and have
    no <queryString>.

Installation
------------
1. Extract the package and change into it:
       tar -xzf jasper-report-service-<version>-linux.tar.gz
       cd jasper-report-service-<version>-linux

2. Run the installer:
       sudo ./install-service.sh

   This creates the "jasper" system account, installs everything into
   /opt/jasper-report-service, and writes the systemd unit. On a first
   install it stops before starting the service, because the database
   connection is not configured yet.

   To install somewhere else, or run as a different account:
       sudo INSTALL_DIR=/srv/jasper SERVICE_USER=reports ./install-service.sh

3. Configure the database connection (required):
       cd /opt/jasper-report-service
       sudo cp config/db.properties.example config/db.properties
       sudo nano config/db.properties
       sudo chown jasper:jasper config/db.properties
       sudo chmod 640 config/db.properties

   Fill in the real SQL Server host, database name, username and password.
   Without this file the service fails to start, logging "Failed to
   configure a DataSource". With it, but pointing at a server it cannot
   reach, the service starts but every render fails.

4. Add your report templates:
       sudo cp /path/to/*.jrxml /opt/jasper-report-service/reports/
       sudo chown -R jasper:jasper /opt/jasper-report-service/reports

   The package ships reports/ empty - templates are maintained separately
   from the application. See reports/README.txt.

5. Configure the API port (optional):
   The service listens on port 8080 unless told otherwise. To change it:
       sudo cp config/service.properties.example config/service.properties
       sudo nano config/service.properties        # set server.port=9090
   This file is not touched by upgrades, so the port survives an update.

   Ports below 1024 will not work with this unit as written: the service
   runs unprivileged. Put a reverse proxy in front instead, or add
   AmbientCapabilities=CAP_NET_BIND_SERVICE to the unit.

6. Start it:
       sudo systemctl enable --now jasper-report-service

7. Verify it is running (use your port if you changed it):
       systemctl status jasper-report-service
       curl http://localhost:8080/actuator/health

   {"status":"UP"} means the service is running and can reach the
   database. {"status":"DOWN"} (HTTP 503) means it is running but the
   database is unreachable - check config/db.properties and that the
   server allows connections from this machine.

Logs
----
The service logs to the journal:
       journalctl -u jasper-report-service -f            follow
       journalctl -u jasper-report-service -n 200         last 200 lines
       journalctl -u jasper-report-service --since today

Updating to a new version
--------------------------
Extract the new package and run its installer. It stops the service,
replaces the jar, and starts it again. config/db.properties,
config/service.properties and reports/ are left untouched.

       tar -xzf jasper-report-service-<new-version>-linux.tar.gz
       cd jasper-report-service-<new-version>-linux
       sudo ./install-service.sh

Uninstalling
------------
       sudo /opt/jasper-report-service/uninstall-service.sh

This removes the service but keeps /opt/jasper-report-service, so the
database credentials and the report templates survive. To delete all of it,
including config and reports:

       sudo /opt/jasper-report-service/uninstall-service.sh --purge

Troubleshooting
---------------
- Service fails to start with "Failed to configure a DataSource" in
  journalctl: config/db.properties is missing or has no
  spring.datasource.url. See step 3.
- Renders fail with "Failed to obtain a database connection for the
  report": the settings in config/db.properties are wrong, or SQL Server
  is not reachable from this machine. This affects every report, not only
  the ones with a <queryString>.
- "Port 8080 was already in use" on startup: another program holds that
  port. Set a free one in config/service.properties and restart.
- Renders fail with "Report file not found": the .jrxml is not in
  /opt/jasper-report-service/reports, or is not readable by the jasper
  account. Check with: sudo -u jasper ls -l /opt/jasper-report-service/reports
- The unit runs java from the path found at install time. If Java is
  upgraded to a different location, re-run install-service.sh, or edit
  ExecStart in /etc/systemd/system/jasper-report-service.service and run
  systemctl daemon-reload.
- reports/, config/db.properties and config/service.properties are read
  relative to the service's working directory, which the unit sets to the
  install directory. Do not move the jar out of that directory on its own.
