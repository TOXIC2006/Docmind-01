package com.lcdw.springai.docmind01.DTO;

import java.util.List;

public record RetrievalDiagnostics(String rewrittenQuery, boolean rewriteApplied, String retrievalMode,
        int semanticCandidates, int lexicalCandidates, int fusedCandidates,
        int contextCharactersBefore, int contextCharactersAfter, long elapsedMs, List<String> warnings) {
}
