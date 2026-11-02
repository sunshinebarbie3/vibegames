# VIBE — architecture and security

## Components

```text
Android phone (Kotlin + XML)
  Fragments + ViewBinding + Navigation Component
  Domain: models, validation, deterministic YES/NO DecisionEngine
  Repositories: Firebase-backed HTTPS REST API + local Room cache
  WorkManager + durable timestamped sync/conflict queue
            │ HTTPS + Firebase ID token
            ▼
Node.js / Express REST API (Cloud Run)
  Firebase Admin SDK: Authentication verification and Cloud Messaging
  Cloud Firestore: users, groups, ideas, votes, plans, memories, inbox, receipts
  Firebase Cloud Storage: private photo blobs
```

There is no SQL database in this backend. Firebase Authentication owns password credentials; Firestore is the NoSQL database; Firebase Storage stores private photo objects. `api-container/` (the old EF/Azure SQL service) has been removed so there is a single active REST service at `server/`.

## Offline-first data flow

1. A repository validates an action, updates the local Room row and appends a sync-queue item containing its action, payload and event timestamp.
2. If online, the same request is sent immediately. A successful server response is applied to Room and acknowledges that exact queue row; it cannot be replayed a second time.
3. If offline or the network/API is temporarily unavailable, the UI continues using the cached row. WorkManager retries with network constraints and exponential back-off, and the connectivity observer also triggers a drain.
4. The REST batch endpoint processes ordered items, checks role/rules, and stores idempotency receipts. A stale optimistic update becomes a visible `CONFLICT` with both serialized versions rather than a silent overwrite. The member chooses *Keep mine* or *Keep theirs*. Rejected validation/permission changes stay in a reviewable `FAILED` state.
5. Before logout/account switching, the app refuses to erase pending local work. Account-specific Room rows and staged media are cleared on an accepted account change or confirmed sign-out.

Local photos selected through Android's system photo picker are copied into private app storage before a URI permission expires, resized/orientation-corrected, and sent to the REST API. The API validates file signatures and size before writing an opaque `vibe-blob://` reference to Firestore. Immediate online uploads use the REST API; offline photos are uploaded during ordered replay before their containing memory/profile mutation.

## Fair voting

`app/src/main/java/com/vibe/app/domain/DecisionEngine.kt` and `server/src/domain.js` implement the same round calculation: one immutable YES/NO per member and activity, only members count, results are hidden until completion/deadline/owner closure, non-majority ideas are eliminated, survivors advance, and a bounded final tie is reported. Firestore transactions prevent double votes. The REST API returns only the requesting member's own votes while a round is open; no other member's vote or partial tally is exposed.

## Firestore model

| Collection | Main records |
| --- | --- |
| `users` | Firebase UID, display name, email, locale, privacy and profile blob reference |
| `groups` | owner, member UIDs, shared invite code and creation metadata |
| `invites` | unique six-character invite code → group ID |
| `activities` | group ideas, contributor, status and private favorite-member IDs |
| `decisions` | round snapshot, current state and per-member vote records |
| `plans` / `memories` | schedule, completion, rating, caption and blob references |
| `notifications` / `devices` | local inbox mirrors, FCM device tokens and retry state |
| `receipts` | idempotent applied sync actions, scoped to the Firebase UID |
| `blobs` | opaque photo reference, owner, group, MIME type and storage object key |

Firestore and Storage rules deny mobile/web client access. The API uses its Cloud Run service identity (Application Default Credentials); there is no Admin key in the APK or repository. API data routes require a Firebase ID token. Private image reads require account ownership or group membership; profile photos obey profile privacy.

## Authentication and privacy controls

- Passwords are created and verified by Firebase Authentication; the REST service does not save or log raw passwords. Identity Toolkit responses are normalized to safe API errors.
- Firebase ID and refresh tokens are stored using Android Keystore-backed `EncryptedSharedPreferences`; the OkHttp interceptor redacts authorization headers. Expired ID tokens are refreshed once under a mutex; an invalid refresh clears the session.
- Google credential tokens are checked server-side for signature, issuer, expiry, verified email and configured audience before sign-in or linking.
- REST validation, authentication, role checks, rate limits, bounded request/photo sizes and HTTPS-only Android network security protect account and group operations.
- Backups are disabled for the app so encrypted session state, offline group data and queued changes are not copied to an unmanaged device.
- Account deletion requires reauthentication and leaving/deleting groups first. It removes owned media and records instead of orphaning private data.

## Alerts and localization

The Android Firebase Messaging service registers refreshed FCM tokens, mirrors data-only pushes into Room, localizes the displayed message from resources, observes the notification permission and opens the inbox when tapped. The server first writes an inbox record; failed push delivery leaves it retryable. The Android resources and notification copy ship in English (`en`), isiZulu (`zu`) and Sesotho (`st`).

## Tests and verification

- `server/test/domain.test.js`: hidden tallies, fair participation, majority winner, tie, deadline and outsider/duplicate vote cases.
- `server/test/http.test.js`: API health/storage declaration, authentication guard, input validation, sync rejection and private blob reference validation.
- `app/src/test`: pure decision rules, filters, validators and Room codecs.
- `app/src/androidTest`: offline queue, conflicts, real Room and reconnect behaviour.
- `.github/workflows/android.yml`: Android resource/Room/Kotlin compilation, JVM tests and lint, plus Node API tests.

A successful CI build verifies compile/test/lint. Firebase production behavior, FCM delivery, release signing and the required video must additionally be checked with a configured Firebase project and physical Android phone.
