Jasper Report Service - Windows Service Installation
======================================================

This folder is a self-contained distribution of the Jasper Report Service.
It contains everything needed to run it as a Windows Service.

Contents
--------
  jasper-report-service.jar          the application
  jasper-report-service.xml          Windows service configuration (WinSW)
  install-service.ps1                installs and starts the service
  uninstall-service.ps1              stops and removes the service
  reports\                           .jrxml report files served by the app
  config\db.properties.example       template for the database connection
                                     (required - see step 2)
  config\service.properties.example  template for the API port and other
                                     runtime settings

Prerequisites
-------------
  - Windows Server or Windows 10/11
  - Java 21 (JDK or JRE). Verify with:
        java -version
    If Java is not installed, install a Java 21 build (e.g. Eclipse
    Temurin 21) before continuing.
  - Administrator rights on the machine, to install a service.
  - Network access to a SQL Server instance. This is required: the
    service does not start without connection settings, and every
    report render opens a connection, including reports that use only
    parameters and have no <queryString>.

Installation
------------
1. Copy this entire folder to the server, e.g.:
       C:\Services\jasper-report-service

2. Configure the database connection (required):
   - Copy config\db.properties.example to config\db.properties
   - Edit config\db.properties and fill in the real SQL Server host,
     database name, username and password.
   - Without this file the service fails to start, logging
     "Failed to configure a DataSource". With it, but pointing at a
     server it cannot reach, the service starts but every render
     fails.

3. Configure the API port (optional):
   - The service listens on port 8080 unless told otherwise.
   - To change it, copy config\service.properties.example to
     config\service.properties and set:
         server.port=9090
   - This file is read at startup and is not touched by jar upgrades,
     so the port survives an update of the service.

4. Download WinSW (the Windows service wrapper this app uses):
       https://github.com/winsw/winsw/releases
   Download the .NET Framework or .NET Core build matching what is
   installed on the server, and save it into this same folder, renamed
   to exactly:
       jasper-report-service.exe

5. Open PowerShell as Administrator, change into this folder, and run:
       .\install-service.ps1

   This registers the "Jasper Report Service" Windows Service, starts
   it immediately, and sets it to start automatically on boot and
   restart automatically 10 seconds after a crash.

6. Verify it's running. On the default port 8080 (use the port from
   config\service.properties if you changed it):
       curl http://localhost:8080/actuator/health

   {"status":"UP"} means the service is running and can reach the
   database. {"status":"DOWN"} (HTTP 503) means it is running but the
   database is unreachable - check config\db.properties and that the
   server allows connections from this machine.

   Logs are written to the logs\ subfolder (created on first start),
   rolled daily.

Updating to a new version
--------------------------
1. Stop the service:
       Stop-Service jasper-report-service
2. Replace jasper-report-service.jar with the new build.
3. Start it again:
       Start-Service jasper-report-service

Uninstalling
------------
Open PowerShell as Administrator in this folder and run:
    .\uninstall-service.ps1

Troubleshooting
---------------
- "java is not recognized": the account running the service (often
  Local System) does not have java on its PATH. Edit
  jasper-report-service.xml and change the <executable> line to the
  full path of java.exe, e.g.:
      C:\Program Files\Java\jdk-21\bin\java.exe
  then reinstall the service.
- Service starts and immediately stops, with "Failed to configure a
  DataSource" in the log: config\db.properties is missing or has no
  spring.datasource.url. See step 2.
- Renders fail with "Failed to obtain a database connection for the
  report": the settings in config\db.properties are wrong, or SQL
  Server is not reachable from this machine. This affects every
  report, not only the ones with a <queryString>.
- Reports directory, config\db.properties and config\service.properties
  are read relative to this folder (the service's working directory) -
  do not move the jar out of this folder on its own.
- "Port 8080 was already in use" on startup: another program on the
  server holds that port. Set a free one in config\service.properties
  and restart the service.
