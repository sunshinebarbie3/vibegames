# VIBE — setup, Firebase deployment and phone demonstration

**Stack:** Kotlin + native Android XML, Room/WorkManager offline cache, a Node.js REST API, Firebase Authentication, Cloud Firestore (NoSQL), Firebase Cloud Storage, and Firebase Cloud Messaging. Do not place an Admin SDK service-account key, upload keystore, OAuth secret, or API key in Git or in chat.

## 1. Build the Android app

Install Android Studio, JDK 17 or 21, Android SDK Platform 36 and Build Tools 36.0.0. The Gradle wrapper is in the repository.

```bash
python3 tools/check_strings.py
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug build does not need `google-services.json`; it compiles the client while Firebase is unconfigured. Live APIs and authentication require the Firebase setup below. There is no insecure demo login and no bundled fake account.

**Install on a physical phone** (Android 7/API 24 or later):

1. In Android Settings, enable Developer Options and USB debugging; authorize this computer.
2. Connect the phone and confirm `adb devices` shows it as `device` (not `unauthorized`).
3. Run `./gradlew :app:installDebug`, or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Launch VIBE on the phone. Confirm airplane-mode changes appear in the pending banner and are uploaded after reconnection.

## 2. Configure Firebase (required for live features)

1. Create a Firebase project and Android app with package **`com.vibe.app`**. Download `google-services.json` into `app/`. This private/local project file is ignored by Git.
2. In **Authentication → Sign-in method**, enable Email/Password and Google. Create a **Web OAuth client** for the API and an Android OAuth client for your package and signing certificate SHA-1.
3. In **Project settings → General**, copy the Firebase **Web API key**. It is a client identifier, not an Admin credential; restrict it to the required Identity Toolkit API and your Android app where supported.
4. Create the default Cloud Firestore database. Deploy `server/firestore.rules` and `server/firestore.indexes.json`. App clients are denied direct Firestore access: the verified API is the only writer/reader.
5. Create the Firebase Storage bucket. Deploy `server/storage.rules`. Objects are private; the API checks ownership and group membership and streams authorized reads.
6. Cloud Messaging is used for push. A phone permission prompt appears on Android 13+. The API uses the runtime service identity to send FCM; never put a service-account JSON file in `app/`.

Set Android properties in your **untracked** root `local.properties`, or pass `-P` flags:

```properties
API_BASE_URL=https://YOUR-CLOUD-RUN-URL/
GOOGLE_SERVER_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

The original repository default `https://api.vibeapp.com/` is preserved, but is a placeholder—not a deployed service. `API_BASE_URL` must be HTTPS and end in `/`.

## 3. Deploy the REST API to Cloud Run

Install Node.js 22+, Google Cloud CLI and Firebase CLI. Select a Google Cloud project linked to the Firebase project, enable Cloud Run, Cloud Build, Firestore, Firebase Authentication, Cloud Storage and FCM APIs, and create a runtime service account. Give it only the project roles required to administer Firebase Auth, read/write Firestore, read/write Cloud Storage objects, and send FCM messages. Use Secret Manager for the Firebase web key and Google web client ID; do not commit `.env` or service-account files.

For local API tests, create an untracked `server/.env` from `server/.env.example` and use Application Default Credentials (`gcloud auth application-default login`). For deployment, attach the runtime service account to Cloud Run and use the following environment variables:

```text
FIREBASE_PROJECT_ID=your-firebase-project-id
FIREBASE_WEB_API_KEY=...                 # Secret Manager
FIREBASE_STORAGE_BUCKET=...              # e.g. project-id.firebasestorage.app
GOOGLE_SERVER_CLIENT_ID=...              # Secret Manager; same web OAuth client as Android
PORT=8080
```

Example source deployment (configure secrets/service account in your project before running):

