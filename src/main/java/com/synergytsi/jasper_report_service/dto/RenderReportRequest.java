package com.synergytsi.jasper_report_service.dto;

import java.util.List;
import java.util.Map;

/**
 * Request body for {@code POST /api/reports/render}.
 *
 * @param reportFileName name of a .jrxml file in the configured reports directory
 * @param parameters     report parameters, each entry a single {@code {parameterName: parameterValue}} object
 */
public record RenderReportRequest(
        String reportFileName,
        List<Map<String, Object>> parameters) {
}
