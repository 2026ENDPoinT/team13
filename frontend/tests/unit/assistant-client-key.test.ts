import { describe, expect, it } from "vitest";
// @ts-expect-error server.mjs 는 타입 선언이 없는 Node 서버 진입점이다.
import { assistantClientKey } from "../../server.mjs";

// /api/assistant 요청 한도의 기준 주소. 터널(cloudflared)은 같은 host 의 loopback 에서 붙으므로
// loopback 이면 Cloudflare 가 넣은 브라우저 주소를, 아니면 소켓 주소를 쓴다.
describe("assistantClientKey", () => {
  it("loopback 뒤에서는 CF-Connecting-IP 를 브라우저 주소로 쓴다", () => {
    expect(assistantClientKey({ remoteAddress: "127.0.0.1", connectingIp: "203.0.113.7", forwardedFor: "198.51.100.1" }))
      .toBe("203.0.113.7");
  });

  it("CF-Connecting-IP 가 없으면 X-Forwarded-For 의 마지막 주소를 쓴다", () => {
    expect(assistantClientKey({ remoteAddress: "::1", forwardedFor: "1.2.3.4, 203.0.113.9" })).toBe("203.0.113.9");
    expect(assistantClientKey({ remoteAddress: "::ffff:127.0.0.1", forwardedFor: ["1.2.3.4", "203.0.113.10"] })).toBe("203.0.113.10");
  });

  it("loopback 이고 전달 헤더가 없으면 소켓 주소 하나로 센다", () => {
    expect(assistantClientKey({ remoteAddress: "127.0.0.1" })).toBe("127.0.0.1");
  });

  it("loopback 이 아닌 상대가 보낸 전달 헤더는 믿지 않는다", () => {
    expect(assistantClientKey({ remoteAddress: "172.24.0.9", connectingIp: "1.2.3.4", forwardedFor: "5.6.7.8" }))
      .toBe("172.24.0.9");
  });
});
