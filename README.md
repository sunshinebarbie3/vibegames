<div align="center">

# VIBE

### *Stop arguing. Start vibing.*

A group decision app for students: share ideas, vote fairly, make a plan, save the memory.

**Android:** Kotlin + native XML views · Room offline cache · **Backend:** Firebase Auth + Firestore + FCM

</div>

## What is implemented

- Email/password registration, login, reset and password updates; passwords are handled by Firebase Authentication, not stored in the app.
- Google single sign-on and optional linked Google account.
- Groups, invite codes, members, an idea list and equal YES/NO voting rounds.
- Winner → date/time plan → memory caption, rating and private photo uploads.
- Offline-first Room cache and a timestamped, idempotent work queue. WorkManager syncs on reconnect; conflicts are shown for a member decision.
- Firebase Firestore NoSQL data, Firebase Storage blobs, FCM device alerts and a local notification inbox.
- Dark/light/system theme and English, isiZulu, Sesotho; localized labels and push content.
- Android 7.0+; native XML layouts with ViewBinding.

## Build and run

```bash
python3 tools/check_strings.py
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

A debug APK can compile without Firebase credentials, but live sign-in and shared data require Firebase configuration. See [`docs/SETUP.md`](docs/SETUP.md) before running. There is no hard-coded demo password, hidden demo account, or invented API key.

To install on an actual phone, enable USB debugging and run `adb devices`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`. The assignment asks for a **physical-phone demonstration video**; see the recording checklist in the setup guide. This repository does not pretend that an emulator recording satisfies that requirement.

## Architecture

```text
app/                         Kotlin + XML native Android client
  src/main/res/layout/        XML screens and reusable item layouts
  data/local/                 Room cache, including offline queue
  data/repository/            Auth, groups, voting, memory and media
  data/sync/                  WorkManager retry, conflict review, sync acknowledgements
  domain/                     Decision rules, models and validation
server/                       Firebase Security Rules and indexes (no custom backend)
  src/                        Firebase Auth verification, Firestore and Storage
  firestore.rules              group membership and voting permissions for direct client access
  storage.rules                retained for reference; photos stay on-device in this prototype
prototype/                     Legacy browser walkthrough; not the Android application
```

The API implements the same decision rules as `app/src/main/java/com/vibe/app/domain/DecisionEngine.kt`. Register a real Firebase project and deploy the API before using the live app; the app's API base URL remains configurable and the existing placeholder/default has not been replaced with a fabricated key.

## Submission requirements

The implementation covers registration/login, SSO, settings, REST + NoSQL, offline sync, FCM notification handling, South African language catalogs and an app icon. Before a Play Store bundle or demo recording, supply the real Firebase project configuration, private upload keystore, Google OAuth web client ID, and a physical Android phone. Release bundling intentionally fails its preflight until those are configured.
