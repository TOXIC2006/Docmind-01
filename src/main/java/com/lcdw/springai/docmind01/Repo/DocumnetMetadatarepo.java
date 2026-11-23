package com.lcdw.springai.docmind01.Repo;

import com.lcdw.springai.docmind01.Entity.DocumentMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumnetMetadatarepo extends JpaRepository<DocumentMetadata, String> {
    java.util.List<DocumentMetadata> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    java.util.Optional<DocumentMetadata> findByIdAndOwnerId(String id, String ownerId);
}
