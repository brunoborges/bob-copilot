# Java Copilot SDK agent with IBM Bob Shell

This Java 17+ sample creates a GitHub Copilot SDK agent with one custom tool,
`analyze_with_bob`. The tool runs IBM Bob Shell in headless mode and returns
Bob's codebase analysis to Copilot for synthesis.

## Prerequisites

- Java 17 or later
- Maven 3.9 or later
- GitHub Copilot CLI 1.0.55 or later, installed and authenticated
- IBM Bob Shell installed as `bob`
- A Bob API key

Verify the CLIs:

```bash
copilot --version
bob --version
bob run --help
```

## Configure

From the `java` directory:

```bash
cd java
cp .env.example .env
```

Edit `.env` and set:

```dotenv
BOB_API_KEY=your-bob-api-key
```

The `.env` file is excluded by the repository `.gitignore`. Never commit it.

## Run

```bash
mvn compile exec:java \
  -Dexec.args="Explain the architecture and identify the main entry points"
```

To analyze another codebase, set `ANALYSIS_WORKSPACE` in `.env`.

## Configuration

| Variable | Default | Purpose |
|---|---:|---|
| `BOB_API_KEY` | Required | Bob credential for headless mode |
| `ANALYSIS_WORKSPACE` | Current directory | Codebase Bob analyzes |
| `BOB_MAX_TURNS` | `10` | Bob turn limit |
| `BOB_TIMEOUT_MS` | `300000` | Bob process timeout |
| `BOB_TRUST_WORKSPACE` | unset | Set to `1` to pass `--trust` |
| `COPILOT_MODEL` | `auto` | Copilot model for the agent |

## Development

```bash
mvn test
mvn package
```
