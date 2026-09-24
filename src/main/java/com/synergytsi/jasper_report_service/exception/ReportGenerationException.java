package com.synergytsi.jasper_report_service.exception;

/**
 * Raised when an uploaded report definition cannot be compiled, filled,
 * or exported to PDF.
 */
public class ReportGenerationException extends RuntimeException {

    public ReportGenerationException(String message) {
        super(message);
    }

    public ReportGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
