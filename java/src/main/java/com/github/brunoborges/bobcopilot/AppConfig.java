package com.github.brunoborges.bobcopilot;

import java.nio.file.Path;
import java.time.Duration;

import io.github.cdimascio.dotenv.Dotenv;

record AppConfig(
        String bobApiKey,
        Path workspace,
        int bobMaxTurns,
        Duration bobTimeout,
        boolean trustWorkspace,
        String copilotModel) {

    static AppConfig load() {
        var dotenv = Dotenv.configure().ignoreIfMissing().load();
        return new AppConfig(
                required(dotenv.get("BOB_API_KEY"), "BOB_API_KEY"),
                Path.of(valueOrDefault(dotenv.get("ANALYSIS_WORKSPACE"), "."))
                        .toAbsolutePath()
                        .normalize(),
                positiveInteger(dotenv.get("BOB_MAX_TURNS"), "BOB_MAX_TURNS", 10),
                Duration.ofMillis(
                        positiveInteger(dotenv.get("BOB_TIMEOUT_MS"), "BOB_TIMEOUT_MS", 300_000)),
                "1".equals(dotenv.get("BOB_TRUST_WORKSPACE")),
                valueOrDefault(dotenv.get("COPILOT_MODEL"), "auto"));
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
}
