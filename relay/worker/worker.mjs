const routes = Object.freeze({
  "/v1/guardian/pass": Object.freeze({
    methods: Object.freeze(["GET", "HEAD"]),
    target: "https://vpn.mozilla.org/api/v1/fpn/token",
    authorization: true
  }),
  "/v1/guardian/activate": Object.freeze({
    methods: Object.freeze(["POST"]),
    target: "https://vpn.mozilla.org/api/v1/fpn/activate",
    authorization: true,
    body: true
  }),
  "/v1/mozilla/servers": Object.freeze({
    methods: Object.freeze(["GET"]),
    target: "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records",
    authorization: false
  })
});

const securityHeaders = Object.freeze({
  "Cache-Control": "no-store, max-age=0",
  "CDN-Cache-Control": "no-store",
  "Cloudflare-CDN-Cache-Control": "no-store",
  "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'; base-uri 'none'",
  "Cross-Origin-Resource-Policy": "same-origin",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
  "Referrer-Policy": "no-referrer",
  "Strict-Transport-Security": "max-age=63072000; includeSubDomains",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY"
});

function failure(status, code, extra = {}) {
  return new Response(JSON.stringify({ error: code }), {
    status,
    headers: { ...securityHeaders, "Content-Type": "application/json", ...extra }
  });
}

async function admit(request, env) {
  if (!env.GOZAR_RATE_LIMIT || typeof env.GOZAR_RATE_LIMIT.limit !== "function") {
    return failure(503, "rate_limiter_unavailable");
  }
  const key = request.headers.get("CF-Connecting-IP") || "unknown";
  try {
    const verdict = await env.GOZAR_RATE_LIMIT.limit({ key });
    if (!verdict || verdict.success !== true) {
      return failure(429, "rate_limited", { "Retry-After": "60" });
    }
  } catch {
    return failure(503, "rate_limiter_unavailable", { "Retry-After": "30" });
  }
  return null;
}

export async function handleRequest(request, env, fetcher = fetch) {
  const rejected = await admit(request, env);
  if (rejected) return rejected;
  const url = new URL(request.url);
  if (url.protocol !== "https:" || url.search || url.username || url.password || url.port) {
    return failure(400, "invalid_request");
  }
  if (url.pathname === "/health") {
    if (request.method !== "GET") return failure(405, "method_not_allowed", { Allow: "GET" });
    return new Response(JSON.stringify({ ready: true, version: 1 }), {
      status: 200,
      headers: { ...securityHeaders, "Content-Type": "application/json" }
    });
  }
  const route = routes[url.pathname];
  if (!route) return failure(404, "not_found");
  if (!route.methods.includes(request.method)) {
    return failure(405, "method_not_allowed", { Allow: route.methods.join(", ") });
  }
  const headers = new Headers({
    Accept: "application/json",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"
  });
  if (route.authorization) {
    const authorization = request.headers.get("Authorization");
    if (!authorization || authorization.length > 16384) return failure(401, "authorization_required");
    headers.set("Authorization", authorization);
  }
  let body;
  if (route.body) {
    const contentType = (request.headers.get("Content-Type") || "").split(";", 1)[0].trim().toLowerCase();
    if (contentType !== "application/json") return failure(415, "json_required");
    const declared = Number(request.headers.get("Content-Length") || "0");
    if (!Number.isSafeInteger(declared) || declared < 1 || declared > 4096) {
      return failure(413, "invalid_body_size");
    }
    const bytes = await request.arrayBuffer();
    if (bytes.byteLength < 1 || bytes.byteLength > 4096) return failure(413, "invalid_body_size");
    headers.set("Content-Type", "application/json");
    body = bytes;
  }
  try {
    const options = {
      method: request.method,
      headers,
      body,
      redirect: "manual",
      cf: { cacheEverything: false, cacheTtl: 0 }
    };
    const upstream = await fetcher(route.target, options);
    const responseHeaders = new Headers(securityHeaders);
    const contentType = upstream.headers.get("Content-Type");
    if (contentType) responseHeaders.set("Content-Type", contentType);
    for (const name of ["X-Quota-Unlimited", "X-Quota-Limit", "X-Quota-Remaining", "X-Quota-Reset"]) {
      const value = upstream.headers.get(name);
      if (value) responseHeaders.set(name, value);
    }
    return new Response(request.method === "HEAD" ? null : upstream.body, {
      status: upstream.status,
      headers: responseHeaders
    });
  } catch {
    return failure(502, "mozilla_unavailable", { "Retry-After": "5" });
  }
}

export default { fetch: handleRequest };
