function csrfToken() {
  const prefix = '__Host-memory-csrf=';
  const item = document.cookie.split('; ').find((value) => value.startsWith(prefix));
  return item ? decodeURIComponent(item.slice(prefix.length)) : '';
}

function idempotencyKey() { return crypto.randomUUID(); }

class ReauthenticationRequiredError extends Error {}
class StaleVersionError extends Error {}

async function request(path, options = {}) {
  const response = await fetch(path, { credentials: 'same-origin', ...options });
  if (response.status === 409) throw new StaleVersionError();
  if (response.status === 401 || response.status === 403) throw new ReauthenticationRequiredError();
  if (!response.ok) throw new Error('The request could not be completed.');
  return response.status === 204 ? null : response.json();
}

function mutation(method, path, version, body) {
  const headers = { 'X-Memory-CSRF': csrfToken(), 'Idempotency-Key': idempotencyKey() };
  if (version) headers['If-Match'] = version;
  if (body) headers['Content-Type'] = 'application/json';
  return request(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
}

export const api = {
  consent: () => request('/v1/me/memory-consent'),
  saveConsent: (version, value) => mutation('PUT', '/v1/me/memory-consent', version, value),
  memories: ({ type = '', status = '' } = {}) => {
    const params = new URLSearchParams();
    if (type) params.set('type', type);
    if (status) params.set('status', status);
    const query = params.toString();
    return request(`/v1/me/memories${query ? `?${query}` : ''}`);
  },
  memory: (id) => request(`/v1/me/memories/${encodeURIComponent(id)}`),
  confirm: (id, version) => mutation('POST', `/v1/me/memories/${encodeURIComponent(id)}:confirm`, version),
  correct: (id, version, value) => mutation('POST', `/v1/me/memories/${encodeURIComponent(id)}:correct`, version, value),
  disable: (id, version) => mutation('POST', `/v1/me/memories/${encodeURIComponent(id)}:disable`, version),
  forget: (id, version) => mutation('DELETE', `/v1/me/memories/${encodeURIComponent(id)}`, version),
};

export { ReauthenticationRequiredError, StaleVersionError };
