Report templates go in this directory.
======================================

The service renders .jrxml files it finds here. A render request names the file
including its extension, so this body:

    {"reportFileName": "po.jrxml", "parameters": [{"title": "My Report"}]}

reads po.jrxml from this directory. Subdirectories work too - a reportFileName
of "purchasing/po.jrxml" reads purchasing/po.jrxml. A path that would escape
this directory is rejected.

Subreports referenced by a .jrxml are resolved relative to the report that
declares them, or to this directory, so keep them alongside their parent
report.

The release package ships this directory empty on purpose - report templates
are maintained separately from the application and are not part of the build.
Copy your .jrxml files in here after installing, or point the service at a
directory you already keep them in by setting reports.directory in
config/service.properties, for example:

    reports.directory=/var/lib/jasper-report-service/reports

    reports.directory=C:/ReportTemplates

The path is resolved relative to the service's working directory, which is the
directory holding the jar. An absolute path avoids any doubt.

Changes to a .jrxml take effect on the next render - the service compiles the
template per request, so it does not need restarting when a report changes.
