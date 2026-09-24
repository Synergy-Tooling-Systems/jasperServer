package com.synergytsi.jasper_report_service.service;

import com.synergytsi.jasper_report_service.exception.ReportGenerationException;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JRExpression;
import net.sf.jasperreports.engine.JRSubreport;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.design.JRDesignExpression;
import net.sf.jasperreports.engine.design.JRDesignParameter;
import net.sf.jasperreports.engine.design.JRDesignSubreport;
import net.sf.jasperreports.engine.design.JRDesignSubreportParameter;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.util.JRElementsVisitor;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.engine.util.JRVisitorSupport;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles a .jrxml report definition and renders it to PDF.
 */
@Service
public class JasperReportRenderService {

    private static final String JRXML_EXTENSION = ".jrxml";
    private static final String COMPILED_EXTENSION = ".jasper";

    /** Matches a subreport expression that is nothing but a string literal, e.g. {@code "products.jrxml"}. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\\s*\"([^\"]*)\"\\s*");

    /** Prefix for the generated parameters that carry compiled subreports into the fill. */
    private static final String SUBREPORT_PARAMETER_PREFIX = "JRXML_SUBREPORT_";

    private final DataSource dataSource;
    private final Path reportsDirectory;

    /**
     * @param dataSource       the app's configured {@link DataSource}, supplied by a local
     *                         {@code config/db.properties} file (see application.properties).
     *                         In practice it is always present: without
     *                         {@code spring.datasource.url}, Spring Boot's DataSource
     *                         auto-configuration fails the app at startup, so the empty
     *                         data source fallback below never runs today. It is kept for
     *                         the case where that auto-configuration is made conditional.
     * @param reportsDirectory the directory report files are read from; subreport references
     *                         are resolved within it and may not point outside it.
     */
    public JasperReportRenderService(
            Optional<DataSource> dataSource,
            @Value("${reports.directory:./reports}") String reportsDirectory) {
        this.dataSource = dataSource.orElse(null);
        this.reportsDirectory = Path.of(reportsDirectory).toAbsolutePath().normalize();
    }

    /**
     * Compiles the given report and fills it with the supplied parameters.
     * <p>
     * When a database connection is configured, it is handed to JasperReports so that any
     * {@code <queryString>} defined in the report can pull data from it. Otherwise, the report
     * is filled with an empty data source (i.e. it must rely solely on its parameters).
     *
     * @param reportPath the .jrxml file to render, already resolved inside the reports directory
     * @param parameters report parameters; must be mutable, and may be empty but not null
     * @return the rendered PDF bytes
     */
    public byte[] renderPdf(Path reportPath, Map<String, Object> parameters) {
        try {
            JasperReport jasperReport = new Compilation(parameters).compile(reportPath, List.of());
            JasperPrint jasperPrint = dataSource != null
                    ? fillWithConnection(jasperReport, parameters)
                    : JasperFillManager.fillReport(jasperReport, parameters, new JREmptyDataSource());
            return JasperExportManager.exportReportToPdf(jasperPrint);
        } catch (JRException e) {
            throw new ReportGenerationException("Failed to compile or render the report: " + e.getMessage(), e);
        }
    }

    private JasperPrint fillWithConnection(JasperReport jasperReport, Map<String, Object> parameters) throws JRException {
        try (Connection connection = dataSource.getConnection()) {
            return JasperFillManager.fillReport(jasperReport, parameters, connection);
        } catch (SQLException e) {
            throw new ReportGenerationException("Failed to obtain a database connection for the report: " + e.getMessage(), e);
        }
    }

    /**
     * One report compilation, including the subreports it references.
     * <p>
     * JasperReports only ever <em>deserializes</em> a subreport named by a string expression, so a
     * reference to a .jrxml file fails at fill time with
     * {@code StreamCorruptedException: invalid stream header: 3C3F786D} (the {@code <?xm} of the XML
     * it was handed). Reports here are authored as .jrxml and compiled per request, so each .jrxml
     * subreport reference is compiled the same way and swapped for a generated parameter holding the
     * resulting {@link JasperReport} — an object the fill engine accepts directly.
     * <p>
     * A reference to an already-compiled .jasper file is loaded and passed the same way, so that it
     * too resolves relative to the report that declares it or to the reports directory. JasperReports
     * would otherwise resolve it against the process working directory, which is not where reports
     * live once {@code reports.directory} points somewhere else. References that resolve to nothing
     * inside the reports directory are left alone for JasperReports to handle as before.
     */
    private final class Compilation {

        /** The parameter map the report will be filled with; compiled subreports are added to it. */
        private final Map<String, Object> fillParameters;

        /** Reports currently being compiled, to catch a subreport that references one of its ancestors. */
        private final Set<Path> ancestry = new LinkedHashSet<>();

        private int subreportCount;

        private Compilation(Map<String, Object> fillParameters) {
            this.fillParameters = fillParameters;
        }

