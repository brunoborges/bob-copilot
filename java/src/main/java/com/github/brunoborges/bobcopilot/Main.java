package com.github.brunoborges.bobcopilot;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.github.copilot.CopilotClient;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.ToolDefinition;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        var config = AppConfig.load();
        var question = args.length == 0
                ? "Summarize this codebase's architecture, entry points, key dependencies, and likely risks."
                : String.join(" ", args);

        var analyzer = new BobAnalyzer(
                "bob",
                1_000_000,
                config.bobMaxTurns(),
                config.bobTimeout(),
                config.trustWorkspace());
        var schema = Map.<String, Object>of(
                "type", "object",
                "properties", Map.of("question", Map.of(
                        "type", "string",
                        "description", "A focused question for Bob about the current codebase.")),
                "required", List.of("question"));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var client = new CopilotClient()) {
            var tool = ToolDefinition.create(
                    "analyze_with_bob",
                    "Ask IBM Bob Shell to inspect and analyze the current codebase.",
                    schema,
                    invocation -> CompletableFuture.supplyAsync(() -> {
                        var toolQuestion = (String) invocation.getArguments().get("question");
                        try {
                            return analyzer.analyze(config.workspace(), toolQuestion, config.bobApiKey());
                        } catch (RuntimeException e) {
                            return "IBM Bob analysis failed: " + rootMessage(e);
                        }
                    }, executor));

            client.start().get(30, TimeUnit.SECONDS);
            var sessionConfig = new SessionConfig()
                    .setModel(config.copilotModel())
                    .setWorkingDirectory(config.workspace().toString())
                    // setTools registers the handler; this allowlist also hides the
                    // default CLI tools so Bob remains the sole analysis capability.
                    // Omitting it would still register Bob but expose default tools too.
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

            try (var session = client.createSession(sessionConfig).get(30, TimeUnit.SECONDS)) {
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

    private static String rootMessage(Throwable error) {
        var cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
