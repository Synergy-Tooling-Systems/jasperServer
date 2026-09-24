package com.synergytsi.jasper_report_service.service;

import com.synergytsi.jasper_report_service.exception.ReportGenerationException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link JasperReportRenderService} can drive a report's
 * {@code <queryString>} against a real JDBC connection, independent of Spring Boot's
 * own (well-tested) DataSource autoconfiguration, and that reports referencing
 * .jrxml subreports render without the subreports being pre-compiled.
 */
class JasperReportRenderServiceTest {

    private static final Path TEST_REPORTS = Path.of("src/test/resources/reports");

    @Test
    void rendersReportUsingConfiguredDataSource() throws Exception {
        DataSource dataSource = inMemoryDataSourceWithOnePerson("Ada Lovelace");
        JasperReportRenderService service =
                new JasperReportRenderService(Optional.of(dataSource), TEST_REPORTS.toString());

        byte[] pdf = service.renderPdf(TEST_REPORTS.resolve("with-query.jrxml"), new HashMap<>());

        assertThatIsPdf(pdf);
    }

    @Test
    void rendersReportWithoutDataSourceConfigured() {
        JasperReportRenderService service =
                new JasperReportRenderService(Optional.empty(), TEST_REPORTS.toString());

        byte[] pdf = service.renderPdf(TEST_REPORTS.resolve("blank.jrxml"), new HashMap<>());

        assertThatIsPdf(pdf);
    }

    /**
     * JasperReports itself only deserializes a pre-compiled .jasper for a subreport named by a
     * string expression; a .jrxml there fails with "invalid stream header: 3C3F786D".
     */
    @Test
    void rendersReportWhoseSubreportIsAnUncompiledJrxml(@TempDir Path reports) throws Exception {
        writeParentReport(reports.resolve("parent.jrxml"), "child.jrxml");
        writeLeafReport(reports.resolve("child.jrxml"));

        byte[] pdf = render(reports, reports.resolve("parent.jrxml"));

        assertThatIsPdf(pdf);
    }

    @Test
    void rendersSubreportReferencedRelativeToTheReportsDirectory(@TempDir Path reports) throws Exception {
        Files.createDirectories(reports.resolve("purchasing"));
        writeParentReport(reports.resolve("purchasing/parent.jrxml"), "purchasing/child.jrxml");
        writeLeafReport(reports.resolve("purchasing/child.jrxml"));

        byte[] pdf = render(reports, reports.resolve("purchasing/parent.jrxml"));

        assertThatIsPdf(pdf);
    }

    /** The compiled subreport has to be passed down through every level, not just the first. */
    @Test
    void rendersNestedJrxmlSubreports(@TempDir Path reports) throws Exception {
        writeParentReport(reports.resolve("grandparent.jrxml"), "parent.jrxml");
        writeParentReport(reports.resolve("parent.jrxml"), "child.jrxml");
        writeLeafReport(reports.resolve("child.jrxml"));

        byte[] pdf = render(reports, reports.resolve("grandparent.jrxml"));

        assertThatIsPdf(pdf);
    }

    @Test
    void rendersSubreportThatIsAlreadyCompiled(@TempDir Path reports) throws Exception {
        writeLeafReport(reports.resolve("child.jrxml"));
        net.sf.jasperreports.engine.JasperCompileManager.compileReportToFile(
                reports.resolve("child.jrxml").toString(), reports.resolve("child.jasper").toString());
        writeParentReport(reports.resolve("parent.jrxml"), "child.jasper");

        byte[] pdf = render(reports, reports.resolve("parent.jrxml"));

        assertThatIsPdf(pdf);
    }

    @Test
    void reportsAMissingSubreportByName(@TempDir Path reports) throws Exception {
        writeParentReport(reports.resolve("parent.jrxml"), "nowhere.jrxml");

        assertThatThrownBy(() -> render(reports, reports.resolve("parent.jrxml")))
                .isInstanceOf(ReportGenerationException.class)
                .hasMessageContaining("Subreport not found")
                .hasMessageContaining("nowhere.jrxml");
    }

    @Test
    void refusesASubreportOutsideTheReportsDirectory(@TempDir Path reports) throws Exception {
        Path outside = reports.resolve("outside");
        Files.createDirectories(outside);
        writeLeafReport(outside.resolve("child.jrxml"));

        Path reportsRoot = reports.resolve("reports");
        Files.createDirectories(reportsRoot);
        writeParentReport(reportsRoot.resolve("parent.jrxml"), "../outside/child.jrxml");

        assertThatThrownBy(() -> render(reportsRoot, reportsRoot.resolve("parent.jrxml")))
                .isInstanceOf(ReportGenerationException.class)
                .hasMessageContaining("Subreport not found");
    }

    @Test
    void reportsASubreportCycle(@TempDir Path reports) throws Exception {
        writeParentReport(reports.resolve("parent.jrxml"), "child.jrxml");
        writeParentReport(reports.resolve("child.jrxml"), "parent.jrxml");

        assertThatThrownBy(() -> render(reports, reports.resolve("parent.jrxml")))
                .isInstanceOf(ReportGenerationException.class)
                .hasMessageContaining("cycle");
    }

    private static byte[] render(Path reportsDirectory, Path report) {
        return new JasperReportRenderService(Optional.empty(), reportsDirectory.toString())
                .renderPdf(report, new HashMap<>());
    }

    private static void assertThatIsPdf(byte[] pdf) {
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    /** A report whose detail band is nothing but a subreport pointing at {@code subreportLocation}. */
    private static void writeParentReport(Path path, String subreportLocation) throws Exception {
        Files.writeString(path, """
                <?xml version="1.0" encoding="UTF-8"?>
                <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="http://jasperreports.sourceforge.net/jasperreports http://jasperreports.sourceforge.net/xsd/jasperreport.xsd"
                              name="parent" pageWidth="595" pageHeight="842" columnWidth="555"
                              leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                    <detail>
                        <band height="50">
                            <subreport>
                                <reportElement x="0" y="0" width="555" height="50"/>
                                <dataSourceExpression><![CDATA[new net.sf.jasperreports.engine.JREmptyDataSource()]]></dataSourceExpression>
                                <subreportExpression><![CDATA["%s"]]></subreportExpression>
                            </subreport>
                        </band>
                    </detail>
                </jasperReport>
                """.formatted(subreportLocation));
    }

    private static void writeLeafReport(Path path) throws Exception {
        Files.writeString(path, """
                <?xml version="1.0" encoding="UTF-8"?>
                <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="http://jasperreports.sourceforge.net/jasperreports http://jasperreports.sourceforge.net/xsd/jasperreport.xsd"
                              name="child" pageWidth="555" pageHeight="842" columnWidth="555"
                              leftMargin="0" rightMargin="0" topMargin="0" bottomMargin="0">
                    <detail>
                        <band height="20">
                            <staticText>
                                <reportElement x="0" y="0" width="200" height="20"/>
                                <text><![CDATA[Subreport line]]></text>
                            </staticText>
                        </band>
                    </detail>
                </jasperReport>
                """);
    }

    private static DataSource inMemoryDataSourceWithOnePerson(String name) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE person (name VARCHAR(100))");
            statement.execute("INSERT INTO person (name) VALUES ('" + name + "')");
        }
        return dataSource;
    }
}
