package com.lcdw.springai.docmind01.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcdw.springai.docmind01.Confi.Appproperties;
import com.lcdw.springai.docmind01.Entity.Document_Status;
import com.lcdw.springai.docmind01.Repo.DocumnetMetadatarepo;
import com.lcdw.springai.docmind01.Repo.ScopedChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.*;

@Slf4j
@Component
@Profile("pinecone-migrate")
@RequiredArgsConstructor
public class LegacyVectorMigration implements ApplicationRunner {
    private final DocumnetMetadatarepo documents;
    private final ScopedChunkRepository chunks;
    private final JdbcTemplate jdbc;
    private final VectorStore vectors;
    private final ObjectMapper mapper;
    private final Appproperties properties;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("migration.document-id")) {
            throw new IllegalArgumentException("Supply --migration.document-id=<id>; repeat it to migrate more documents.");
        }
        for (String id : args.getOptionValues("migration.document-id")) migrate(id);
        context.close();
    }

    void migrate(String id) {
        var document = documents.findById(id).orElseThrow(() -> new IllegalArgumentException("Migration document not found."));
        if (document.getOwnerId() == null || document.getOwnerId().isBlank() || document.getStatus() != Document_Status.indexed) {
            throw new IllegalArgumentException("Only owned, indexed documents can be migrated. Ownership is never assigned automatically.");
        }
        if (document.getVectorRevision() != null) {
            log.info("Document {} is already migrated; skipped", id);
            return;
        }
        String revision = UUID.randomUUID().toString();
        int limit = properties.getRag().getMaxCorpusChunks();
        List<Document> legacy = jdbc.query("SELECT id::text, content, metadata::text FROM docmind_vectors "
                + "WHERE metadata->>'documentId' = ? AND metadata->>'ownerId' = ? ORDER BY id LIMIT ?", (row, n) -> {
            try {
                Map<String, Object> original = mapper.readValue(row.getString(3), new TypeReference<>() {});
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("ownerId", document.getOwnerId());
                metadata.put("documentId", id);
                metadata.put("vectorRevision", revision);
                metadata.put("fileName", document.getFilename());
                metadata.put("contentType", document.getContentType());
                metadata.put("chunkIndex", original.getOrDefault("chunkIndex", n));
                if (original.get("pageNumber") instanceof Number page) metadata.put("pageNumber", page);
                return Document.builder().id(row.getString(1)).text(row.getString(2)).metadata(metadata).build();
            } catch (java.io.IOException e) {
                throw new SQLException("Invalid legacy chunk metadata", e);
            }
        }, id, document.getOwnerId(), limit + 1);
        if (legacy.isEmpty() || legacy.size() > limit || !Objects.equals(document.getTotalChunks(), legacy.size())) {
            throw new IllegalStateException("Legacy chunk count does not match the indexed document; no data was migrated.");
        }
        chunks.replace(document.getOwnerId(), id, revision, legacy);
        for (int offset = 0; offset < legacy.size(); offset += 100) {
            vectors.add(legacy.subList(offset, Math.min(offset + 100, legacy.size())));
        }
        document.setVectorRevision(revision);
        documents.save(document);
        log.info("Migrated document {} with {} chunks to Pinecone; legacy rows retained", id, legacy.size());
    }
}
