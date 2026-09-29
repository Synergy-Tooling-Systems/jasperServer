package com.synergytsi.jasper_report_service.controller;

import com.synergytsi.jasper_report_service.dto.RenderReportRequest;
import com.synergytsi.jasper_report_service.exception.ReportGenerationException;
import com.synergytsi.jasper_report_service.service.JasperReportRenderService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final String JRXML_EXTENSION = ".jrxml";

    /** Request header selecting the reports directory; {@value #DEV_MODE} selects {@code reports.devDirectory}. */
    static final String MODE_HEADER = "mode";
    static final String DEV_MODE = "dev";

    private final JasperReportRenderService renderService;
    private final Path reportsDirectory;
    /** Null when {@code reports.devDirectory} is not configured. */
    private final Path devReportsDirectory;

    public ReportController(
            JasperReportRenderService renderService,
            @Value("${reports.directory:./reports}") String reportsDirectory,
            @Value("${reports.devDirectory:}") String devReportsDirectory) {
        this.renderService = renderService;
        this.reportsDirectory = Path.of(reportsDirectory).toAbsolutePath().normalize();
        this.devReportsDirectory = devReportsDirectory.isBlank()
                ? null
                : Path.of(devReportsDirectory).toAbsolutePath().normalize();
    }

    /**
     * Renders a .jrxml report from the configured reports directory to PDF.
     *
     * @param request {@code reportFileName} (a file in the reports directory) plus optional
     *                {@code parameters}, e.g.
     *                {@code {"reportFileName": "po.jrxml", "parameters": [{"title": "My Report"}]}}
     * @param mode    optional; {@code dev} reads the report from {@code reports.devDirectory}
     *                instead of {@code reports.directory}
     */
    @PostMapping(value = "/render", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> renderReport(
            @RequestBody RenderReportRequest request,
            @RequestHeader(name = MODE_HEADER, required = false) String mode) {
        String filename = request.reportFileName();
        if (filename == null || filename.isBlank()) {
            throw new ReportGenerationException("reportFileName is required");
        }
        if (!filename.toLowerCase().endsWith(JRXML_EXTENSION)) {
            throw new ReportGenerationException("reportFileName must have a .jrxml extension");
        }

        Path baseDirectory = reportsDirectoryFor(mode);
        Path reportPath = resolveReportPath(baseDirectory, filename);
        if (!Files.isRegularFile(reportPath)) {
            throw new ReportGenerationException("Report file not found: " + filename);
        }

        Map<String, Object> parameters = flattenParameters(request.parameters());

        byte[] pdfBytes = renderService.renderPdf(baseDirectory, reportPath, parameters);

        String outputFilename = filename.substring(0, filename.length() - JRXML_EXTENSION.length()) + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(outputFilename).build().toString())
                .body(pdfBytes);
    }

    private Path reportsDirectoryFor(String mode) {
        if (mode == null || !mode.trim().equalsIgnoreCase(DEV_MODE)) {
            return reportsDirectory;
        }
        if (devReportsDirectory == null) {
            throw new ReportGenerationException("mode: dev was requested but reports.devDirectory is not configured");
        }
        return devReportsDirectory;
    }

    private Path resolveReportPath(Path baseDirectory, String filename) {
        Path resolved = baseDirectory.resolve(filename).normalize();
        if (!resolved.startsWith(baseDirectory)) {
            throw new ReportGenerationException("Invalid reportFileName: " + filename);
        }
        return resolved;
    }

    private Map<String, Object> flattenParameters(List<Map<String, Object>> parameters) {
        // JasperReports mutates the parameters map while filling the report
        // (e.g. it injects the data source under REPORT_DATA_SOURCE), so this
        // must be a mutable map, not an immutable Map.of()/emptyMap().
        Map<String, Object> result = new HashMap<>();
        if (parameters == null) {
            return result;
        }
        for (Map<String, Object> entry : parameters) {
            if (entry != null) {
                result.putAll(entry);
            }
        }
        return result;
    }
}
