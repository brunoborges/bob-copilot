import { spawn } from "node:child_process";

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

  return runCommand(options.bobCommand ?? "bob", args, {
    cwd: workspace,
    maxOutputBytes: options.maxOutputBytes ?? DEFAULT_MAX_OUTPUT_BYTES,
    timeoutMs: options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
  });
}

interface RunCommandOptions {
  cwd: string;
  maxOutputBytes: number;
  timeoutMs: number;
}

function runCommand(
  command: string,
  args: string[],
  options: RunCommandOptions,
): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd,
      env: process.env,
      shell: false,
      stdio: ["ignore", "pipe", "pipe"],
    });

    const stdout: Buffer[] = [];
    const stderr: Buffer[] = [];
    let outputBytes = 0;
    let settled = false;

    const finish = (error?: Error, value?: string) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timeout);
      if (error) {
        reject(error);
      } else {
        resolve(value ?? "");
      }
    };

    const collect = (target: Buffer[], chunk: Buffer) => {
      outputBytes += chunk.length;
      if (outputBytes > options.maxOutputBytes) {
        child.kill("SIGTERM");
        finish(
          new Error(
            `IBM Bob output exceeded ${options.maxOutputBytes} bytes and was terminated.`,
          ),
        );
        return;
      }
      target.push(chunk);
    };

    child.stdout.on("data", (chunk: Buffer) => collect(stdout, chunk));
    child.stderr.on("data", (chunk: Buffer) => collect(stderr, chunk));

    child.on("error", (error) => {
      finish(
        new Error(
          `Unable to start IBM Bob Shell (${command}): ${error.message}`,
          { cause: error },
        ),
      );
    });

    child.on("close", (code, signal) => {
      const output = Buffer.concat(stdout).toString("utf8").trim();
      const errorOutput = Buffer.concat(stderr).toString("utf8").trim();

      if (code === 0) {
        finish(undefined, output);
        return;
      }

      const reason = signal ? `signal ${signal}` : `exit code ${code}`;
      finish(
        new Error(
          [`IBM Bob Shell failed with ${reason}.`, errorOutput || output]
            .filter(Boolean)
            .join("\n"),
        ),
      );
    });

    const timeout = setTimeout(() => {
      child.kill("SIGTERM");
      finish(
        new Error(`IBM Bob Shell timed out after ${options.timeoutMs} ms.`),
      );
    }, options.timeoutMs);
  });
}
