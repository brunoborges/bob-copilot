import { execFile, type ExecFileException } from "node:child_process";

export interface BobAnalysisOptions {
  bobCommand?: string;
  maxOutputBytes?: number;
  maxTurns?: number;
  timeoutMs?: number;
  trustWorkspace?: boolean;
}

const DEFAULT_MAX_OUTPUT_BYTES = 1_000_000;
const DEFAULT_MAX_TURNS = 10;
const DEFAULT_TIMEOUT_MS = 5 * 60_000;

export async function analyzeWithBob(
  workspace: string,
  question: string,
  options: BobAnalysisOptions = {},
): Promise<string> {
  const args = [
    "run",
    "--format",
    "json",
    "--workspace",
    workspace,
    "--mode",
    "agent",
    "--max-turns",
    String(options.maxTurns ?? DEFAULT_MAX_TURNS),
    "--log-level",
    "error",
    "--disable-subagents",
  ];

  if (options.trustWorkspace) {
    args.push("--trust");
  }

  args.push(
    [
      "Analyze the workspace in read-only mode. Do not create, edit, move, or delete files.",
      "Use evidence from the codebase and cite relevant file paths.",
      `Analysis request: ${question}`,
    ].join("\n"),
  );

  const command = options.bobCommand ?? "bob";
  const maxOutputBytes = options.maxOutputBytes ?? DEFAULT_MAX_OUTPUT_BYTES;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  return new Promise((resolve, reject) => {
    const child = execFile(
      command,
      args,
      {
        cwd: workspace,
        encoding: "utf8",
        env: process.env,
        killSignal: "SIGTERM",
        maxBuffer: maxOutputBytes,
        timeout: timeoutMs,
        windowsHide: true,
      },
      (error, stdout, stderr) => {
        if (!error) {
          resolve(stdout.trim());
          return;
        }
        reject(
          toBobError(
            error,
            stdout,
            stderr,
            command,
            maxOutputBytes,
            timeoutMs,
          ),
        );
      },
    );

    child.stdin?.end();
  });
}

function toBobError(
  error: ExecFileException,
  stdout: string,
  stderr: string,
  command: string,
  maxOutputBytes: number,
  timeoutMs: number,
): Error {
  if (error.code === "ENOENT") {
    return new Error(
      `Unable to start IBM Bob Shell (${command}): ${error.message}`,
      { cause: error },
    );
  }
  if (error.code === "ERR_CHILD_PROCESS_STDIO_MAXBUFFER") {
    return new Error(
      `IBM Bob output exceeded ${maxOutputBytes} bytes and was terminated.`,
      { cause: error },
    );
  }
  if (error.killed && error.signal === "SIGTERM") {
    return new Error(`IBM Bob Shell timed out after ${timeoutMs} ms.`, {
      cause: error,
    });
  }

  const output = [stderr, stdout].filter(Boolean).join("\n").trim();
  return new Error(
    [`IBM Bob Shell failed with exit code ${String(error.code)}.`, output]
      .filter(Boolean)
      .join("\n"),
    { cause: error },
  );
}
