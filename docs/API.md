# VIBE REST API (Firebase backend)

The service in `server/` is the only active REST backend. It runs on Node.js/Express and uses Firebase Admin SDK; it never connects to SQL. The app requires HTTPS and sends `Authorization: Bearer <Firebase ID token>` for account/group operations. Responses are JSON camelCase with UTC epoch-millisecond timestamps. JSON error bodies contain a safe `error` and optional `field`; open vote responses never expose another member's vote/tally.

## Authentication

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | Firebase email/password registration |
| POST | `/api/v1/auth/login` | Password sign-in; returns Firebase ID/refresh tokens and profile |
| POST | `/api/v1/auth/google` | Verify Google ID token and exchange/sign-in with Firebase |
| POST | `/api/v1/auth/refresh` | Rotate Firebase session using a refresh token |
| POST | `/api/v1/auth/password-reset` | Firebase password reset; does not disclose whether an email exists |
| POST | `/api/v1/auth/logout` | Revoke Firebase refresh tokens |
| GET | `/api/v1/auth/providers` | List connected identity providers |
| POST/DELETE | `/api/v1/auth/google/link` | Link or unlink a verified Google provider |
| GET/PATCH/DELETE | `/api/v1/users/me` | Read/update profile, or delete after reauthentication and group cleanup |
| POST | `/api/v1/users/me/password` | Change password after reauthentication |

## Groups and activities

| Method | Path | Purpose |
| --- | --- | --- |
| GET/POST | `/api/v1/groups` | List memberships / create a group (optional client ID and invite code make replay idempotent) |
| POST | `/api/v1/groups/join` | Join with the six-character invite code |
| GET | `/api/v1/groups/{id}` | Authorized group, role and member list |
| DELETE | `/api/v1/groups/{id}` | Owner deletes group and group-owned blobs/records |
| POST | `/api/v1/groups/{id}/leave` | Member leaves; owner must transfer/delete the group |
| GET/POST | `/api/v1/groups/{id}/activities` | Read/add Vibe List ideas |
| GET | `/api/v1/users/me/activities` | Ideas the member contributed |
| PATCH/DELETE | `/api/v1/activities/{id}` | Authorized idea edit/favorite or delete |

## Votes, plans, memories

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v1/groups/{id}/decisions` | Owner starts an eligible round; includes an idempotency key |
| GET | `/api/v1/decisions/{id}` | Current round and only requesting member's votes until closure |
| POST | `/api/v1/decisions/{id}/votes` | Unique member/activity YES or NO vote |
| POST | `/api/v1/decisions/{id}/close` | Owner closes early or completed round settles automatically |
| POST | `/api/v1/decisions/{id}/surprise` | Pick a random eligible idea not in the rejected list |
| GET | `/api/v1/groups/{id}/plans` | Authorized group plans |
| POST | `/api/v1/plans` | Save/update a plan (`clientId` supported) |
| POST | `/api/v1/plans/{id}/complete` | Complete the plan and save rating, caption and blob references |
| PATCH | `/api/v1/memories/{id}` | Update a saved memory |
| GET | `/api/v1/groups/{id}/memories` | Group memories |

## Private blobs, notifications and sync

- `POST /api/v1/media` — authenticated multipart JPEG, PNG or WebP upload (10 MB maximum); optional `groupId` requires membership. Returns `{ id, uri: "vibe-blob://…" }`.
- `GET /api/v1/media/{id}` — streams a private photo only to its owner or authorized group member; profile privacy also applies.
- `GET /api/v1/notifications`, `POST /api/v1/notifications/{id}/read`, `POST /api/v1/notifications/read-all` — member inbox.
- `POST /api/v1/devices/register`, `/unregister` — associate/revoke an FCM token with the authenticated UID.
- `POST /api/v1/sync/batch` — at most 50 timestamped client actions; each receives `applied`, `conflict`, or `rejected` plus server version/message where safe. Applied client IDs receive per-user Firestore receipts for replay protection.
- `GET /health` — unauthenticated service status, identifies Firestore and Firebase Storage.

## Errors and security

- `400` not used for silent application errors; Zod input validation returns `422` with a field when possible.
- `401` expired/missing Firebase ID token or invalid credentials; `403` group/role check; `404` unavailable resource; `409` duplicate/conflicting action; `413` oversized upload; `429` rate limit; `5xx` safe retry message.
- Never pass the Firebase Admin service account to the app. Firestore and Storage client rules default to deny all; the REST API validates each request with Firebase Admin ID-token verification and membership/ownership checks.
- Set `FIREBASE_PROJECT_ID`, `FIREBASE_WEB_API_KEY`, `FIREBASE_STORAGE_BUCKET`, and `GOOGLE_SERVER_CLIENT_ID` on the API runtime. See [`SETUP.md`](SETUP.md) for Cloud Run IAM and secret setup.
