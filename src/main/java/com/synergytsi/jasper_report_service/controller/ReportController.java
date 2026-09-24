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

    private final JasperReportRenderService renderService;
    private final Path reportsDirectory;

    public ReportController(
            JasperReportRenderService renderService,
            @Value("${reports.directory:./reports}") String reportsDirectory) {
        this.renderService = renderService;
        this.reportsDirectory = Path.of(reportsDirectory).toAbsolutePath().normalize();
    }

    /**
     * Renders a .jrxml report from the configured reports directory to PDF.
     *
     * @param request {@code reportFileName} (a file in the reports directory) plus optional
     *                {@code parameters}, e.g.
     *                {@code {"reportFileName": "po.jrxml", "parameters": [{"title": "My Report"}]}}
     */
    @PostMapping(value = "/render", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> renderReport(@RequestBody RenderReportRequest request) {
        String filename = request.reportFileName();
        if (filename == null || filename.isBlank()) {
            throw new ReportGenerationException("reportFileName is required");
        }
        if (!filename.toLowerCase().endsWith(JRXML_EXTENSION)) {
            throw new ReportGenerationException("reportFileName must have a .jrxml extension");
        }

        Path reportPath = resolveReportPath(filename);
        if (!Files.isRegularFile(reportPath)) {
            throw new ReportGenerationException("Report file not found: " + filename);
        }

        Map<String, Object> parameters = flattenParameters(request.parameters());

        byte[] pdfBytes = renderService.renderPdf(reportPath, parameters);

        String outputFilename = filename.substring(0, filename.length() - JRXML_EXTENSION.length()) + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(outputFilename).build().toString())
                .body(pdfBytes);
    }

    private Path resolveReportPath(String filename) {
        Path resolved = reportsDirectory.resolve(filename).normalize();
        if (!resolved.startsWith(reportsDirectory)) {
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
