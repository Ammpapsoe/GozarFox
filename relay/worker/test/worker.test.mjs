import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { handleRequest } from "../worker.mjs";

function environment(limit = 5) {
  const counts = new Map();
  return {
    counts,
    GOZAR_RATE_LIMIT: {
      async limit({ key }) {
        const count = (counts.get(key) || 0) + 1;
        counts.set(key, count);
        return { success: count <= limit };
      }
    }
  };
}

function request(path, options = {}) {
  return new Request(`https://gozar-guardian-a.gozarfox7.workers.dev${path}`, {
    ...options,
    headers: { "CF-Connecting-IP": "203.0.113.10", ...(options.headers || {}) }
  });
}

test("only exact Mozilla routes and methods are accepted", async () => {
  const env = environment();
  let upstreamCalls = 0;
  const fetcher = async () => {
    upstreamCalls++;
    return new Response("{}", { status: 200 });
  };
  const denied = [
    ["/v1/proxy?url=https://example.org", "GET"],
    ["/v1/guardian/pass?target=example.org", "GET"],
    ["https://example.org/", "GET"],
    ["/api/v1/fpn/token", "GET"],
    ["/v1/guardian/pass", "POST"],
    ["/v1/guardian/activate", "GET"],
    ["/v1/mozilla/servers", "POST"]
  ];
  for (const [path, method] of denied) {
    const target = path.startsWith("https:") ? path : `https://gozar-guardian-a.gozarfox7.workers.dev${path}`;
    const incoming = new Request(target, { method, headers: { "CF-Connecting-IP": "203.0.113.10" } });
    const response = await handleRequest(incoming, env, fetcher);
    assert.ok(response.status >= 400 && response.status < 500);
  }
  assert.equal(upstreamCalls, 0);
});

test("Guardian authorization passes unchanged and identifying headers are stripped", async () => {
  const env = environment();
  let captured;
  const fetcher = async (target, init) => {
    captured = { target, init };
    return new Response(JSON.stringify({ token: "synthetic-pass" }), {
      status: 200,
      headers: {
        "Content-Type": "application/json",
        "X-Quota-Limit": "50",
        "X-Quota-Remaining": "49",
        "X-Quota-Reset": "tomorrow",
        "Set-Cookie": "must-not-escape=value",
        "Server": "must-not-escape"
      }
    });
  };
  const incoming = request("/v1/guardian/pass", {
    headers: {
      Authorization: "Bearer synthetic-token",
      "User-Agent": "identifying-agent",
      Referer: "https://private.invalid/path",
      Cookie: "private=cookie",
      "X-Forwarded-For": "198.51.100.8"
    }
  });
  const response = await handleRequest(incoming, env, fetcher);
  assert.equal(response.status, 200);
  assert.equal(captured.target, "https://vpn.mozilla.org/api/v1/fpn/token");
  assert.equal(captured.init.headers.get("Authorization"), "Bearer synthetic-token");
  assert.equal(captured.init.headers.get("Accept"), "application/json");
  assert.equal(captured.init.headers.get("User-Agent"), "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0");
  for (const name of ["Referer", "Cookie", "X-Forwarded-For", "CF-Connecting-IP"]) {
    assert.equal(captured.init.headers.get(name), null);
  }
  assert.equal(captured.init.redirect, "manual");
  assert.equal(response.headers.get("Set-Cookie"), null);
  assert.equal(response.headers.get("Server"), null);
  assert.equal(response.headers.get("X-Quota-Limit"), "50");
  assert.equal(response.headers.get("Cache-Control"), "no-store, max-age=0");
  assert.deepEqual(await response.json(), { token: "synthetic-pass" });
});

test("first-login activation needs no relay secret and preserves Mozilla 451", async () => {
  const env = environment();
  let calls = 0;
  const fetcher = async (target, init) => {
    calls++;
    assert.equal(target, "https://vpn.mozilla.org/api/v1/fpn/activate");
    assert.equal(init.headers.get("Authorization"), "Bearer first-login");
    assert.equal(init.headers.get("X-Relay-Secret"), null);
    return new Response(JSON.stringify({ code: "unavailable_in_region" }), { status: 451 });
  };
  const response = await handleRequest(request("/v1/guardian/activate", {
    method: "POST",
    headers: {
      Authorization: "Bearer first-login",
      "Content-Type": "application/json",
      "Content-Length": "2",
      "X-Relay-Secret": "not-used"
    },
    body: "{}"
  }), env, fetcher);
  assert.equal(response.status, 451);
  assert.equal(calls, 1);
  assert.equal(response.headers.get("Cache-Control"), "no-store, max-age=0");
});

test("public server-list route cannot carry user authorization upstream", async () => {
  const env = environment();
  const fetcher = async (target, init) => {
    assert.equal(target, "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records");
    assert.equal(init.headers.get("Authorization"), null);
    return new Response(JSON.stringify({ data: [] }), { status: 200 });
  };
  const response = await handleRequest(request("/v1/mozilla/servers", {
    headers: { Authorization: "Bearer must-not-leak" }
  }), env, fetcher);
  assert.equal(response.status, 200);
});

test("edge limiter admits five requests and blocks the sixth", async () => {
  const env = environment(5);
  for (let index = 0; index < 5; index++) {
    const response = await handleRequest(request("/health"), env);
    assert.equal(response.status, 200);
  }
  const blocked = await handleRequest(request("/health"), env);
  assert.equal(blocked.status, 429);
  assert.equal(blocked.headers.get("Retry-After"), "60");
  assert.equal(env.counts.get("203.0.113.10"), 6);
});

test("limiter failure closes the upstream path", async () => {
  let upstreamCalls = 0;
  const env = { GOZAR_RATE_LIMIT: { async limit() { throw new Error("synthetic"); } } };
  const response = await handleRequest(request("/v1/guardian/pass", {
    headers: { Authorization: "Bearer synthetic" }
  }), env, async () => {
    upstreamCalls++;
    return new Response("{}");
  });
  assert.equal(response.status, 503);
  assert.equal(upstreamCalls, 0);
});

test("worker contains no logging or persistence API usage", async () => {
  for (const file of ["../worker.mjs"]) {
    const source = await readFile(new URL(file, import.meta.url), "utf8");
    for (const forbidden of ["console.", "caches.", "DurableObject", ".kv", ".r2", "logpush", "localStorage"]) {
      assert.equal(source.includes(forbidden), false, forbidden);
    }
  }
});
