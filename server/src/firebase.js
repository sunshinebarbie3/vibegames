import { initializeApp, applicationDefault, getApps } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { getMessaging } from 'firebase-admin/messaging';
import { OAuth2Client } from 'google-auth-library';
import { ApiError, assert } from './errors.js';

export function firebaseServices(env = process.env) {
  assert(env.FIREBASE_PROJECT_ID, 503, 'FIREBASE_PROJECT_ID is not configured.');
  assert(env.FIREBASE_WEB_API_KEY, 503, 'FIREBASE_WEB_API_KEY is not configured.');
  assert(env.FIREBASE_STORAGE_BUCKET, 503, 'FIREBASE_STORAGE_BUCKET is not configured.');
  const app = getApps()[0] || initializeApp({
    credential: applicationDefault(),
    projectId: env.FIREBASE_PROJECT_ID,
    storageBucket: env.FIREBASE_STORAGE_BUCKET,
  });
  const auth = getAuth(app), db = getFirestore(app), bucket = getStorage(app).bucket();
  const google = new OAuth2Client(env.GOOGLE_SERVER_CLIENT_ID);
  async function identity(action, body) {
    const response = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:${action}?key=${encodeURIComponent(env.FIREBASE_WEB_API_KEY)}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...body, returnSecureToken: true }), signal: AbortSignal.timeout(15000),
    });
    const data = await response.json();
    if (!response.ok) {
      const code = data.error?.message || '';
      if (/EMAIL_EXISTS/.test(code)) throw new ApiError(409, 'An account already uses this email.', 'email');
      if (/INVALID_LOGIN_CREDENTIALS|EMAIL_NOT_FOUND|INVALID_PASSWORD|WEAK_PASSWORD/.test(code)) throw new ApiError(401, 'Email or password is incorrect.');
      if (/TOO_MANY_ATTEMPTS/.test(code)) throw new ApiError(429, 'Too many attempts. Try again later.');
      throw new ApiError(401, 'Firebase could not verify this sign-in.');
    }
    return data;
  }
  async function sessionFor(uid) {
    const customToken = await auth.createCustomToken(uid);
    const token = await identity('signInWithCustomToken', { token: customToken });
    return { accessToken: token.idToken, refreshToken: token.refreshToken, expiresAtEpochMillis: Date.now() + Number(token.expiresIn) * 1000 };
  }
  async function refresh(refreshToken) {
    const body = new URLSearchParams({ grant_type: 'refresh_token', refresh_token: refreshToken });
    const response = await fetch(`https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(env.FIREBASE_WEB_API_KEY)}`, {
      method: 'POST', body, signal: AbortSignal.timeout(15000),
    });
    const data = await response.json();
    if (!response.ok) throw new ApiError(401, 'Your session expired. Please sign in again.');
    return { accessToken: data.id_token, refreshToken: data.refresh_token, expiresAtEpochMillis: Date.now() + Number(data.expires_in) * 1000 };
  }
  return {
    auth, db, bucket, messaging: getMessaging(app), sessionFor, refresh,
    verify: token => auth.verifyIdToken(token, true),
    async googleLogin(idToken) {
      await this.googleToken(idToken);
      const tokens = await identity('signInWithIdp', { requestUri: 'http://localhost', postBody: `id_token=${encodeURIComponent(idToken)}&providerId=google.com`, returnIdpCredential: true });
      const verified = await this.verify(tokens.idToken);
      return { uid: verified.uid, accessToken: tokens.idToken, refreshToken: tokens.refreshToken, expiresAtEpochMillis: Date.now() + Number(tokens.expiresIn) * 1000 };
    },
    async unlinkGoogle(firebaseToken) {
      const response = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:update?key=${encodeURIComponent(env.FIREBASE_WEB_API_KEY)}`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({idToken:firebaseToken,deleteProvider:['google.com'],returnSecureToken:true}), signal:AbortSignal.timeout(15000) });
      if (!response.ok) throw new ApiError(409,'Set a password before unlinking Google.');
    },
    async linkGoogle(firebaseToken, googleId) {
      const p = await this.googleToken(googleId), current = await this.verify(firebaseToken);
      assert(current.email?.toLowerCase() === p.email.toLowerCase(), 422, 'Use the Google account with the same email.');
      await identity('signInWithIdp', { idToken: firebaseToken, requestUri: 'http://localhost', postBody: `id_token=${encodeURIComponent(googleId)}&providerId=google.com`, returnIdpCredential: true });
    },
    login: (email, password) => identity('signInWithPassword', { email, password }),
    create: (email, password, displayName) => auth.createUser({ email, password, displayName }),
    async googleToken(idToken) {
      assert(env.GOOGLE_SERVER_CLIENT_ID, 503, 'Google SSO is not configured.');
      try {
        const ticket = await google.verifyIdToken({ idToken, audience: env.GOOGLE_SERVER_CLIENT_ID });
        const p = ticket.getPayload();
        assert(p?.email && p.email_verified && p.sub, 401, 'A verified Google email is required.');
        assert(['accounts.google.com', 'https://accounts.google.com'].includes(p.iss), 401, 'Invalid Google issuer.');
        return p;
      } catch (e) { if (e instanceof ApiError) throw e; throw new ApiError(401, 'Google sign-in could not be verified.'); }
    },
    async reset(email) {
      // Do not disclose whether an email is registered.
      try { await identity('sendOobCode', { requestType: 'PASSWORD_RESET', email }); }
      catch (e) { if (![401, 409].includes(e.status)) throw e; }
    },
  };
}
