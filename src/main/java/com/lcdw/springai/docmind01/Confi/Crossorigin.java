package com.lcdw.springai.docmind01.Confi;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class Crossorigin  implements WebMvcConfigurer {

    private final Appproperties appproperties;


    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(appproperties.getCors().getAllowedOrigins())
                .allowedMethods(appproperties.getCors().getAllowedMethods())
                .allowedHeaders(appproperties.getCors().getAllowedHeaders());
    }




}
