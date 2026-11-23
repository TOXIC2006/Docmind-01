package com.lcdw.springai.docmind01.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatResponseDTO {

    private  String  query;
    private  String  answer;
    private  int totalmatches;
    private List<CitationDto> matches;
    private RetrievalDiagnostics diagnostics;
    private boolean grounded;
    private boolean abstained;
    private CacheDiagnostics cache;
    private TokenUsage tokenUsage;
}
