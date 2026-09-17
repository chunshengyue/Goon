#!/usr/bin/env node
/**
 * Minimal MCP server (Streamable HTTP) used to verify Goon's MCP client end to end.
 *
 * It implements just enough of the spec: initialize / notifications/initialized / tools/list / tools/call,
 * answers either as JSON or as an SSE frame (?sse=1), and echoes the session id so we can prove the
 * client carries it. Tools: `echo` (text) and `clock` (no arguments).
 *
 * Run:  node tools/mcp/echo_server.mjs [port]
 * Then: adb reverse tcp:9100 tcp:9100   (device reaches the host via 127.0.0.1:9100)
 */
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";

const port = Number(process.argv[2] || 9100);
const sessions = new Set();

const TOOLS = [
  {
    name: "echo",
    description: "回显传入的文本，用来验证工具调用链路是否通。",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string", description: "要回显的文本" } },
      required: ["text"],
    },
  },
  {
    name: "clock",
    description: "返回服务端当前时间（ISO 8601）。",
    inputSchema: { type: "object", properties: {} },
  },
];

function handle(message) {
  const { id, method, params } = message;
  switch (method) {
    case "initialize":
      return { jsonrpc: "2.0", id, result: {
        protocolVersion: params?.protocolVersion || "2025-06-18",
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: "goon-echo", version: "0.1.0" },
      } };
    case "notifications/initialized":
      return null;
    case "tools/list":
      return { jsonrpc: "2.0", id, result: { tools: TOOLS } };
    case "tools/call": {
      const name = params?.name;
      const tool = TOOLS.find((t) => t.name === name);
      if (!tool) {
        return { jsonrpc: "2.0", id, result: { content: [{ type: "text", text: `未知工具 ${name}` }], isError: true } };
      }
      const text = name === "echo"
        ? `echo: ${params?.arguments?.text ?? "(空)"}`
        : `server time: ${new Date().toISOString()}`;
      return { jsonrpc: "2.0", id, result: { content: [{ type: "text", text }] } };
    }
    default:
      return { jsonrpc: "2.0", id, error: { code: -32601, message: `未实现的方法：${method}` } };
  }
}

createServer((req, res) => {
  if (req.method !== "POST") {
    res.writeHead(405).end("POST only");
    return;
  }
  let body = "";
  req.on("data", (chunk) => (body += chunk));
  req.on("end", () => {
    let message;
    try {
      message = JSON.parse(body);
    } catch {
      res.writeHead(400, { "Content-Type": "application/json" }).end(JSON.stringify({ error: "bad json" }));
      return;
    }
    const reply = handle(message);
    const headers = {};
    if (message.method === "initialize") {
      const session = randomUUID();
      sessions.add(session);
      headers["Mcp-Session-Id"] = session;
    } else if (req.headers["mcp-session-id"]) {
      headers["Mcp-Session-Id"] = req.headers["mcp-session-id"];
    }
    if (!reply) {
      res.writeHead(202, headers).end();
      return;
    }
    // ?sse=1 时用 SSE 帧回答，用来验证客户端两种响应格式都能解析。
    if (new URL(req.url, "http://x").searchParams.get("sse") === "1") {
      res.writeHead(200, { ...headers, "Content-Type": "text/event-stream", "Cache-Control": "no-store" });
      res.end(`event: message\ndata: ${JSON.stringify(reply)}\n\n`);
      return;
    }
    res.writeHead(200, { ...headers, "Content-Type": "application/json" });
    res.end(JSON.stringify(reply));
  });
}).listen(port, "127.0.0.1", () => {
  console.log(`mcp echo server on http://127.0.0.1:${port} (tools: ${TOOLS.map((t) => t.name).join(", ")})`);
});
