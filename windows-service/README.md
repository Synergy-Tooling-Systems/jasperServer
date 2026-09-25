# Running as a Windows Service

Uses [WinSW](https://github.com/winsw/winsw) to wrap the executable jar as a Windows Service.

> **Installing on a server?** Use the published release package instead of
> building it yourself: download `jasper-report-service-<version>-windows.zip`
> from the [Releases page](https://github.com/Synergy-Tooling-Systems/jasperServer/releases)
> and follow [docs/INSTALL.md](../docs/INSTALL.md). It is the same layout as
> `dist\` below, with WinSW already bundled. The steps here are for assembling
> that folder by hand from a working copy.

## One-time setup on the target server

1. From the project root, run `.\build-dist.ps1`. This builds the jar and assembles
   everything needed into `.\dist`.
2. Copy `.\dist` to the server, e.g. `C:\Services\jasper-report-service`.
3. In that folder, copy `config\db.properties.example` to `config\db.properties` and
   fill in real SQL Server credentials. This step is required — without it the
   service fails to start with `Failed to configure a DataSource`. To listen on
   something other than port 8080, also copy `config\service.properties.example`
   to `config\service.properties` and set `server.port` there.
4. Download a WinSW release binary (the `.NET Framework` or `.NET Core` build,
   whichever matches what's installed) from
   https://github.com/winsw/winsw/releases and save it into the same folder as
   `jasper-report-service.exe`.
5. Open PowerShell as Administrator, `cd` into the folder, and run:
   ```powershell
   .\install-service.ps1
   ```

This registers and starts the "Jasper Report Service" service, set to start
automatically on boot and restart 10s after a crash. Logs roll daily into
`logs\` in the same folder.

## Updating

To ship a new version: stop the service, replace `jasper-report-service.jar`
with a freshly built one (`build-dist.ps1` regenerates it), then start the
service again.

```powershell
Stop-Service jasper-report-service
# copy new jar over jasper-report-service.jar
Start-Service jasper-report-service
```

## Uninstalling

```powershell
.\uninstall-service.ps1
```

## Notes

- The service's working directory is the folder containing the `.exe`/`.xml`,
  so `reports\`, `config\db.properties` and `config\service.properties` must
  live alongside the jar there — this matches how `application.properties`
  resolves those paths.
- A reachable SQL Server is needed for every render, not just for reports with
  a `<queryString>`: `JasperReportRenderService` opens a connection whenever a
  `DataSource` bean exists. If the database is down the service still runs, but
  renders return `400` and `/actuator/health` reports `DOWN` (HTTP 503).
- The API port comes from `server.port` in `config\service.properties`
  (default 8080). Keeping it in that file rather than in the jar means it
  survives an upgrade; alternatively append `--server.port=9090` to
  `<arguments>` in `jasper-report-service.xml` and reinstall the service.
- If the account running the service doesn't have `java` on its `PATH`
  (common for `LocalSystem`), edit `<executable>` in `jasper-report-service.xml`
  to the full path, e.g. `C:\Program Files\Java\jdk-21\bin\java.exe`.
