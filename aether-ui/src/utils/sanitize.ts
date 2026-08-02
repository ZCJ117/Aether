import DOMPurify from 'dompurify';

/** HTML entity escaping for XSS prevention */
export function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#x27;');
}

/** Recursively sanitize all string values in an object */
export function sanitizeObject<T>(obj: T): T {
  if (typeof obj === 'string') {
    return DOMPurify.sanitize(obj, { ALLOWED_TAGS: [], ALLOWED_ATTR: [] }) as unknown as T;
  }
  if (Array.isArray(obj)) {
    return obj.map(sanitizeObject) as unknown as T;
  }
  if (obj !== null && typeof obj === 'object') {
    const result: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
      result[key] = sanitizeObject(value);
    }
    return result as T;
  }
  return obj;
}

/** Escape request body string values (preserves password/token fields) */
export function escapeRequestData<T>(data: T): T {
  if (typeof data === 'string') {
    return escapeHtml(data) as unknown as T;
  }
  if (Array.isArray(data)) {
    return data.map(escapeRequestData) as unknown as T;
  }
  if (data !== null && typeof data === 'object') {
    const result: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(data as Record<string, unknown>)) {
      const lowerKey = key.toLowerCase();
      if (lowerKey.includes('password') || lowerKey.includes('token') || lowerKey.includes('secret')) {
        result[key] = value;
      } else {
        result[key] = escapeRequestData(value);
      }
    }
    return result as T;
  }
  return data;
}
