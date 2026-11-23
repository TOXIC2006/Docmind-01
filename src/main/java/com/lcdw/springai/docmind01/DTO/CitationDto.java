package com.lcdw.springai.docmind01.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CitationDto {
    private String citationId;
    private String chunkId;
    private Double bm25Score;
    private Double fusionScore;
    private Double rerankScore;
    private String documentId;
    private String fileName;
    private Integer chunkIndex;
    private Integer pageNumber;
    private String snippet;
    private Double similarityScore;
    private Map<String, Object> metadata;
}