        /**
         * @param chain the subreport elements leading from the report being filled down to this one,
         *              along which a generated parameter has to be passed; empty for the top report
         */
        private JasperReport compile(Path reportPath, List<Link> chain) throws JRException {
            Path report = reportPath.toAbsolutePath().normalize();
            if (!ancestry.add(report)) {
                throw new ReportGenerationException(
                        "Subreport cycle: " + reportsDirectory.relativize(report) + " includes itself");
            }

            JasperDesign design = load(report);
            for (JRDesignSubreport subreport : fileSubreportsOf(design)) {
                String location = stringLiteral(subreport.getExpression());
                Path subreportPath = resolve(location, report);
                if (subreportPath == null) {
                    // Not in the reports directory: leave it for JasperReports to resolve its own way.
                    continue;
                }

                JasperReport compiledSubreport = location.toLowerCase(Locale.ROOT).endsWith(JRXML_EXTENSION)
                        ? compile(subreportPath, append(chain, new Link(design, subreport)))
                        : (JasperReport) JRLoader.loadObject(subreportPath.toFile());

                String parameterName = SUBREPORT_PARAMETER_PREFIX + (++subreportCount);
                fillParameters.put(parameterName, compiledSubreport);
                declareParameter(parameterName, design, chain);
                subreport.setExpression(new JRDesignExpression("$P{" + parameterName + "}"));
            }

            ancestry.remove(report);
            return JasperCompileManager.compileReport(design);
        }

        /**
         * Declares the generated parameter on the report that uses it, and on every report above it,
         * passing the value down through the subreport elements in between.
         */
        private void declareParameter(String name, JasperDesign design, List<Link> chain) throws JRException {
            for (Link link : chain) {
                declareParameter(link.design(), name);

                JRDesignSubreportParameter passedDown = new JRDesignSubreportParameter();
                passedDown.setName(name);
                passedDown.setExpression(new JRDesignExpression("$P{" + name + "}"));
                link.subreport().addParameter(passedDown);
            }
            declareParameter(design, name);
        }

        private void declareParameter(JasperDesign design, String name) throws JRException {
            if (design.getParametersMap().containsKey(name)) {
                return;
            }
            JRDesignParameter parameter = new JRDesignParameter();
            parameter.setName(name);
            parameter.setValueClass(JasperReport.class);
            parameter.setForPrompting(false);
            design.addParameter(parameter);
        }

        /**
         * Resolves a subreport reference the way report authors write them: relative to the report
         * that declares it, to the reports directory, or to the working directory the service runs in.
         */
        private Path resolve(String location, Path declaringReport) {
            List<Path> candidates = List.of(
                    declaringReport.getParent().resolve(location),
                    reportsDirectory.resolve(location),
                    Path.of("").toAbsolutePath().resolve(location));

            for (Path candidate : candidates) {
                Path resolved = candidate.toAbsolutePath().normalize();
                if (resolved.startsWith(reportsDirectory) && Files.isRegularFile(resolved)) {
                    return resolved;
                }
            }
            if (location.toLowerCase(Locale.ROOT).endsWith(JRXML_EXTENSION)) {
                throw new ReportGenerationException("Subreport not found in the reports directory: " + location
                        + " (referenced by " + reportsDirectory.relativize(declaringReport) + ")");
            }
            return null;
        }

        private JasperDesign load(Path report) throws JRException {
            try (InputStream jrxml = Files.newInputStream(report)) {
                return JRXmlLoader.load(jrxml);
            } catch (IOException e) {
                throw new ReportGenerationException("Failed to read report file: " + report.getFileName(), e);
            }
        }

        private List<Link> append(List<Link> chain, Link link) {
            List<Link> extended = new ArrayList<>(chain);
            extended.add(link);
            return extended;
        }
    }

    /** A subreport element, together with the report design that declares it. */
    private record Link(JasperDesign design, JRDesignSubreport subreport) {
    }

    private static List<JRDesignSubreport> fileSubreportsOf(JasperDesign design) {
        List<JRDesignSubreport> subreports = new ArrayList<>();
        JRElementsVisitor.visitReport(design, new JRVisitorSupport() {
            @Override
            public void visitSubreport(JRSubreport subreport) {
                if (subreport instanceof JRDesignSubreport designSubreport) {
                    String location = stringLiteral(designSubreport.getExpression());
                    if (location == null) {
                        return;
                    }
                    String lowerCase = location.toLowerCase(Locale.ROOT);
                    if (lowerCase.endsWith(JRXML_EXTENSION) || lowerCase.endsWith(COMPILED_EXTENSION)) {
                        subreports.add(designSubreport);
                    }
                }
            }
        });
        return subreports;
    }

    /**
     * @return the text of a subreport expression that is a plain string literal, or null when the
     *         expression computes the location at runtime — those are left to JasperReports
     */
    private static String stringLiteral(JRExpression expression) {
        if (expression == null || expression.getText() == null) {
            return null;
        }
        Matcher matcher = STRING_LITERAL.matcher(expression.getText());
        return matcher.matches() ? matcher.group(1) : null;
    }
}
