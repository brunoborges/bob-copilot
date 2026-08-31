package com.github.brunoborges.bobcopilot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

final class BobAnalyzer {

    private final String command;
    private final int maxOutputBytes;
    private final int maxTurns;
    private final Duration timeout;
    private final boolean trustWorkspace;

    BobAnalyzer(String command, int maxOutputBytes, int maxTurns, Duration timeout, boolean trustWorkspace) {
        this.command = command;
        this.maxOutputBytes = maxOutputBytes;
        this.maxTurns = maxTurns;
        this.timeout = timeout;
        this.trustWorkspace = trustWorkspace;
    }

    String analyze(Path workspace, String question, String apiKey) {
        var processBuilder = new ProcessBuilder(buildCommand(workspace, question))
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        processBuilder.environment().put("BOB_API_KEY", apiKey);

        try {
            var process = processBuilder.start();
            process.getOutputStream().close();
            var output = CompletableFuture.supplyAsync(() -> readOutput(process.getInputStream()));

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                throw new IllegalStateException("IBM Bob Shell timed out after " + timeout.toMillis() + " ms.");
            }

            var text = output.join().strip();
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "IBM Bob Shell failed with exit code " + process.exitValue() + ".\n" + text);
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start IBM Bob Shell (" + command + ").", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for IBM Bob Shell.", e);
        }
    }

    List<String> buildCommand(Path workspace, String question) {
        var arguments = new ArrayList<>(List.of(
                command,
                "run",
                "--format",
                "json",
                "--workspace",
                workspace.toString(),
                "--mode",
                "agent",
                "--max-turns",
                Integer.toString(maxTurns),
                "--log-level",
                "error",
                "--disable-subagents"));

        if (trustWorkspace) {
            arguments.add("--trust");
        }

        arguments.add("""
                Analyze the workspace in read-only mode. Do not create, edit, move, or delete files.
                Use evidence from the codebase and cite relevant file paths.
                Analysis request: %s
                """.formatted(question).strip());
        return List.copyOf(arguments);
    }

    private String readOutput(InputStream input) {
        try (input; var output = new ByteArrayOutputStream()) {
            var buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxOutputBytes) {
                    throw new IllegalStateException(
                            "IBM Bob output exceeded " + maxOutputBytes + " bytes.");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CompletionException("Unable to read IBM Bob Shell output.", e);
        }
    }
}
