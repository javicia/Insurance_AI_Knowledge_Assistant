package com.rag.springai.insuranceai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(com.rag.springai.insuranceai.DatabaseCleanupExtension.class)
class InsuranceKnowledgeAssistantApplicationTests {

    @Test
    void contextLoads() {
    }

}
