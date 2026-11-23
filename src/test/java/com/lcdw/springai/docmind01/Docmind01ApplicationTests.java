package com.lcdw.springai.docmind01;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@EnabledIfEnvironmentVariable(named = "DOCMIND_LIVE_TESTS", matches = "true")
@SpringBootTest
class Docmind01ApplicationTests {

    @Test
    void contextLoads() {
    }

}
