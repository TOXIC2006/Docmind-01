package com.lcdw.springai.docmind01.Confi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app")
@org.springframework.validation.annotation.Validated

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Appproperties {

     private  CorsProperties cors = new CorsProperties();
        private  ChunkingProperties rag= new ChunkingProperties();
    @jakarta.validation.Valid
    private CacheProperties cache = new CacheProperties();
    @jakarta.validation.Valid
    private TokenProperties tokens = new TokenProperties();

    @Data
    public static class CacheProperties {
        @jakarta.validation.constraints.Min(1)
        private int maxEntries = 256;
        @jakarta.validation.constraints.Min(1)
        private long ttlSeconds = 900;
        @jakarta.validation.constraints.DecimalMin("0.9")
        @jakarta.validation.constraints.DecimalMax("1.0")
        private double similarityThreshold = 0.98;
    }

    @Data
    public static class TokenProperties {
        @jakarta.validation.constraints.Min(256)
        private int maxPromptTokens = 3000;
        @jakarta.validation.constraints.Min(1)
        private int maxCompletionTokens = 4096;
        @jakarta.validation.constraints.Min(1)
        private int maxRewriteTokens = 512;
        @jakarta.validation.constraints.Min(32)
        private int safetyMarginTokens = 128;
    }
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CorsProperties {

        private String allowedOrigins = "*";
        private String allowedMethods = "GET,POST,PUT,DELETE,OPTIONS";
        private String allowedHeaders = "*";
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ChunkingProperties {

        private int chunkSize = 600;
        private int chunkOverlap = 100;
        private  int minChunklengthtoembed =5;
        private  int maxChunks=10000;;
        private int topK = 5;
        private double similarityThreshold = 0.25;
        private int candidateK = 30;
        private int maxCorpusChunks = 10000;
        private int contextMaxChars = 6000;
        private int passageMaxChars = 1600;
        private double bm25K1 = 1.5;
        private double bm25B = 0.75;
    }
}