```bash
gcloud run deploy vibe-api \
  --source server \
  --region YOUR_REGION \
  --service-account vibe-api-runtime@YOUR_PROJECT.iam.gserviceaccount.com \
  --allow-unauthenticated \
  --set-env-vars FIREBASE_PROJECT_ID=YOUR_PROJECT,FIREBASE_STORAGE_BUCKET=YOUR_BUCKET \
  --set-secrets FIREBASE_WEB_API_KEY=firebase-web-api-key:latest,GOOGLE_SERVER_CLIENT_ID=google-web-client-id:latest
```

`--allow-unauthenticated` means the HTTPS API endpoint is publicly reachable, **not** that account data is public: `/api/v1` requires Firebase ID-token authentication, API requests are rate-limited, and Firestore/Storage client rules deny access. Configure Cloud Run ingress, budgets, quotas, monitoring, backup/retention and IAM for your deployment. Test `https://YOUR-CLOUD-RUN-URL/health` before setting the Android API URL.

Run locally:

```bash
cd server
npm ci
cp .env.example .env  # edit only locally; never commit it
npm test
npm start             # listens on 0.0.0.0:$PORT
```

## 4. Google sign-in and Play signing

- Put the Google **web client ID** in `GOOGLE_SERVER_CLIENT_ID` for both the Android build and API runtime. The API verifies the Google token's issuer, audience, expiry and verified email before exchanging it with Firebase Auth.
- Add the Android OAuth client to the Firebase project and register debug and release SHA-1 fingerprints.
- Create and safely back up a private Play upload keystore. Put its path and passwords in an **untracked** `keystore.properties`:

```properties
storeFile=/secure/path/vibe-upload.jks
storePassword=...
keyAlias=vibe-upload
keyPassword=...
```

- Build a release bundle only after configuring credentials and the deployed API:

```bash
./gradlew :app:bundleRelease -PAPI_BASE_URL=https://YOUR-CLOUD-RUN-URL/ \
  -PGOOGLE_SERVER_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

`preflightRelease` deliberately blocks until `app/google-services.json`, a non-placeholder HTTPS API URL, a Google web client ID and a signing configuration exist. Verify package name, version, privacy policy, data-safety disclosures, store listing, screenshots and Play Console requirements before upload.

## 5. Physical-phone demonstration video checklist

Record **the app running on a real Android phone** (not only an emulator). Use Android's built-in Screen Recorder from Quick Settings, or `adb shell screenrecord /sdcard/vibe-demo.mp4` while operating the connected phone, then pull the recording with `adb pull /sdcard/vibe-demo.mp4`. Verify the saved video before submission. The phone should have Google Play services and internet access for Google SSO/FCM.

Suggested end-to-end walkthrough:

1. Register a new account, log in, and show that an incorrect password is refused. Show Google SSO only after registering the real OAuth clients.
2. Open Settings: change theme and language (English, isiZulu, Sesotho); show notifications and account settings.
3. Create a group; show its generated invite code. Add at least two ideas. If available, use a second phone/account to join through the invite code.
4. Start a vote as owner. Vote YES/NO on each idea. Show equal votes, participation, and that totals are hidden until the round closes. Show the winner and schedule a date/time.
5. Mark the plan complete; choose a photo, caption and rating. Show the saved memory/photo.
6. Turn on airplane mode; add an idea or make another supported offline change; show the offline queue. Reconnect and show it syncing. If possible, deliberately edit the same record on another account to demonstrate conflict resolution.
7. Show a real FCM push on the phone only if Cloud Messaging credentials, permission and a second group member/device are configured.

Do not show personal data, secret keys, OAuth tokens, passwords or service-account files in the video. Capture notification permission and a clean successful run.

## 6. Automated checks

```bash
python3 tools/check_strings.py
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
npm ci --prefix server && npm test --prefix server
```

GitHub Actions runs the Android compiler, XML/Room resource generation, JVM tests, lint and the API unit/contract tests. Connected Room instrumentation and physical-device/video checks require an actual Android device; Firebase integration tests require a configured project/emulator.
