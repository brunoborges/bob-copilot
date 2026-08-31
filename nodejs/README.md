# Copilot SDK agent with IBM Bob Shell

This sample creates a Node.js/TypeScript custom agent with the GitHub Copilot
SDK. The agent has one custom tool, `analyze_with_bob`, which runs IBM Bob
Shell in headless mode against the selected workspace and returns Bob's
codebase analysis to Copilot for synthesis.

See the repository-level [conceptual architecture and Copilot SDK
guide](../README.md#conceptual-architecture) for diagrams of the complete
agent and tool-call flow.

## Prerequisites

- Node.js 26.8.1
- A GitHub Copilot subscription and authentication, unless using a supported
  Copilot SDK BYOK configuration
- IBM Bob Shell installed as `bob`, authenticated, and licensed
- A Bob API key for headless mode

Verify Bob before configuring the project:

```bash
bob --version
bob run --help
```

## Configure

From the `nodejs` directory, install the dependencies and create your local
environment file:

```bash
cd nodejs
npm install
cp .env.example .env
```

Edit `.env` and set your Bob API key:

```dotenv
BOB_API_KEY=your-bob-api-key
```

The project loads `.env` with Node.js's native `process.loadEnvFile()` API. The
`.env` file is excluded by `.gitignore` and must never be committed.
`.env.example` documents the supported settings without containing credentials.

## Run

```bash
npm start -- "Explain the architecture and identify the main entry points"
```

By default, the sample analyzes its current working directory. To analyze
another codebase:

```bash
ANALYSIS_WORKSPACE=/path/to/repository \
  npm start -- "Find risky error-handling and cite the relevant files"
```

Bob may require the workspace to have been trusted previously. You can opt in
to Bob's `--trust` flag for this run:

```bash
BOB_TRUST_WORKSPACE=1 npm start -- "Summarize the codebase"
```

Only enable that flag for a workspace you have reviewed and trust.

## Configuration

| Variable | Default | Purpose |
|---|---:|---|
| `ANALYSIS_WORKSPACE` | Current directory | Codebase Bob analyzes |
| `BOB_MAX_TURNS` | `10` | Bob turn limit |
| `BOB_TIMEOUT_MS` | `300000` | Bob process timeout |
| `BOB_TRUST_WORKSPACE` | unset | Set to `1` to pass `--trust` |
| `COPILOT_MODEL` | `auto` | Copilot model for the custom agent |

The Copilot client uses `mode: "empty"` and exposes only the Bob custom tool.
The Bob subprocess is started directly without a shell, is instructed to work
read-only, has bounded turns and runtime, and has a 1 MB output limit.

## Development

```bash
npm test
npm run typecheck
npm run build
```
