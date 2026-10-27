Answer the question using the context below.

            CONTEXT:
            {context}

            QUESTION:
            {query}

            Rules:
            - Prefer the CONTEXT as the source of truth.
            - If the CONTEXT does not contain the answer, say so plainly, then answer
              from general knowledge and label it as not coming from the documents.
            - Never invent file names, page numbers or figures.
            - Cite the passage you used as [fileName, page N]