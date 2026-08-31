import path from "node:path";
import process, { loadEnvFile } from "node:process";
import { CopilotClient, defineTool, ToolSet } from "@github/copilot-sdk";
import { z } from "zod";
import { analyzeWithBob } from "./bob.js";

loadLocalEnvironment();

const workspace = path.resolve(process.env.ANALYSIS_WORKSPACE ?? process.cwd());
const question =
  process.argv.slice(2).join(" ").trim() ||
  "Summarize this codebase's architecture, entry points, key dependencies, and likely risks.";

if (!process.env.BOB_API_KEY) {
  throw new Error(
    "BOB_API_KEY is required for IBM Bob Shell headless mode. Export it before running npm start.",
  );
}

const bobAnalysisTool = defineTool("analyze_with_bob", {
  description:
    "Ask IBM Bob Shell to inspect and analyze the current codebase. Use this tool for every codebase analysis request.",
  parameters: z.object({
    question: z
      .string()
      .min(1)
      .describe("A focused question for Bob about the current codebase."),
  }),
  skipPermission: true,
  handler: async ({ question: bobQuestion }) => {
    return analyzeWithBob(workspace, bobQuestion, {
      maxTurns: readPositiveInteger("BOB_MAX_TURNS", 10),
      timeoutMs: readPositiveInteger("BOB_TIMEOUT_MS", 300_000),
      trustWorkspace: process.env.BOB_TRUST_WORKSPACE === "1",
    });
  },
});

const client = new CopilotClient({
  baseDirectory: path.join(process.cwd(), ".copilot-sdk"),
  mode: "empty",
  workingDirectory: workspace,
});

try {
  const session = await client.createSession({
    model: process.env.COPILOT_MODEL ?? "auto",
    workingDirectory: workspace,
    tools: [bobAnalysisTool],
    // tools registers the handler; this allowlist makes it usable. Because the
    // client uses mode: "empty", omitting availableTools would reject session
    // creation instead of falling back to the default CLI tools.
    availableTools: new ToolSet().addCustom("analyze_with_bob"),
    systemMessage: {
      mode: "replace",
      content: [
        "You are a codebase analysis agent.",
        "For any question about the workspace, call analyze_with_bob before answering.",
        "Base your answer only on Bob's findings.",
        "Synthesize a concise answer and preserve Bob's file-path citations.",
        "If Bob fails, report the error clearly instead of guessing.",
      ].join("\n"),
    },
  });

  session.on("tool.execution_start", (event) => {
    console.error(`[tool] ${event.data.toolName}`);
  });

  const response = await session.sendAndWait(
    {
      prompt: `Analyze the current codebase and answer this request:\n${question}`,
    },
    10 * 60_000,
  );

  if (!response) {
    throw new Error("The Copilot agent completed without an assistant response.");
  }

  console.log(response.data.content);
  await session.disconnect();
} finally {
  const errors = await client.stop();
  if (errors.length > 0) {
    throw new AggregateError(errors, "Copilot SDK cleanup failed.");
  }
}

function readPositiveInteger(name: string, fallback: number): number {
  const rawValue = process.env[name];
  if (rawValue === undefined) {
    return fallback;
  }

  const value = Number(rawValue);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer.`);
  }
  return value;
}

function loadLocalEnvironment(): void {
  if (process.env.BOB_API_KEY) {
    return;
  }

  try {
    loadEnvFile();
  } catch (error) {
    if (error instanceof Error && Reflect.get(error, "code") === "ENOENT") {
      return;
    }
    throw error;
  }
}
