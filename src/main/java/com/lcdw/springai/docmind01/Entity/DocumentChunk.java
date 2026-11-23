package com.lcdw.springai.docmind01.Entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "docmind_chunks", indexes = @Index(name = "docmind_chunks_scope", columnList = "owner_id,document_id"))
@Getter
@NoArgsConstructor
public class DocumentChunk {
    @Id
    private String id;
    @Column(nullable = false)
    private String ownerId;
    @Column(nullable = false)
    private String documentId;
    @Column(nullable = false)
    private String vectorRevision;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String metadata;
}
