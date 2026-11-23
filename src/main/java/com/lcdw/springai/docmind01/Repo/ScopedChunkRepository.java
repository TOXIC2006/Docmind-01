package com.lcdw.springai.docmind01.Repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcdw.springai.docmind01.Execption.Document_EXE;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class ScopedChunkRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public List<Document> load(String ownerId, String documentId, int limit) {
        String scope = documentId == null ? "" : " AND d.id = ?";
        List<Object> parameters = new ArrayList<>(List.of(ownerId));
        if (documentId != null) parameters.add(documentId);
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM document_metadata d WHERE d.owner_id = ? "
                + "AND d.status = 'indexed' AND d.vector_revision IS NULL" + scope, Integer.class, parameters.toArray());
        if (pending != null && pending > 0) {
            throw new Document_EXE("Existing documents need the Pinecone migration. Run the pinecone-migrate profile before searching this scope.");
        }
        String sql = "SELECT c.id, c.content, c.metadata FROM docmind_chunks c "
                + "JOIN document_metadata d ON d.id = c.document_id AND d.owner_id = c.owner_id "
                + "AND d.vector_revision = c.vector_revision "
                + "WHERE d.owner_id = ? AND d.status = 'indexed'" + scope + " ORDER BY c.id LIMIT ?";
        parameters.add(limit + 1);
        List<Document> chunks = jdbc.query(sql, (row, n) -> {
            try {
                Map<String, Object> metadata = mapper.readValue(row.getString(3), new TypeReference<>() {});
                return Document.builder().id(row.getString(1)).text(row.getString(2)).metadata(metadata).build();
            } catch (java.io.IOException e) {
                throw new SQLException("Invalid chunk metadata", e);
            }
        }, parameters.toArray());
        if (chunks.size() > limit) {
            throw new IllegalArgumentException("This scope exceeds the BM25 corpus limit. Select a single document or ask the administrator to increase app.rag.max-corpus-chunks.");
        }
        return chunks;
    }

    @Transactional
    public void replace(String ownerId, String documentId, String revision, List<Document> chunks) {
        List<Object[]> rows = chunks.stream().map(chunk -> {
            if (!ownerId.equals(chunk.getMetadata().get("ownerId"))
                    || !documentId.equals(chunk.getMetadata().get("documentId"))
                    || !revision.equals(chunk.getMetadata().get("vectorRevision"))) {
                throw new IllegalArgumentException("Chunk scope does not match its document.");
            }
            try {
                return new Object[]{chunk.getId(), ownerId, documentId, revision, chunk.getText(), mapper.writeValueAsString(chunk.getMetadata())};
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Unable to persist chunk metadata.", e);
            }
        }).toList();
        delete(ownerId, documentId);
        jdbc.batchUpdate("INSERT INTO docmind_chunks (id, owner_id, document_id, vector_revision, content, metadata) VALUES (?, ?, ?, ?, ?, ?)", rows);
    }

    public List<String> ids(String ownerId, String documentId) {
        return jdbc.queryForList("SELECT id FROM docmind_chunks WHERE owner_id = ? AND document_id = ? ORDER BY id",
                String.class, ownerId, documentId);
    }

    public void delete(String ownerId, String documentId) {
        jdbc.update("DELETE FROM docmind_chunks WHERE owner_id = ? AND document_id = ?", ownerId, documentId);
    }
}
