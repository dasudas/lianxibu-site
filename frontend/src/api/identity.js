import { ApiError } from './client.js';

export function createIdentityApi(client) {
  return Object.freeze({ async login({ username, password }, { signal } = {}) {
    if (typeof username !== 'string' || [...username].length < 1 || [...username].length > 64
        || typeof password !== 'string' || [...password].length < 1 || [...password].length > 1024) {
      throw new TypeError('Username and password are required within their field limits');
    }
    const { data } = await client.request('POST', '/session', { body: { username, password }, signal });
    if (!data || data.authenticated !== true || typeof data.user_id !== 'string' || !data.user_id
        || [...data.user_id].length > 64 || /[\u0000-\u001f\u007f]/.test(data.user_id) || !['user', 'owner'].includes(data.role)) {
      throw new ApiError(200, 'invalid_response', 'Authenticated identity is required');
    }
    return Object.freeze({ id: data.user_id, role: data.role });
  } });
}
