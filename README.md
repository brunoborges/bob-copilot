# Copilot SDK agents with IBM Bob Shell

This repository contains equivalent GitHub Copilot SDK samples that expose IBM
Bob Shell as a custom codebase-analysis tool.

| Language | Directory | Runtime |
|---|---|---|
| Node.js / TypeScript | [`nodejs/`](nodejs/) | Node.js 26 |
| Java | [`java/`](java/) | Java 26 and Maven 3.9+ |

Each sample:

- exposes only an `analyze_with_bob` custom tool to the Copilot session;
- runs `bob` directly without a shell;
- asks Bob to inspect the selected workspace in read-only mode;
- bounds Bob's turns, runtime, and captured output;
- loads `BOB_API_KEY` from a git-ignored local `.env` file.

See each language directory for setup and usage instructions.
