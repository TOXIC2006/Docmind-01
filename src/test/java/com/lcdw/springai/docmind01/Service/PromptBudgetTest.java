package com.lcdw.springai.docmind01.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcdw.springai.docmind01.Confi.Appproperties;
import com.lcdw.springai.docmind01.DTO.CitationDto;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PromptBudgetTest {
    private final Appproperties properties = new Appproperties();
    private final PromptBudget budget = new PromptBudget(new ObjectMapper(), properties);
    private CitationDto source(String id, String text) {
        return CitationDto.builder().citationId(id).snippet(text).documentId("d1").build();
    }

    @Test void duplicatePassagesAreNotSentTwiceAndIdsRemainStable() {
        var prepared = budget.prepare("Use only evidence", "refunds", List.of(source("S1", "Thirty days."), source("S2", "Thirty days."), source("S3", "Contact support.")));
        assertThat(prepared.sources()).extracting(CitationDto::getCitationId).containsExactly("S1", "S3");
        assertThat(prepared.after()).isLessThan(prepared.before());
        assertThat(prepared.input()).contains("S3").doesNotContain("S2");
    }

    @Test void unicodeJsonAndSystemInstructionsCountTowardBudget() {
        properties.getTokens().setMaxPromptTokens(256);
        var prepared = budget.prepare("Evidence only.", "政策是什么？", List.of(source("S1", "政策退款。".repeat(500)), source("S2", "退款三十天。")));
        assertThat(prepared.after()).isLessThanOrEqualTo(256);
        assertThat(prepared.sources()).extracting(CitationDto::getCitationId).containsExactly("S2");
        assertThat(prepared.input()).contains("政策是什么？");
    }

    @Test void oversizedQuestionIsRejectedNotTruncated() {
        properties.getTokens().setMaxPromptTokens(256);
        assertThatThrownBy(() -> budget.prepare("Evidence only.", "政策".repeat(500), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("token budget");
    }

    @Test void omittedEvidenceIsNotReturnedAsACitation() {
        properties.getTokens().setMaxPromptTokens(256);
        var prepared = budget.prepare("Evidence only", "refund", List.of(source("S1", "refund ".repeat(1000))));
        assertThat(prepared.sources()).isEmpty();
        assertThat(prepared.input()).doesNotContain("S1");
    }
}
