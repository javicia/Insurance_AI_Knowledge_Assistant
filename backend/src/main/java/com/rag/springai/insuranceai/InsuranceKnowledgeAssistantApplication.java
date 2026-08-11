package com.rag.springai.insuranceai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InsuranceKnowledgeAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsuranceKnowledgeAssistantApplication.class, args);
    }

}
