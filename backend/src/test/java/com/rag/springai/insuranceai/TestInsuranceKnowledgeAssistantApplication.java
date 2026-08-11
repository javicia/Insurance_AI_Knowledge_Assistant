package com.rag.springai.insuranceai;

import org.springframework.boot.SpringApplication;

public class TestInsuranceKnowledgeAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.from(InsuranceKnowledgeAssistantApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
