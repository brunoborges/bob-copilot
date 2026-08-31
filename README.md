# Copilot SDK agents with IBM Bob Shell

This repository contains equivalent GitHub Copilot SDK samples that expose IBM
Bob Shell as a custom codebase-analysis tool.

## Conceptual architecture

The sample combines two agentic systems with a narrow integration boundary:
Copilot owns the conversation and orchestration, while Bob owns codebase
inspection. The application connects them through a single custom tool.

```mermaid
flowchart LR
    User["User<br/>analysis question"]

    subgraph App["Sample application"]
        Config["Configuration<br/>workspace, model, limits"]
        Client["CopilotClient"]
        Session["Copilot session<br/>system message + tool allowlist"]
        Tool["Custom tool<br/>analyze_with_bob"]
        Adapter["Bob process adapter<br/>timeout + output limit"]
    end

    Runtime["Copilot runtime<br/>agent orchestration"]
    Model["Copilot model"]
    Bob["IBM Bob Shell<br/>headless agent"]
    Codebase[("Target codebase")]

    User --> Client
    Config --> Client
    Client <--> Runtime
    Runtime <--> Model
    Model -->|"tool call"| Session
    Session --> Tool
    Tool --> Adapter
    Adapter -->|"bob run"| Bob
    Bob -->|"read and analyze"| Codebase
    Codebase --> Bob
    Bob -->|"analysis with file citations"| Adapter
    Adapter --> Tool
    Tool -->|"tool result"| Model
    Model -->|"synthesized answer"| User
```

The custom tool is intentionally the only codebase-analysis capability exposed
to the model. Copilot cannot inspect the workspace directly in these samples;
it must ask Bob and base its final answer on Bob's result.

## Request flow

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant App as Node or Java app
    participant SDK as Copilot SDK
    participant Runtime as Copilot runtime
    participant Model as Copilot model
    participant Tool as analyze_with_bob
    participant Bob as IBM Bob Shell
    participant Repo as Codebase

    User->>App: Start sample with a question
    App->>App: Load .env and resolve workspace
    App->>SDK: Create client and configured session
    SDK->>Runtime: Start or connect to runtime
    App->>SDK: sendAndWait(question)
    SDK->>Runtime: Submit user message
    Runtime->>Model: Run configured agent
    Model-->>Runtime: Request analyze_with_bob
    Runtime->>SDK: Dispatch custom tool call
    SDK->>Tool: Invoke handler(question)
    Tool->>Bob: Start bob run with limits
    Bob->>Repo: Inspect files and relationships
    Repo-->>Bob: Source and project metadata
    Bob-->>Tool: Structured analysis output
    Tool-->>SDK: Return tool result
    SDK-->>Runtime: Complete tool call
    Runtime->>Model: Continue with Bob's result
    Model-->>Runtime: Final cited explanation
    Runtime-->>SDK: Assistant message
    SDK-->>App: sendAndWait completes
    App-->>User: Print final answer
