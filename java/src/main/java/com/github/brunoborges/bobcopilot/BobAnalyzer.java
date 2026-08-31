package com.github.brunoborges.bobcopilot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

record BobAnalyzer(
        String command,
        int maxOutputBytes,
        int maxTurns,
        Duration timeout,
        boolean trustWorkspace) {

    BobAnalyzer {
        if (command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (maxOutputBytes <= 0 || maxTurns <= 0 || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Bob limits must be positive");
        }
    }

    String analyze(Path workspace, String question, String apiKey) {
        var processBuilder = new ProcessBuilder(buildCommand(workspace, question))
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        processBuilder.environment().put("BOB_API_KEY", apiKey);

        try (var process = processBuilder.start();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            process.outputWriter().close();
            var output = executor.submit(() -> readOutput(process.getInputStream()));

            if (!process.waitFor(timeout)) {
                process.destroy();
                if (!process.waitFor(Duration.ofSeconds(5))) {
                    process.destroyForcibly().waitFor(Duration.ofSeconds(5));
                }
                throw new IllegalStateException("IBM Bob Shell timed out after " + timeout.toMillis() + " ms.");
            }

            var text = output.get().strip();
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
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unable to read IBM Bob Shell output.", e.getCause());
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
            throw new IllegalStateException("Unable to read IBM Bob Shell output.", e);
        }
    }
}
