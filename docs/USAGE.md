# Jasper Report Service — Usage

A small Spring Boot service that compiles a [JasperReports](https://jasperreports.sourceforge.net/) `.jrxml` report definition from the local `reports` directory and renders it to PDF against a SQL Server database.

## Requirements

- Java 21
- Maven (or use the bundled `mvnw` / `mvnw.cmd` wrapper)
- A reachable SQL Server instance, configured in `config/db.properties` — the service does not start without it (see [Configuring the database connection](#configuring-the-database-connection))

## Running the service

```bash
./mvnw spring-boot:run
```

On Windows:

```cmd
mvnw.cmd spring-boot:run
```

The service listens on port `8080` by default.

## Configuring the port

The port is read from the `server.port` property, which can be set in any of these ways (each one overrides the ones below it):

1. A command line argument — handy for a one-off run:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=9090
   java -jar jasper-report-service.jar --server.port=9090
   ```

2. The `SERVER_PORT` environment variable.
3. `config/service.properties`, the per-machine settings file — this is the one to use for an installed service, since it survives a jar upgrade:

   ```properties
   server.port=9090
   ```

   Copy [`config/service.properties.example`](../config/service.properties.example) to `config/service.properties` (same directory) and edit it. The file is gitignored, loaded automatically at startup, and entirely optional — without it the default applies.

Setting `server.port=0` picks a free port at random, which the startup log then reports.


## Configuring the database connection

The service requires a database connection. Without `config/db.properties` it fails at startup with:

```
APPLICATION FAILED TO START
Failed to configure a DataSource: 'url' attribute is not specified and no embedded datasource could be configured.
```

1. Copy [`config/db.properties.example`](../config/db.properties.example) to `config/db.properties` (same directory).
2. Fill in your SQL Server connection details:

   ```properties
   spring.datasource.driver-class-name=com.microsoft.sqlserver.jdbc.SQLServerDriver
   spring.datasource.url=jdbc:sqlserver://<host>:<port>;databaseName=<db>;encrypt=true;trustServerCertificate=true
   spring.datasource.username=<user>
   spring.datasource.password=<password>
   ```

Like `config/service.properties`, `config/db.properties` is gitignored and loaded automatically at startup.

The server also has to be reachable, whatever the report does. Every render opens a connection from the pool, so with the database down even a report that has no `<queryString>` and uses only parameters fails with `400` and `Failed to obtain a database connection for the report`. `/actuator/health` returns `503` with `{"status":"DOWN"}` in that state, while the service itself stays up.

`JasperReportRenderService` takes an `Optional<DataSource>` and falls back to an empty data source when none is present, but that path is currently unreachable: Spring Boot's `DataSourceAutoConfiguration` fails first when no `spring.datasource.url` is set, which is why the file is required rather than optional.

## Reports directory

`.jrxml` files are read by name from the directory configured by `reports.directory` (default `./reports`, relative to the working directory the app is started from, and overridable in `config/service.properties` like the port). Drop report definitions there — the render endpoint refers to them by filename only.

## Subreports

A report can pull in another report through `<subreportExpression>`. Reference the `.jrxml` file directly — the service compiles subreports per request, exactly as it does the main report, so there are no `.jasper` files to generate or keep in sync:

```xml
<subreportExpression><![CDATA["purchasing/purchaseOrderProducts_v2.jrxml"]]></subreportExpression>
```

The location is resolved, in that order, relative to:

1. the directory of the report that declares it — `products.jrxml` sitting next to its parent, which is what Jaspersoft Studio writes;
2. the reports directory — `purchasing/products.jrxml`;
3. the working directory the service was started from — `reports/purchasing/products.jrxml`.

The resolved file must stay inside the reports directory; anything outside it is refused. Nesting works to any depth, and a reference to an already-compiled `.jasper` file is resolved the same way, so existing reports keep working.

A reference that resolves to nothing fails fast with `400` and `Subreport not found in the reports directory: <name>`. Without this handling JasperReports only ever deserializes a compiled `.jasper` for a subreport, so a `.jrxml` there failed mid-render with `StreamCorruptedException: invalid stream header: 3C3F786D` — the `<?xm` of the XML it was handed.

## API

### `POST /api/reports/render`

Renders a `.jrxml` report from the reports directory to PDF.

**Content type:** `application/json`

| Field            | Required | Description                                                                                          |
|------------------|----------|--------------------------------------------------------------------------------------------------------|
| `reportFileName` | Yes      | Name of a `.jrxml` file in the reports directory, e.g. `"po.jrxml"`.                                   |
| `parameters`     | No       | An array of single-key objects, each mapping one report parameter name to its value.                   |

**Response:** `200 OK` with `Content-Type: application/pdf` and a `Content-Disposition: attachment` header. The response filename matches `reportFileName` with `.pdf` in place of `.jrxml`.

**Example:**

```bash
curl -X POST http://localhost:8080/api/reports/render \
  -H "Content-Type: application/json" \
  -d '{"reportFileName":"po.jrxml","parameters":[{"title":"Monthly Summary"},{"count":5}]}' \
  -o report.pdf
```

### Errors

| Status | Cause                                                                 |
|--------|------------------------------------------------------------------------|
| `400 Bad Request` | Missing/non-`.jrxml` `reportFileName`, report file not found, a subreport that cannot be resolved, a database connection failure, or a JasperReports compile/fill/export failure |

Error responses are JSON:

```json
{
  "timestamp": "2026-09-22T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "reportFileName must have a .jrxml extension"
}
```

## Notes

- JasperReports is pinned to `6.21.5` — version 7.x uses an incompatible `.jrxml` format for reports produced by Jaspersoft Studio.
- The service does not persist generated PDFs; rendering happens in-memory per request.
