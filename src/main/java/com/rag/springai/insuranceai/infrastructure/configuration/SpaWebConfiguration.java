package com.rag.springai.insuranceai.infrastructure.configuration;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the Angular SPA packaged into {@code src/main/resources/static/} by the Docker
 * multi-stage build (brief FASE 15 section 35/36) - never a second container, never a separate
 * origin. {@code /assistant}, {@code /documents}, {@code /governance}, {@code /audit}, {@code
 * /evaluation} (and any future Angular route) must resolve to {@code index.html} so Angular's own
 * router can take over client-side, but {@code /api/**} must never silently fall back to
 * {@code index.html} - an unmatched API path is a real 404, not a 200 with HTML in it.
 *
 * <p>This works because Spring Boot's {@code RequestMappingHandlerMapping} (every
 * {@code @RestController}, including {@code ChatController}/{@code DocumentController}/
 * governance/audit/evaluation, and springdoc's own endpoints) is always consulted <em>before</em>
 * the static resource handler chain registered here - a real, unmapped API path only reaches this
 * resolver because no controller claimed it, at which point {@link #getResource} explicitly
 * refuses to substitute {@code index.html} for anything under {@code api/} or {@code actuator/},
 * letting Spring's normal 404 handling apply instead.
 */
@Configuration
public class SpaWebConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaFallbackResourceResolver());
    }

    private static final class SpaFallbackResourceResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
                return null;
            }
            Resource requestedResource = location.createRelative(resourcePath);
            return requestedResource.exists() && requestedResource.isReadable() ? requestedResource
                    : new ClassPathResource("/static/index.html");
        }
    }
}
