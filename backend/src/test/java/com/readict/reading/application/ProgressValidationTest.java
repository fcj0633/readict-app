package com.readict.reading.application;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.readict.shared.web.ApiProblem;

class ProgressValidationTest {
    private static final String CFI = "epubcfi(/6/8!/4/2/1:0)";

    @Test void acceptsACompleteAnchor() {
        ProgressValidation.validateAnchor(CFI, "OEBPS/Text/ch1.xhtml", "b-1", 0, "abc", "", "");
        ProgressValidation.validateAnchor(CFI, "OEBPS/Text/ch1.xhtml", null, null, null, null, null);
        ProgressValidation.validateRequest(0L, UUID.randomUUID());
    }

    @Test void rejectsMalformedCfiAndHref() {
        assertThat(code(() -> ProgressValidation.validateAnchor("", "h", null, null, null, null, null))).isEqualTo("ANCHOR_INVALID");
        assertThat(code(() -> ProgressValidation.validateAnchor("cfi(/1)", "h", null, null, null, null, null))).isEqualTo("ANCHOR_INVALID");
        assertThat(code(() -> ProgressValidation.validateAnchor("epubcfi(" + "x".repeat(4100) + ")", "h", null, null, null, null, null)))
                .isEqualTo("ANCHOR_INVALID");
        assertThat(code(() -> ProgressValidation.validateAnchor(CFI, "", null, null, null, null, null))).isEqualTo("ANCHOR_INVALID");
    }

    @Test void rejectsIncompleteBlockOrQuotePairs() {
        assertThat(code(() -> ProgressValidation.validateAnchor(CFI, "h", "b-1", null, null, null, null))).isEqualTo("ANCHOR_INVALID");
        assertThat(code(() -> ProgressValidation.validateAnchor(CFI, "h", null, 1, null, null, null))).isEqualTo("ANCHOR_INVALID");
        assertThat(code(() -> ProgressValidation.validateAnchor(CFI, "h", "b-1", -1, null, null, null))).isEqualTo("ANCHOR_INVALID");
        assertThat(code(() -> ProgressValidation.validateAnchor(CFI, "h", "b-1", 0, "abc", null, ""))).isEqualTo("ANCHOR_INVALID");
        assertThat(code(() -> ProgressValidation.validateAnchor(CFI, "h", "b-1", 0, "x".repeat(300), "", ""))).isEqualTo("ANCHOR_INVALID");
    }

    @Test void rejectsInvalidRequests() {
        assertThat(code(() -> ProgressValidation.validateRequest(-1L, UUID.randomUUID()))).isEqualTo("INVALID_REQUEST");
        assertThat(code(() -> ProgressValidation.validateRequest(null, UUID.randomUUID()))).isEqualTo("INVALID_REQUEST");
        assertThat(code(() -> ProgressValidation.validateRequest(0L, null))).isEqualTo("INVALID_REQUEST");
    }

    private static String code(Runnable action) {
        try { action.run(); return "NO_FAILURE"; }
        catch (ApiProblem problem) { return problem.code(); }
    }
}
