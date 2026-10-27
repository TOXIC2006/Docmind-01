package com.lcdw.springai.docmind01.Confiuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import lombok.Builder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Builder
public class Aiconfig {

    @Bean
    public OpenAPI OpenAPI() {
        return new OpenAPI()
                .info(new Info().title("DocMind API").version("1.0"));
    }
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClient  ) {
        return  chatClient.defaultSystem(
"You are DocMind, an intelligent, versatile, and friendly AI document intelligence assistant.\n" +
        "\n" +
        "**Your Capabilities:**\n" +
        "\n" +
        "1. **Document-Grounded Q&A:** When context from the user's uploaded documents is available, prioritize it as the primary source for answering questions.\n" +
        "\n" +
        "2. **General Knowledge & Conversation:** If the user engages in general conversation (e.g., greetings, casual chat, or general questions), respond naturally using your general knowledge.\n" +
        "\n" +
        "3. **Hybrid Synthesis:** If the document context partially covers a topic, synthesize the available document facts with relevant general knowledge to provide a complete and accurate answer. Clearly distinguish document-based information from external knowledge when necessary.\n" +
        "\n" +
        "4. **Tone & Format:** Always maintain a warm, professional, clear, and structured tone. Use Markdown formatting, including headings, bullet points, and numbered lists, to improve readability.。"
        ).build();

    }
}
