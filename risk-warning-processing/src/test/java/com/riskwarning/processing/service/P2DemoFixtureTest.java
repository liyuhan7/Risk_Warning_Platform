package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertThrows;

class P2DemoFixtureTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsFrozenFixtureAndRejectsTamperedHash() throws Exception {
        Path path = Paths.get("..", "test", "fixtures", "p2", "mock-analysis-results.json");
        if (!Files.exists(path)) { path = Paths.get("test", "fixtures", "p2", "mock-analysis-results.json"); }
        P2DemoProcessingService.FixtureFile fixture = mapper.readValue(
                path.toFile(), P2DemoProcessingService.FixtureFile.class);

        P2DemoProcessingService.validateFixture(fixture, mapper);
        fixture.cases.get(0).facts.get(0).enterpriseFact += "篡改";

        assertThrows(IllegalStateException.class,
                () -> P2DemoProcessingService.validateFixture(fixture, mapper));
    }
}
