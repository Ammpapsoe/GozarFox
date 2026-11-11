const ROUTES = Object.freeze({
  'GET:/v1/guardian/pass': 'https://vpn.mozilla.org/api/v1/fpn/token',
  'POST:/v1/guardian/activate': 'https://vpn.mozilla.org/api/v1/fpn/activate',
  'GET:/v1/mozilla/servers': 'https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records',
  'POST:/v1/mozilla/account/create': 'https://oauth.accounts.firefox.com/v1/account/create',
  'POST:/v1/mozilla/account/login': 'https://oauth.accounts.firefox.com/v1/account/login',
  'POST:/v1/mozilla/token': 'https://oauth.accounts.firefox.com/v1/token',
  'POST:/v1/mozilla/oauth/token': 'https://oauth.accounts.firefox.com/v1/oauth/token',
  'GET:/v1/mozilla/recovery-email/status': 'https://oauth.accounts.firefox.com/v1/recovery_email/status',
  'POST:/v1/mozilla/recovery-email/verify-code': 'https://oauth.accounts.firefox.com/v1/recovery_email/verify_code',
  'POST:/v1/mozilla/recovery-email/resend-code': 'https://oauth.accounts.firefox.com/v1/recovery_email/resend_code',
  'POST:/v1/mozilla/session/verify-code': 'https://oauth.accounts.firefox.com/v1/session/verify_code'
});

function doGet() {
  return output_(405, {}, {error: 'post_required'});
}

function doPost(e) {
  let request;
  try {
    if (!e || !e.postData || e.postData.type !== 'application/json') return output_(415, {}, {error: 'json_required'});
    if (typeof e.postData.contents !== 'string' || e.postData.contents.length > 16384) return output_(413, {}, {error: 'request_too_large'});
    request = JSON.parse(e.postData.contents);
  } catch (error) {
    return output_(400, {}, {error: 'invalid_json'});
  }
  if (!request || request.v !== 1 || typeof request.path !== 'string' || typeof request.method !== 'string') {
    return output_(400, {}, {error: 'invalid_request'});
  }
  const target = ROUTES[request.method + ':' + request.path];
  if (!target) return output_(405, {}, {error: 'route_not_allowed'});
  const body = request.body === null || request.body === undefined ? '' : request.body;
  if (typeof body !== 'string' || body.length > 4096) return output_(413, {}, {error: 'body_too_large'});
  const authorization = request.authorization === null || request.authorization === undefined ? '' : request.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192) return output_(400, {}, {error: 'invalid_authorization'});
  const headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0',
    'Accept': 'application/json',
    'Accept-Encoding': 'identity',
    'Cache-Control': 'no-store'
  };
  if (authorization) headers.Authorization = authorization;
  const options = {
    method: request.method,
    headers: headers,
    followRedirects: false,
    muteHttpExceptions: true,
    validateHttpsCertificates: true
  };
  if (request.method === 'POST') {
    options.contentType = 'application/json';
    options.payload = body;
  }
  try {
    const response = UrlFetchApp.fetch(target, options);
    const upstreamHeaders = response.getHeaders();
    const allowedHeaders = {};
    ['Content-Type', 'X-Quota-Unlimited', 'X-Quota-Limit', 'X-Quota-Remaining', 'X-Quota-Reset'].forEach(function(name) {
      const value = upstreamHeaders[name] || upstreamHeaders[name.toLowerCase()];
      if (value !== undefined && value !== null && String(value).length <= 1024) allowedHeaders[name] = String(value);
    });
    return output_(response.getResponseCode(), allowedHeaders, null, response.getContentText());
  } catch (error) {
    return output_(502, {}, {error: 'upstream_unavailable'});
  }
}

function output_(status, headers, error, body) {
  const payload = {
    status: status,
    headers: headers || {},
    body: error ? JSON.stringify(error) : String(body || '')
  };
  return ContentService.createTextOutput(JSON.stringify(payload)).setMimeType(ContentService.MimeType.JSON);
}
