package com.self.help.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Spring Converter that automatically deserializes a URL-encoded JSON path variable
 * into a GraphMappingSchema instance.
 */
@Component
public class GraphMappingSchemaConverter implements Converter<String, GraphMappingSpec> {
    private final ObjectMapper objectMapper;

    public GraphMappingSchemaConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    @NotNull
    public GraphMappingSpec convert(@NotNull String source) {
        try {
            String decoded = URLDecoder.decode(source, StandardCharsets.UTF_8);
            return objectMapper.readValue(decoded, GraphMappingSpec.class);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Failed to convert JSON string to GraphMappingSchema: " + e.getMessage(), e);
        }
    }
}
