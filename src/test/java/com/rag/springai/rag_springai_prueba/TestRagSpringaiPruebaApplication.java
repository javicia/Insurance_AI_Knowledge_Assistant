package com.rag.springai.rag_springai_prueba;

import org.springframework.boot.SpringApplication;

public class TestRagSpringaiPruebaApplication {

	public static void main(String[] args) {
		SpringApplication.from(RagSpringaiPruebaApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
