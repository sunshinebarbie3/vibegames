# VIBE — setup, Firebase configuration and phone demonstration

**Stack:** Kotlin + native Android XML, Room offline cache, Firebase Authentication, Cloud Firestore (NoSQL), Firebase Cloud Messaging. No custom backend server. Do not place an Admin SDK service-account key, upload keystore, OAuth secret, or API key in Git or in chat.

## 1. Build the Android app

Install Android Studio, JDK 17 or 21, Android SDK Platform 36 and Build Tools 36.0.0. The Gradle wrapper is in the repository.

```bash
python3 tools/check_strings.py
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug build does not need `google-services.json`; it compiles the client while Firebase is unconfigured. Live sign-in and shared data require the Firebase setup below.

**Install on a physical phone** (Android 7/API 24 or later):

1. In Android Settings, enable Developer Options and USB debugging; authorize this computer.
2. Connect the phone and confirm `adb devices` shows it as `device` (not `unauthorized`).
3. Run `./gradlew :app:installDebug`, or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Launch VIBE on the phone.

## 2. Configure Firebase (required for live sign-in and shared data)

1. Create a Firebase project and an Android app with package **`com.vibe.app`**. Download `google-services.json` into `app/`. This private/local project file is ignored by Git.
2. In **Authentication → Sign-in method**, enable Email/Password and Google.
3. In **Project settings → General**, copy the Firebase **Web API key**. Restrict it to the Identity Toolkit API and your Android app where supported.
4. Create the default Cloud Firestore database. Deploy `server/firestore.rules` (now allows direct client access with membership/ownership rules).
5. In **Authentication → Sign-in method → Google**, note the **Web client ID** (e.g. `123456789012-abcdefghijklmnop.apps.googleusercontent.com`). You will need it for `GOOGLE_SERVER_CLIENT_ID`.
6. Add the Android OAuth client to the Firebase project and register your debug and release SHA-1 certificate fingerprints.
7. Cloud Messaging is used for push. A phone permission prompt appears on Android 13+. FCM is configured through `google-services.json`; no service-account JSON goes in `app/`.

Set Android properties in your **untracked** root `local.properties`. Keep any existing `sdk.dir` line:

```properties
# sdk.dir=/path/to/your/Android/Sdk   # keep if you already have one

GOOGLE_SERVER_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

There is no `API_BASE_URL` anymore: the app talks directly to Firebase Auth and Firestore.

## 3. Firestore Security Rules

`server/firestore.rules` enforces group membership and voting permissions:

- Users can read/write only their own profile (`/users/{uid}`).
- Group members can read groups and activities they belong to; only the owner can create/update/delete a group.
- Votes are stored at predictable IDs `{decisionId}:{activityId}:{userId}`. Rules enforce one vote per member per idea and prevent voting on another user's behalf.
- Only the group owner can create or close a decision round.
- Notifications and FCM device tokens are restricted to the owning user.

Deploy the rules with the Firebase CLI:

```bash
firebase deploy --only firestore:rules
```

## 4. Google sign-in and Play signing

- Put the Google **web client ID** in `GOOGLE_SERVER_CLIENT_ID` for both the Android build and Firebase project. The app exchanges the Google ID token directly with Firebase Authentication.
- Add the Android OAuth client to the Firebase project and register debug and release SHA-1 fingerprints.
- Create and safely back up a private Play upload keystore. Put its path and passwords in an **untracked** `keystore.properties`:

```properties
storeFile=/secure/path/vibe-upload.jks
storePassword=...
keyAlias=vibe-upload
keyPassword=...
```

- Build a release bundle only after configuring credentials:

```bash
./gradlew :app:bundleRelease -PGOOGLE_SERVER_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

`preflightRelease` blocks until `GOOGLE_SERVER_CLIENT_ID` and a signing configuration exist. Verify package name, version, privacy policy, data-safety disclosures, store listing, screenshots and Play Console requirements before upload.

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
```

GitHub Actions runs the Android compiler, XML/Room resource generation, JVM tests and lint. Connected Room instrumentation and physical-device/video checks require an actual Android device; Firebase integration tests require a configured project/emulator.
