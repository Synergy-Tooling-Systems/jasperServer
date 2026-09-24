package com.synergytsi.jasper_report_service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rendersJrxmlFromReportsDirectoryToPdf() throws Exception {
        mockMvc.perform(post("/api/reports/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportFileName\":\"blank.jrxml\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void rendersJrxmlWithParametersToPdf() throws Exception {
        mockMvc.perform(post("/api/reports/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportFileName\":\"blank.jrxml\",\"parameters\":[{\"title\":\"Custom Title\"}]}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void rejectsNonJrxmlFile() throws Exception {
        mockMvc.perform(post("/api/reports/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportFileName\":\"notes.txt\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingReportFile() throws Exception {
        mockMvc.perform(post("/api/reports/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportFileName\":\"does-not-exist.jrxml\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        mockMvc.perform(post("/api/reports/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportFileName\":\"../pom.jrxml\"}"))
                .andExpect(status().isBadRequest());
    }
}
