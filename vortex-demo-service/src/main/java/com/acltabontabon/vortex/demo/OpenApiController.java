package com.acltabontabon.vortex.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves this service's own OpenAPI description.
 *
 * <p>The document lives as a static file rather than being generated at request time — it is the
 * source of truth a person reads before running a demonstration, and it is what gets imported into
 * Vortex to build a workload. Spring's default static-resource handling would serve it too, but
 * with an unhelpful {@code application/octet-stream} content type, since neither Spring nor Tomcat
 * know the {@code .yaml} extension out of the box. This controller takes priority over that handler
 * and serves the same bytes with the media type OpenAPI tooling expects.
 */
@RestController
public class OpenApiController {

    private static final MediaType APPLICATION_YAML = new MediaType("application", "yaml");

    private final Resource openApiDocument = new ClassPathResource("static/openapi.yaml");

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public ResponseEntity<byte[]> openApiDocument() {
        try (var in = openApiDocument.getInputStream()) {
            return ResponseEntity.ok().contentType(APPLICATION_YAML).body(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the bundled OpenAPI document", e);
        }
    }
}
