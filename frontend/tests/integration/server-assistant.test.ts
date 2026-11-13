// @vitest-environment node
import { createServer as createHttpServer, request, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it, vi } from "vitest";
// @ts-expect-error server.mjs 는 타입 선언이 없는 Node 서버 진입점이다.
import { createServer } from "../../server.mjs";

// POST /api/assistant: 백엔드(ASTRA_API_ORIGIN)가 있으면 다른 /api 경로처럼 백엔드로 넘기고,
// 없으면(샘플 모드) 이 서버의 Gemini 중계가 그대로 답한다.
const BODY = JSON.stringify({
  text: "오늘 마감인 과제 보여줘", today: "2026-11-14",
  courses: [{ id: "c1", ko: "데이터베이스", en: "DB" }],
  activities: [{ id: "a1", courseId: "c1", type: "report", title: "과제 1", due: "2026-11-14", status: "ongoing", progress: 0 }],
});

const servers: Server[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((server) => new Promise((done) => server.close(done))));
  vi.unstubAllEnvs();
});

async function listen(server: Server): Promise<number> {
  servers.push(server);
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  return (server.address() as AddressInfo).port;
}

function post(port: number, headers: Record<string, string>): Promise<{ status: number; headers: IncomingMessage["headers"]; body: string }> {
  return new Promise((resolve, reject) => {
    const req = request({ host: "127.0.0.1", port, method: "POST", path: "/api/assistant", headers: {
      Host: `127.0.0.1:${port}`, "Content-Type": "application/json", "Content-Length": String(Buffer.byteLength(BODY)), ...headers,
    } }, (res) => {
      const chunks: Buffer[] = [];
      res.on("data", (chunk: Buffer) => chunks.push(chunk));
      res.on("end", () => resolve({ status: res.statusCode || 0, headers: res.headers, body: Buffer.concat(chunks).toString("utf8") }));
    });
    req.on("error", reject);
    req.end(BODY);
  });
}

describe("POST /api/assistant", () => {
  it("백엔드가 있으면 쿠키·CSRF·Origin 과 본문을 그대로 백엔드에 넘기고 그 응답을 돌려준다", async () => {
    const seen: { method?: string; url?: string; headers?: IncomingMessage["headers"]; body?: string } = {};
    const upstream = createHttpServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on("data", (chunk: Buffer) => chunks.push(chunk));
      req.on("end", () => {
        Object.assign(seen, { method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks).toString("utf8") });
        res.writeHead(429, { "Content-Type": "application/problem+json", "Retry-After": "7" });
        res.end(JSON.stringify({ status: 429, code: "rate_limited" }));
      });
    });
    const upstreamPort = await listen(upstream);
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const port = await listen(createServer({ apiOrigin: `http://127.0.0.1:${upstreamPort}` }));

    const res = await post(port, { Cookie: "SESSION=abc; XSRF-TOKEN=t1", "X-XSRF-TOKEN": "t1", Origin: `http://127.0.0.1:${port}` });

    expect(seen.method).toBe("POST");
    expect(seen.url).toBe("/api/assistant");
    expect(seen.headers?.cookie).toBe("SESSION=abc; XSRF-TOKEN=t1");
    expect(seen.headers?.["x-xsrf-token"]).toBe("t1");
    expect(seen.headers?.origin).toBe(`http://127.0.0.1:${port}`);
    expect(seen.body).toBe(BODY);
    expect(res.status).toBe(429);
    expect(res.headers["retry-after"]).toBe("7");
    expect(JSON.parse(res.body).code).toBe("rate_limited");
    // 로컬 Gemini 중계는 쓰지 않는다.
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("백엔드가 없으면 로컬 중계가 모델 답을 검사해 답한다", async () => {
    vi.stubEnv("GEMINI_API_KEY", "test-key");
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      candidates: [{ content: { parts: [{ text: JSON.stringify({ kind: "go", intent: "report", courseId: "c1", targets: ["a1", "zz"], say: "과제 1" }) }] } }],
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    const port = await listen(createServer({ apiOrigin: "" }));

    const res = await post(port, {});

    expect(res.status).toBe(200);
    expect(JSON.parse(res.body)).toEqual({ kind: "go", intent: "report", courseId: "c1", targets: ["a1"], say: "과제 1" });
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(String(fetchSpy.mock.calls[0][0])).toMatch(/:generateContent$/);
  });

  it("백엔드가 없고 키도 없으면 503 이다(화면의 '설정되지 않았습니다')", async () => {
    vi.stubEnv("GEMINI_API_KEY", "");
    const port = await listen(createServer({ apiOrigin: "" }));
    const res = await post(port, {});
    expect(res.status).toBe(503);
  });
});
