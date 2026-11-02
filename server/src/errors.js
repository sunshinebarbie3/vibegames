export class ApiError extends Error {
  constructor(status, message, field) { super(message); this.status = status; this.field = field; }
}
export const assert = (condition, status, message, field) => { if (!condition) throw new ApiError(status, message, field); };
export const idValid = id => typeof id === 'string' && /^[a-zA-Z0-9_-]{1,120}$/.test(id);
