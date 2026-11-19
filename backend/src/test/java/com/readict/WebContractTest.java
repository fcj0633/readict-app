package com.readict;

import com.readict.shared.web.*;
import com.readict.operations.api.SystemStatusController;
import com.readict.operations.application.*;
import com.readict.operations.infrastructure.persistence.SystemMetadataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.*;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WebContractTest {
    @Test void acceptsCanonicalUuidAndRejectsOversizedOrInjectedIds() {
        assertThat(RequestIdFilter.normalize("00112233-4455-6677-8899-aabbccddeeff")).isEqualTo("00112233-4455-6677-8899-aabbccddeeff");
        for (String input : new String[] {"1-1-1-1-1", "a".repeat(10000), "bad\r\nsecret", ""})
            assertThat(RequestIdFilter.normalize(input)).matches("[0-9a-f-]{36}").isNotEqualTo(input);
    }
    @Test void filterCleansMdcEvenOnFailureAndKeepsExistingRequestId() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/system/status");
        String id = java.util.UUID.randomUUID().toString(); request.setAttribute(RequestIdFilter.ATTRIBUTE,id);
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new RequestIdFilter().doFilter(request,response,(req,res) -> { assertThat(MDC.get("requestId")).isEqualTo(id); throw new jakarta.servlet.ServletException("controlled"); })).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(MDC.get("requestId")).isNull(); assertThat(response.getHeader("X-Request-Id")).isEqualTo(id);
    }
    @Test void statusAndSafeProblemDetails() throws Exception {
        var service = mock(SystemStatusService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SystemStatusController(service)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        mvc.perform(get("/api/v1/system/status")).andExpect(status().isOk()).andExpect(jsonPath("$.service").value("readict")).andExpect(header().exists("X-Request-Id"));
        doThrow(new SystemNotReadyException()).when(service).checkReady();
        mvc.perform(get("/api/v1/system/status")).andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(jsonPath("$.code").value("SYSTEM_NOT_READY")).andExpect(jsonPath("$.retryable").value(true));
        doThrow(new RuntimeException("password=private SQL SELECT")).when(service).checkReady();
        String body = mvc.perform(get("/api/v1/system/status")).andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private", "SELECT", "password");
    }
    @Test void missingOrWrongBaselineFailsAndHealthIsDown() {
        var repository = mock(SystemMetadataRepository.class); var service = new SystemStatusService(repository);
        when(repository.hasApplicationBaseline()).thenReturn(false);
        assertThatThrownBy(service::checkReady).isInstanceOf(SystemNotReadyException.class);
        assertThat(new MetadataHealthIndicator(service).health().getStatus().getCode()).isEqualTo("DOWN");
        when(repository.hasApplicationBaseline()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private"));
        assertThatThrownBy(service::checkReady).isInstanceOf(SystemNotReadyException.class).hasMessage("SYSTEM_NOT_READY");
    }
}
