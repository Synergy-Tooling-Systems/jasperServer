Jasper Report Service - Windows Service Installation
======================================================

This folder is a self-contained distribution of the Jasper Report Service.
It contains everything needed to run it as a Windows Service.

Contents
--------
  jasper-report-service.jar          the application
  jasper-report-service.exe          WinSW, the Windows service wrapper
                                     (included in the release package; see
                                     step 4 if it is missing)
  jasper-report-service.xml          Windows service configuration (WinSW)
  install-service.ps1                installs and starts the service
  uninstall-service.ps1              stops and removes the service
  reports\                           .jrxml report files served by the app
                                     (ships empty - see reports\README.txt)
  config\db.properties.example       template for the database connection
                                     (required - see step 2)
  config\service.properties.example  template for the API port and other
                                     runtime settings
  VERSION                            the version in this package
  third-party\                       WinSW's license

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

   If you downloaded the .zip, unblock it before extracting - Windows
   otherwise marks the extracted .ps1 files as coming from the internet
   and refuses to run them:
       Unblock-File .\jasper-report-service-<version>-windows.zip

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

4. Check that jasper-report-service.exe is in this folder.

   The release package downloaded from GitHub already includes it, and
   there is nothing to do here. It is only missing if this folder was
   assembled locally with build-dist.ps1, in which case download WinSW
   from:
       https://github.com/winsw/winsw/releases
   and save it into this folder renamed to exactly:
       jasper-report-service.exe

   The bundled build is WinSW.NET461.exe, which needs the .NET
   Framework 4.6.1 that ships with Windows Server 2016 and Windows 10
   1607 and later. On an older server, replace it with WinSW-x64.exe
   from the same release - that build is self-contained and needs no
   .NET Framework.

5. Add your report templates:
   - Copy your .jrxml files into the reports\ subfolder. The package
     ships it empty; report templates are maintained separately from
     the application. See reports\README.txt.

6. Open PowerShell as Administrator, change into this folder, and run:
       .\install-service.ps1

   This registers the "Jasper Report Service" Windows Service, starts
   it immediately, and sets it to start automatically on boot and
   restart automatically 10 seconds after a crash.

7. Verify it's running. On the default port 8080 (use the port from
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
2. Replace jasper-report-service.jar with the new build, and copy the
   new VERSION file alongside it.
3. Start it again:
       Start-Service jasper-report-service

Leave config\ and reports\ alone - they are not part of the upgrade.

Uninstalling
------------
Open PowerShell as Administrator in this folder and run:
    .\uninstall-service.ps1

This removes the service but leaves the folder, including
config\db.properties and reports\, in place. Delete the folder by hand
to remove those as well.

Troubleshooting
---------------
- ".\install-service.ps1 cannot be loaded because running scripts is
  disabled on this system": PowerShell's execution policy is blocking
  it. In the same elevated window, run:
      Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
  then run the script again.
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
- Renders fail with "Report file not found": the .jrxml is not in the
  reports\ subfolder of this folder. See step 5.
- Reports directory, config\db.properties and config\service.properties
  are read relative to this folder (the service's working directory) -
  do not move the jar out of this folder on its own.
- "Port 8080 was already in use" on startup: another program on the
  server holds that port. Set a free one in config\service.properties
  and restart the service.
