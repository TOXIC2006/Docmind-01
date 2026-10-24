package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.Confi.Appproperties;
import com.lcdw.springai.docmind01.Entity.DocumentMetadata;
import com.lcdw.springai.docmind01.Entity.Document_Status;
import com.lcdw.springai.docmind01.Execption.Document_EXE;
import com.lcdw.springai.docmind01.Repo.DocumnetMetadatarepo;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class IngestionService {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DocumnetMetadatarepo documnetMetadatarepo;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private Appproperties appproperties;

    public int ingest(DocumentMetadata documentMetadata, List<Document> parsedDocs) {

         try {

             documentMetadata.setStatus(Document_Status.processing);
             documnetMetadatarepo.save(documentMetadata);
             documentMetadata.setTotalPages(parsedDocs.size());
             TokenTextSplitter splitter = TokenTextSplitter.builder()
                     .withChunkSize(appproperties.getRag().getChunkSize())
                     .withMinChunkSizeChars(appproperties.getRag().getMinChunklengthtoembed())
                     .withMaxNumChunks(appproperties.getRag().getMaxChunks())
                     .withKeepSeparator(true)
                     .build();

                List<Document> chunks = splitter.apply(parsedDocs);
                if(chunks.size()==0){
                    documentMetadata.setStatus(Document_Status.failed);
                    documentMetadata.setErrorMessage("No chunks created from the document.");
                    documnetMetadatarepo.save(documentMetadata);
                    return 0;
                }

             List<Document> enrichedChunks = new ArrayList<>();

             for (int i = 0; i < chunks.size(); i++) {
                 Document chunk = chunks.get(i);

                 Map<String, Object> enrichedMetadata =
                         new HashMap<>(chunk.getMetadata());

                 enrichedMetadata.put("documentId", documentMetadata.getId().toString());
                 enrichedMetadata.put("fileName", documentMetadata.getFilename());
                 enrichedMetadata.put("contentType", documentMetadata.getContentType());
                 enrichedMetadata.put("chunkIndex", i);

                 // Preserve or calculate page number if available
                 Object pageNumber = chunk.getMetadata().get("page_number");

                 if (pageNumber == null) {
                     pageNumber = chunk.getMetadata().get("pageNumber");
                 }

                 if (pageNumber != null) {
                     enrichedMetadata.put("pageNumber", pageNumber);
                 }

                 Document enrichedDoc = new Document(
                         chunk.getText(),
                         enrichedMetadata
                 );

                 enrichedChunks.add(enrichedDoc);
             }

             vectorStore.add(enrichedChunks);

             // 4. Update document status to indexed
             documentMetadata.setStatus(Document_Status.indexed);
             documentMetadata.setTotalChunks(enrichedChunks.size());
             documentMetadata.setErrorMessage(null);

             documnetMetadatarepo.save(documentMetadata);

             return enrichedChunks.size();
         } catch (Exception e) {
             documentMetadata.setStatus(Document_Status.failed);
             documentMetadata.setErrorMessage("Error ingesting documents: try again " + e.getMessage());
             documnetMetadatarepo.save(documentMetadata);
             throw new Document_EXE("Error ingesting documents: " + e.getMessage(), e);
         }
    }
}
