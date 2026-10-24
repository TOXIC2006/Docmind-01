package com.lcdw.springai.docmind01.Confi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app")

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Appproperties {

     private  CorsProperties cors = new CorsProperties();
        private  ChunkingProperties rag= new ChunkingProperties();
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
        private double similarityThreshold = 0.0;
    }
}