```

## Copilot SDK concepts demonstrated

| Concept | Role in this sample | Node.js | Java |
|---|---|---|---|
| **Client** | Owns the connection to the Copilot runtime and its lifecycle. | `CopilotClient` | `CopilotClient` |
| **Runtime** | Executes the agent loop, sends prompts to the model, and dispatches tool calls back to the application. | Bundled Copilot runtime | Installed Copilot CLI |
| **Session** | Holds one conversation plus its model, instructions, tools, workspace, and permissions. | `createSession(...)` | `createSession(SessionConfig)` |
| **System message** | Replaces the default instructions so the model must call Bob before answering codebase questions. | `systemMessage.mode: "replace"` | `SystemMessageMode.REPLACE` |
| **Custom tool** | Defines an application-owned capability that the model may invoke. | `defineTool(...)` | `ToolDefinition.create(...)` |
| **Tool schema** | Describes and validates the `question` argument the model supplies to the tool. | Zod object schema | JSON Schema map |
| **Tool handler** | Runs application code when Copilot selects the tool. Here it starts Bob and returns Bob's analysis. | Async handler function | `CompletableFuture` handler |
| **Tool allowlist** | Restricts the session to `analyze_with_bob`, reducing accidental access to unrelated tools. | `ToolSet.addCustom(...)` | `setAvailableTools(...)` |
| **Permissions** | Controls whether an approved tool call may execute. | Tool uses `skipPermission` | Session uses `PermissionHandler.APPROVE_ALL` |
| **Working directory** | Establishes the codebase context shared by the session and Bob invocation. | `workingDirectory` | `setWorkingDirectory(...)` |
| **Events** | Expose session activity such as tool execution for logging or UI updates. | `tool.execution_start` listener | Event APIs are available but not needed by the sample |
| **Send and wait** | Submits the user prompt and waits until the agent finishes its tool loop and final response. | `sendAndWait(...)` | `sendAndWait(...)` |
| **Lifecycle cleanup** | Releases the session, runtime connection, processes, and executors. | `disconnect()` and `client.stop()` | try-with-resources |

### Client, runtime, and session

The SDK client is not the model itself. It communicates with the Copilot
runtime, which implements the agent loop:

```mermaid
flowchart TD
    Prompt["User prompt"] --> Decide["Model decides next action"]
    Decide -->|"Needs codebase evidence"| Call["Call analyze_with_bob"]
    Call --> Result["Add Bob result to session context"]
    Result --> Decide
    Decide -->|"Enough evidence"| Answer["Return final answer"]
```

A session is the unit that combines the model selection, system message,
available tools, workspace, permissions, and conversation history. The model
may call the custom tool and continue reasoning multiple times before the
session becomes idle and `sendAndWait` returns.

### Custom tools

Custom tools are typed callbacks from the Copilot runtime into application
code. Each definition contains:

1. A stable tool name: `analyze_with_bob`.
2. A description that tells the model when the tool is useful.
3. A parameter schema with one required `question` string.
4. A handler that receives the validated arguments and returns Bob's output.

```mermaid
flowchart LR
    Definition["Tool definition<br/>name + description + schema"]
    Selection["Model selects tool"]
    Validation["SDK validates arguments"]
    Handler["Application handler"]
    Result["Tool result added to context"]

    Definition --> Selection --> Validation --> Handler --> Result
```

The SDK does not know how IBM Bob works. It only knows the tool contract.
Everything behind that contract—starting Bob, passing credentials, enforcing
limits, and interpreting process failures—is owned by the sample application.

### System instructions and tool restrictions

The system message tells the model to:

- call Bob before answering;
- use only Bob's findings;
- preserve Bob's file-path citations;
- report failures rather than inventing an answer.

The tool allowlist reinforces those instructions at the runtime level. The
Node.js client additionally uses `mode: "empty"`, which opts out of the normal
Copilot CLI defaults before explicitly enabling the custom tool. The Java
sample uses an explicit session allowlist for the same effective tool boundary.

### Bob as a nested agent

Bob is invoked as a headless subprocess, not as an SDK-native tool or MCP
server. Conceptually, this is an agent calling another specialized agent:

```mermaid
flowchart TD
    Copilot["Copilot<br/>conversation + orchestration"]
    Contract["analyze_with_bob<br/>custom tool contract"]
    Bob["Bob<br/>codebase investigation"]

    Copilot -->|"focused analysis question"| Contract
    Contract --> Bob
    Bob -->|"evidence and citations"| Contract
    Contract -->|"tool result"| Copilot
```

The adapter starts `bob run` directly without a command shell. It passes the
API key through the subprocess environment and applies a turn limit, timeout,
and output-size limit. Bob is also instructed to operate read-only.

## Implementation lifecycle

```mermaid
stateDiagram-v2
    [*] --> LoadConfiguration
    LoadConfiguration --> CreateClient
    CreateClient --> CreateSession
    CreateSession --> SendPrompt
    SendPrompt --> ToolRequested
    ToolRequested --> BobRunning
    BobRunning --> ToolCompleted
    ToolCompleted --> FinalResponse
    FinalResponse --> Cleanup
    Cleanup --> [*]

    BobRunning --> Failure: timeout, output limit, or Bob error
    Failure --> FinalResponse: surface error without guessing
```

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
