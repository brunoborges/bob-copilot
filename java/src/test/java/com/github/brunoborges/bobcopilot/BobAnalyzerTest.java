package com.github.brunoborges.bobcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class BobAnalyzerTest {

    @Test
    void buildsReadOnlyAnalysisCommand() {
        var analyzer = new BobAnalyzer("bob", 1_000_000, 3, Duration.ofSeconds(5), false);

        var command = analyzer.buildCommand(Path.of("/workspace"), "Find the entry point");

        assertEquals("bob", command.get(0));
        assertTrue(command.contains("/workspace"));
        assertTrue(command.contains("3"));
        assertFalse(command.contains("--trust"));
        assertTrue(command.get(command.size() - 1).contains("read-only mode"));
        assertTrue(command.get(command.size() - 1).contains("Find the entry point"));
    }

    @Test
    void canTrustWorkspaceExplicitly() {
        var analyzer = new BobAnalyzer("bob", 1_000_000, 3, Duration.ofSeconds(5), true);

        assertTrue(analyzer.buildCommand(Path.of("/workspace"), "Summarize").contains("--trust"));
    }
}
