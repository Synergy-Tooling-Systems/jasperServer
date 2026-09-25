# Jasper Report Service — REST API

A small HTTP service that renders [JasperReports](https://jasperreports.sourceforge.net/) report
definitions to PDF. You send it the name of a `.jrxml` file that already lives on the server plus the
report's parameters; it compiles the report, fills it against the server's SQL Server database, and
returns the PDF bytes in the response.

This page is the reference for **calling** the service. To run or install it, see
[docs/USAGE.md](docs/USAGE.md) and [docs/INSTALL.md](docs/INSTALL.md).

Machine-readable contracts:

- Request body: [docs/api/render-report-request.schema.json](docs/api/render-report-request.schema.json)
- Error body: [docs/api/error-response.schema.json](docs/api/error-response.schema.json)

---

## At a glance

| | |
| --- | --- |
| Base URL | `http://<host>:8080` (port is per-installation, see [docs/USAGE.md](docs/USAGE.md#configuring-the-port)) |
| Endpoints | `POST /api/reports/render`, `GET /actuator/health` |
| Authentication | **None.** The service is unauthenticated — deploy it on a trusted network only. |
| Request format | `application/json` |
| Success response | `200` with `Content-Type: application/pdf` and the PDF as the raw body |
| Errors | `400` for everything the service rejects, with a JSON body |
| Report discovery | **No endpoint lists the available reports.** Callers must be told the filenames. |

---

## Quick start

```bash
curl -X POST http://localhost:8080/api/reports/render \
  -H "Content-Type: application/json" \
  -d '{"reportFileName":"purchasing/PurchaseOrder.jrxml","parameters":[{"poNumber":"PO-10432"}]}' \
  -o purchase-order.pdf
```

PowerShell:

```powershell
$body = @{
    reportFileName = 'purchasing/PurchaseOrder.jrxml'
    parameters     = @(@{ poNumber = 'PO-10432' })
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Uri http://localhost:8080/api/reports/render -Method Post `
    -ContentType 'application/json' -Body $body -OutFile purchase-order.pdf
```

Python:

```python
import requests

response = requests.post(
    "http://localhost:8080/api/reports/render",
    json={
        "reportFileName": "purchasing/PurchaseOrder.jrxml",
        "parameters": [{"poNumber": "PO-10432"}, {"copies": 2}],
    },
    timeout=300,
)
response.raise_for_status()          # a 400 body is JSON: {"timestamp","status","error","message"}
open("purchase-order.pdf", "wb").write(response.content)
```

JavaScript:

```javascript
const response = await fetch("http://localhost:8080/api/reports/render", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    reportFileName: "purchasing/PurchaseOrder.jrxml",
    parameters: [{ poNumber: "PO-10432" }],
  }),
});
if (!response.ok) throw new Error((await response.json()).message);
const pdf = await response.arrayBuffer();
```

---

## `POST /api/reports/render`

Renders one report to PDF.

**Request headers**

| Header | Value |
| --- | --- |
| `Content-Type` | `application/json` — anything else is rejected with `415` and an empty body |

**Request body**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `reportFileName` | string | yes | Path of a `.jrxml` file relative to the server's reports directory, e.g. `"po.jrxml"` or `"purchasing/PurchaseOrder.jrxml"`. |
| `parameters` | array of objects | no | Report parameters. Each entry holds one `{"parameterName": value}` pair. |

Unknown top-level fields are ignored, but they are not part of the contract — don't send them.

```json
{
  "reportFileName": "purchasing/PurchaseOrder.jrxml",
  "parameters": [
    { "poNumber": "PO-10432" },
    { "copies": 2 },
    { "includePrices": true }
  ]
}
```

**Success response**

```
200 OK
Content-Type: application/pdf
Content-Disposition: attachment; filename="purchasing/PurchaseOrder.pdf"
<PDF bytes>
```

The suggested filename is `reportFileName` with `.pdf` in place of `.jrxml`, and it keeps any
directory prefix you sent, so most clients should set their own output filename rather than trust it.

Nothing is stored server-side: each call compiles and renders from scratch, and the PDF exists only
in that response.

### `reportFileName` rules

1. Required and non-blank.
2. Must end in `.jrxml`. The extension check ignores case; the rest of the lookup is as
   case-sensitive as the server's filesystem (a Windows host matches `PO.jrxml` to `po.jrxml`, a
   Linux host does not).
3. Use forward slashes for subdirectories. Backslashes are not translated and will fail.
4. Resolved inside the reports directory. Anything that escapes it (`../`, an absolute path) is
   rejected with `Invalid reportFileName`.
5. The file must exist on the server. There is no upload endpoint and no way to list what's there —
   report definitions are deployed by whoever administers the service.

### Parameter rules

`parameters` is an **array of single-pair objects**, not one object:

```json
"parameters": [{ "poNumber": "PO-10432" }, { "copies": 2 }]   // correct
"parameters": { "poNumber": "PO-10432" }                      // 400, empty body
```

The entries are merged into one parameter map in order:

- A name repeated in a later entry overwrites the earlier one.
- Several pairs in a single object also work, but one pair per entry is the documented form.
- Names the report does not declare are silently ignored — a typo in a parameter name does not error,
  it just leaves that parameter unset.
- Omitting a parameter lets the report's own `<defaultValueExpression>` apply. Sending it explicitly
  as `null` does **not**: it overrides the default with null.

**Types are matched strictly. The service converts nothing.** The JSON value's type must match the
class the report declares for that parameter, or the render fails with `400` and
`Failed to compile or render the report: Error evaluating expression ...`:

| JSON you send | Java class it becomes | Report parameter classes that accept it |
| --- | --- | --- |
| `"text"` | `java.lang.String` | `java.lang.String`, `java.lang.Object` |
| `42` | `java.lang.Integer` | `java.lang.Integer`, `java.lang.Number`, `java.lang.Object` |
| `9999999999` (beyond 32-bit) | `java.lang.Long` | `java.lang.Long`, `java.lang.Number`, `java.lang.Object` |
| `9.5` | `java.lang.Double` | `java.lang.Double`, `java.lang.Number`, `java.lang.Object` |
| `true` / `false` | `java.lang.Boolean` | `java.lang.Boolean`, `java.lang.Object` |
| `[1, 2, 3]` | `java.util.ArrayList` | `java.util.List`, `java.util.Collection`, `java.lang.Object` |
| `{"a": 1}` | `java.util.LinkedHashMap` | `java.util.Map`, `java.lang.Object` |
| `null` | `null` | any |

Consequences worth knowing before you debug a `400`:

- `"42"` into an `java.lang.Integer` parameter fails. So does `42` into a `java.lang.String`
  parameter, and `9` into a `java.lang.Double` parameter (`9.0` works).
- `java.math.BigDecimal` parameters cannot be filled from JSON at all — no JSON number maps to it.
- **Dates cannot be passed.** No JSON type maps to `java.util.Date` / `java.sql.Timestamp`; a
  date string and epoch millis both fail. A report that needs a date from a caller has to declare
  that parameter as `java.lang.String` and convert it internally, which is a change to the report,
  not to the request.

If you don't know how a report declares a parameter, ask for its `.jrxml` — the
`<parameter name="..." class="..."/>` elements are the contract.

### A `200` can still be an empty PDF

The service fills every report through a database connection. A report with no `<queryString>` has
no rows, so unless it sets `whenNoDataType="AllSectionsNoDetail"` JasperReports produces a
**zero-page PDF** and the response is still `200` — a valid, blank PDF of a few hundred bytes.

A parameters-only report that comes back suspiciously small is almost always this, and the fix
belongs in the report definition. Clients that care should treat a PDF under ~1 KB as suspect.

---

## Errors

Everything the service itself rejects is `400 Bad Request` with this JSON body
([schema](docs/api/error-response.schema.json)):

```json
{
  "timestamp": "2026-09-25T14:38:18.856060800Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Report file not found: does-not-exist.jrxml"
}
```

| `message` | Cause | Fix |
| --- | --- | --- |
| `reportFileName is required` | Field missing, null or blank | Send the field |
| `reportFileName must have a .jrxml extension` | e.g. `"po.pdf"`, `"po.jasper"` | Name the `.jrxml` source |
| `Report file not found: <name>` | No such file in the reports directory | Check the name, the subdirectory and the case |
| `Invalid reportFileName: <name>` | Path escapes the reports directory | Send a path relative to it, no `..` |
| `Subreport not found in the reports directory: <name> (referenced by <parent>)` | The report includes a subreport that isn't deployed | Server-side: deploy the subreport |
| `Subreport cycle: <name> includes itself` | The report includes itself, directly or transitively | Server-side: fix the report |
| `Failed to read report file: <name>` | The file can't be read | Server-side: permissions |
| `Failed to obtain a database connection for the report: ...` | Database unreachable or misconfigured | Retry; check `/actuator/health` |
| `Failed to compile or render the report: Error evaluating expression ...` | Usually a parameter whose type doesn't match the report's declaration | See [Parameter rules](#parameter-rules) |
| `Failed to compile or render the report: Error preparing statement ... <SQL>` | The report's SQL failed | Server-side: report or schema |
| `Failed to compile or render the report: ...` (other) | The `.jrxml` doesn't compile, or the fill failed | Server-side: report definition |

Responses with **no body at all**, produced before the request reaches the endpoint:

| Status | Cause |
| --- | --- |
| `400` | Malformed JSON, an empty body, or `parameters` sent as an object instead of an array |
| `404` | Wrong path — the only render path is `/api/reports/render` |
| `405` | Wrong method — `POST` only |
| `415` | Missing or wrong `Content-Type` |

So: parse a `400` body as JSON only when `Content-Type` is `application/json`, and fall back to the
status code when it is empty. A `5xx` means an unexpected server-side failure, not a bad request.

### Retrying

Rendering is read-only and has no side effects, so a retry is always safe. Retry with backoff on
`5xx`, on a connection failure, and on `Failed to obtain a database connection for the report` (the
database may be briefly down). Every other `400` is deterministic — retrying it verbatim gives the
same error.

---

## `GET /actuator/health`

```bash
curl http://localhost:8080/actuator/health
```

`200` with `{"groups":["liveness","readiness"],"status":"UP"}` when the service and its database are
reachable. `503` with `{"status":"DOWN"}` when the database is unreachable — the service still
answers, but renders will fail. Use this to tell "service is down" from "database is down" before
reporting a render failure.

---

## Performance notes

- Reports are compiled on **every** request; nothing is cached. A large report with subreports can
  take seconds.
- There is no server-side render timeout and no rate limiting. Set a generous client timeout
  (start at 300 s for big reports) rather than a short one plus retries — a retry doubles the work.
- The whole PDF is built in memory and sent as one response, so very large reports are bounded by
  the server's heap.

---

## For AI agents

Read this section as the operational contract; everything in it is verified against the service's
behaviour.

**Before the first call, get these three facts from the user or their configuration.** Don't guess
them:

1. the base URL, including the port (`8080` is only the default);
2. the exact `reportFileName`, relative to the reports directory, with its subdirectory prefix;
3. for each parameter you intend to send, the name and the `class` the `.jrxml` declares for it.

If a report definition is available in the repository you're working in, read its
`<parameter name="..." class="..."/>` elements instead of guessing. If it isn't, ask for the file or
for the parameter list before sending a call that will fail on types.

**Build the request** against
[docs/api/render-report-request.schema.json](docs/api/render-report-request.schema.json):

```json
{
  "reportFileName": "<path/to/report.jrxml>",
  "parameters": [{ "<name>": <value> }, { "<name>": <value> }]
}
```

Hard rules, in the order they bite:

1. `parameters` is an array of objects. An object there is a `400` with an empty body.
2. `reportFileName` ends in `.jrxml`, uses forward slashes, contains no `..`, and is relative to the
   reports directory — never an absolute or OS-native path.
3. Match parameter types exactly: quote strings, leave numbers unquoted, use `9.0` not `9` for a
   `Double`, and never send a date in any form. See the type table above.
4. Treat the response body as **binary**. Don't decode it as text, don't log it, and write it to a
   file or pass it on as bytes. It is the PDF itself, not JSON containing a PDF.
5. A `200` whose body is under ~1 KB is probably a zero-page PDF — report that as
   "the report produced no pages", not as success. See
   [A `200` can still be an empty PDF](#a-200-can-still-be-an-empty-pdf).
6. On a non-`200`: if `Content-Type` is `application/json`, the `message` field says what's wrong and
   is worth surfacing verbatim; otherwise report the status code. Don't retry a deterministic `400`
   with the same body — fix the request or ask.

**Don't** try to upload, list, delete or cache reports through this API: `POST /api/reports/render`
and `GET /actuator/health` are the only endpoints. Report definitions are deployed to the server's
filesystem out of band, so "the report doesn't exist" is a request for an administrator, not
something to work around by sending a different path.

---

## Related documentation

| Document | Covers |
| --- | --- |
| [docs/USAGE.md](docs/USAGE.md) | Running from source, port and database configuration, the reports directory, subreports |
| [docs/INSTALL.md](docs/INSTALL.md) | Installing a release as a Windows or systemd service |
| [docs/RELEASING.md](docs/RELEASING.md) | Publishing a new release |
| [windows-service/README.md](windows-service/README.md) | The Windows service wrapper |
