# Installing the Jasper Report Service

How to install a released build of the Jasper Report Service on a Windows or
Linux machine. It covers a first install, upgrades and removal.

If you want to run the service from source on a development machine instead,
see [USAGE.md](USAGE.md).

## 1. Get the package

Release packages are published on the
[Releases page](https://github.com/Synergy-Tooling-Systems/jasperServer/releases).
Each release has two packages — take the one matching the target machine:

| File | Install on |
| --- | --- |
| `jasper-report-service-<version>-windows.zip` | Windows Server, or Windows 10/11, as a Windows Service |
| `jasper-report-service-<version>-linux.tar.gz` | Linux, as a systemd service |

Both contain the same application: a self-contained executable jar, the
configuration templates, and the scripts that register it as a service. There
is no installer to run, and nothing is written outside the directory you choose
apart from the service registration itself.

### Verify the download

Each release also publishes `SHA256SUMS.txt`. Check the file you downloaded
against it before installing.

On Windows:

```powershell
Get-FileHash .\jasper-report-service-1.2.3-windows.zip -Algorithm SHA256
```

On Linux:

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing
```

## 2. Check the prerequisites

Both platforms need:

- **Java 21** (a JRE is enough — the service does not compile Java at runtime).
  Verify with `java -version`.
- **A reachable SQL Server instance**, with a login that can read the data your
  reports query. This is not optional: the service refuses to start without
  connection settings, and *every* render opens a connection — including
  reports that only use parameters and have no `<queryString>`.
- **Administrator / root rights**, to register a service.
- **Your `.jrxml` report templates.** The packages ship the `reports` directory
  empty on purpose: report templates are maintained separately from the
  application and are not part of a release. You copy them in during
  installation.

If Java is missing:

```powershell
# Windows — install any Java 21 build, e.g. Eclipse Temurin 21
winget install EclipseAdoptium.Temurin.21.JRE
```

```bash
sudo apt install openjdk-21-jre-headless      # Debian / Ubuntu
sudo dnf install java-21-openjdk-headless     # RHEL / Rocky / Alma
```

## 3. Install

### Windows

1. **Unblock and extract the zip.** Windows marks files downloaded from the
   internet, and PowerShell then refuses to run the extracted scripts:

   ```powershell
   Unblock-File .\jasper-report-service-1.2.3-windows.zip
   Expand-Archive .\jasper-report-service-1.2.3-windows.zip -DestinationPath C:\Services
   ```

   This gives you `C:\Services\jasper-report-service-1.2.3-windows`. Rename it
   to something stable such as `C:\Services\jasper-report-service` — the
   service records this path, so a name without the version in it makes
   upgrades simpler.

2. **Configure the database connection.** In that folder:

   ```powershell
   Copy-Item .\config\db.properties.example .\config\db.properties
   notepad .\config\db.properties
   ```

   Fill in the real host, database, username and password:

   ```properties
   spring.datasource.driver-class-name=com.microsoft.sqlserver.jdbc.SQLServerDriver
   spring.datasource.url=jdbc:sqlserver://dbhost:1433;databaseName=MyReportingDb;encrypt=true;trustServerCertificate=true
   spring.datasource.username=report_user
   spring.datasource.password=...
   ```

   This step is required. Without this file the service starts and immediately
   stops, logging `Failed to configure a DataSource`.

3. **Copy your report templates** into the `reports` subfolder.

4. **Set the port** (optional — the default is 8080). Copy
   `config\service.properties.example` to `config\service.properties` and set
   `server.port` there. Keep it in that file rather than in the service
   definition: it is not touched by upgrades.

5. **Install the service.** Open PowerShell **as Administrator**, `cd` into the
   folder, and run:

   ```powershell
   .\install-service.ps1
   ```

   This registers the **Jasper Report Service** service using the bundled
   [WinSW](https://github.com/winsw/winsw) wrapper, starts it, and sets it to
   start on boot and restart 10 seconds after a crash. Logs roll daily into a
   `logs` subfolder.

   If PowerShell refuses to run the script, allow it for that window only:

   ```powershell
   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
   ```

### Linux

1. **Extract the package** and change into it:

   ```bash
   tar -xzf jasper-report-service-1.2.3-linux.tar.gz
   cd jasper-report-service-1.2.3-linux
   ```

2. **Run the installer** as root:

   ```bash
   sudo ./install-service.sh
   ```

   It creates the `jasper` system account, installs into
   `/opt/jasper-report-service`, and writes the systemd unit. On a **first**
   install it deliberately stops there without starting the service, because
   the database is not configured yet.

   To install elsewhere, or run as a different account:

   ```bash
   sudo INSTALL_DIR=/srv/jasper SERVICE_USER=reports ./install-service.sh
   ```

3. **Configure the database connection:**

   ```bash
   cd /opt/jasper-report-service
   sudo cp config/db.properties.example config/db.properties
   sudo nano config/db.properties
   sudo chown jasper:jasper config/db.properties
   sudo chmod 640 config/db.properties
   ```

   The last two commands matter: the file holds a password, and the service
   account has to be able to read it.

4. **Copy your report templates** in:

   ```bash
   sudo cp /path/to/reports/*.jrxml /opt/jasper-report-service/reports/
   sudo chown -R jasper:jasper /opt/jasper-report-service/reports
   ```

5. **Set the port** (optional — the default is 8080):

   ```bash
   sudo cp config/service.properties.example config/service.properties
   sudo nano config/service.properties     # server.port=9090
   ```

   Ports below 1024 will not work as shipped, because the service runs
   unprivileged. Put a reverse proxy in front, or add
   `AmbientCapabilities=CAP_NET_BIND_SERVICE` to the unit.

6. **Start it:**

   ```bash
   sudo systemctl enable --now jasper-report-service
   ```

## 4. Verify the install

Check the service is up and can reach the database (use your port if you
changed it):

```bash
curl http://localhost:8080/actuator/health
```

- `{"status":"UP"}` — running, and the database is reachable.
- `{"status":"DOWN"}` with HTTP 503 — running, but the database is not
  reachable. Check `config/db.properties` and that SQL Server accepts
  connections from this machine.
- Connection refused — the service is not running. See
  [Troubleshooting](#troubleshooting).

Then render a report, to confirm the templates are found. `reportFileName` is
the filename **including the `.jrxml` extension**, relative to the `reports`
directory:

```bash
curl -X POST http://localhost:8080/api/reports/render \
  -H "Content-Type: application/json" \
  -d "{\"reportFileName\": \"po.jrxml\", \"parameters\": [{\"PoNumber\": \"12345\"}]}" \
  --output test.pdf
```

A PDF comes back on success. `Report file not found` means the `.jrxml` is not
in the `reports` directory, or is not readable by the service account.

See [USAGE.md](USAGE.md) for the full API.

## 5. Where things live

The service resolves its configuration **relative to its working directory**,
which is the directory holding the jar. Do not move the jar out of its folder
on its own.

| Path | What it is |
| --- | --- |
| `jasper-report-service.jar` | The application. Replaced on upgrade. |
| `config/db.properties` | Database connection. **Required.** You create it; never overwritten by an upgrade. |
| `config/service.properties` | Port and other runtime settings. Optional; never overwritten by an upgrade. |
| `reports/` | Your `.jrxml` templates. Never overwritten by an upgrade. |
| `VERSION` | The version installed. |
| `logs/` (Windows only) | Service logs, rolled daily. |

On Linux the logs go to the journal instead:

```bash
journalctl -u jasper-report-service -f
```

Report templates are read per request, so editing a `.jrxml` takes effect on
the next render — no restart needed. Changing anything under `config/` does
need a restart.

## 6. Upgrading

Your configuration and report templates are never part of an upgrade — only
the jar changes.

**Windows.** Download and unblock the new zip, extract it somewhere temporary,
then:

```powershell
Stop-Service jasper-report-service
Copy-Item <new>\jasper-report-service.jar C:\Services\jasper-report-service\ -Force
Copy-Item <new>\VERSION C:\Services\jasper-report-service\ -Force
Start-Service jasper-report-service
```

If a release changes `jasper-report-service.xml` or the install scripts, copy
those across too and re-run `.\install-service.ps1` — the release notes say
when that is needed.

**Linux.** Extract the new package and run its installer. It stops the service,
replaces the jar, and starts it again:

```bash
tar -xzf jasper-report-service-1.3.0-linux.tar.gz
cd jasper-report-service-1.3.0-linux
sudo ./install-service.sh
```

Confirm the new version is running afterwards:

```bash
cat /opt/jasper-report-service/VERSION
curl http://localhost:8080/actuator/health
```

## 7. Uninstalling

**Windows** — from an elevated PowerShell in the install folder:

```powershell
.\uninstall-service.ps1
```

The folder is left in place, so `config\db.properties` and `reports\` survive.
Delete the folder by hand to remove those too.

**Linux:**

```bash
sudo /opt/jasper-report-service/uninstall-service.sh
```

This removes the service and keeps `/opt/jasper-report-service`. To delete the
install directory, the credentials and the report templates as well:

```bash
sudo /opt/jasper-report-service/uninstall-service.sh --purge
```

## Troubleshooting

**The service starts and immediately stops, logging `Failed to configure a
DataSource`.** `config/db.properties` is missing, in the wrong directory, or
has no `spring.datasource.url`. It must sit next to the jar, in `config/`.

**Renders fail with `Failed to obtain a database connection for the report`.**
The settings in `config/db.properties` are wrong, or SQL Server is not
reachable from this machine. This affects every report, not only ones with a
`<queryString>`. Check the host and port, that the SQL Server login works, and
that a firewall is not in the way.

**`Port 8080 was already in use` at startup.** Another program holds the port.
Set a free one in `config/service.properties` and restart.

**Renders fail with `Report file not found`.** The `.jrxml` is not in the
`reports` directory, or `reportFileName` is missing the `.jrxml` extension —
it is required. On Linux, also check the file is readable by the service
account:

```bash
sudo -u jasper ls -l /opt/jasper-report-service/reports
```

**Windows: `java is not recognized` in the service log.** The account running
the service (often Local System) has no `java` on its `PATH`. Edit
`jasper-report-service.xml`, set `<executable>` to the full path — for example
`C:\Program Files\Java\jdk-21\bin\java.exe` — then re-run
`.\install-service.ps1`.

**Windows: the service will not install on an older server.** The bundled
`jasper-report-service.exe` is the WinSW .NET Framework 4.6.1 build, which
needs the .NET Framework that ships with Windows Server 2016 and Windows 10
1607 and later. On anything older, download `WinSW-x64.exe` from the
[WinSW releases](https://github.com/winsw/winsw/releases), save it into the
folder as `jasper-report-service.exe` replacing the bundled one, and re-run
the install script. That build is self-contained and needs no .NET Framework.

**Linux: the unit points at the wrong Java.** The installer records the
absolute path to `java` found at install time. If Java moves, re-run
`install-service.sh`, or edit `ExecStart` in
`/etc/systemd/system/jasper-report-service.service` and run
`sudo systemctl daemon-reload`.

**Linux: `systemctl status` shows the service restarting in a loop.** Read the
actual error:

```bash
journalctl -u jasper-report-service -n 100 --no-pager
```
