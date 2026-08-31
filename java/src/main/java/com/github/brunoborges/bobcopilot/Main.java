package com.github.brunoborges.bobcopilot;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.github.copilot.CopilotClient;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.ToolDefinition;

import io.github.cdimascio.dotenv.Dotenv;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        var dotenv = Dotenv.configure().ignoreIfMissing().load();
        var bobApiKey = required(dotenv.get("BOB_API_KEY"), "BOB_API_KEY");
        var workspace = Path.of(valueOrDefault(dotenv.get("ANALYSIS_WORKSPACE"), "."))
                .toAbsolutePath()
                .normalize();
        var question = args.length == 0
                ? "Summarize this codebase's architecture, entry points, key dependencies, and likely risks."
                : String.join(" ", args);

        var analyzer = new BobAnalyzer(
                "bob",
                1_000_000,
                positiveInteger(dotenv.get("BOB_MAX_TURNS"), "BOB_MAX_TURNS", 10),
                Duration.ofMillis(positiveInteger(dotenv.get("BOB_TIMEOUT_MS"), "BOB_TIMEOUT_MS", 300_000)),
                "1".equals(dotenv.get("BOB_TRUST_WORKSPACE")));
        var schema = Map.<String, Object>of(
                "type", "object",
                "properties", Map.of("question", Map.of(
                        "type", "string",
                        "description", "A focused question for Bob about the current codebase.")),
                "required", List.of("question"));
        var tool = ToolDefinition.create(
                "analyze_with_bob",
                "Ask IBM Bob Shell to inspect and analyze the current codebase.",
                schema,
                invocation -> CompletableFuture.supplyAsync(() -> {
                    var toolQuestion = (String) invocation.getArguments().get("question");
                    try {
                        return analyzer.analyze(workspace, toolQuestion, bobApiKey);
                    } catch (RuntimeException e) {
                        return "IBM Bob analysis failed: " + rootMessage(e);
                    }
                }));

        try (var client = new CopilotClient()) {
            client.start().get(30, TimeUnit.SECONDS);
            var config = new SessionConfig()
                    .setModel(valueOrDefault(dotenv.get("COPILOT_MODEL"), "auto"))
                    .setWorkingDirectory(workspace.toString())
                    .setAvailableTools(List.of("custom:analyze_with_bob"))
                    .setTools(List.of(tool))
                    .setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
                    .setSystemMessage(new SystemMessageConfig()
                            .setMode(SystemMessageMode.REPLACE)
                            .setContent("""
                                    You are a codebase analysis agent.
                                    For any question about the workspace, call analyze_with_bob before answering.
                                    Base your answer only on Bob's findings.
                                    Synthesize a concise answer and preserve Bob's file-path citations.
                                    If Bob fails, report the error clearly instead of guessing.
                                    """.strip()));

            try (var session = client.createSession(config).get(30, TimeUnit.SECONDS)) {
                var response = session.sendAndWait(
                        new MessageOptions().setPrompt(
                                "Analyze the current codebase and answer this request:\n" + question),
                        10 * 60_000).get(11, TimeUnit.MINUTES);

                if (response == null) {
                    throw new IllegalStateException(
                            "The Copilot agent completed without an assistant response.");
                }
                System.out.println(response.getData().content());
            }
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " is required. Copy .env.example to .env and set the value.");
        }
        return value;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int positiveInteger(String value, String name, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            var parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be a positive integer.", e);
        }
    }

    private static String rootMessage(Throwable error) {
        var cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
