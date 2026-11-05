# Acceptance record — screenshot requirements

This record separates implemented source and automated evidence from external setup/device evidence. “Implemented” does not mean that an unconfigured Firebase project or a real phone has been fabricated.

| Requirement | Implementation | Evidence / remaining action |
| --- | --- | --- |
| Kotlin and Android XML | Kotlin repositories/domain/fragments; XML layouts, Navigation, themes and ViewBinding; Compose screens/dependencies removed | Android CI compile, XML resource check; install APK on physical device |
| Register and log in; protect passwords | Firebase Authentication; API validates identity; Android encrypts tokens with Android Keystore | Node tests cover validation/auth guard; live login needs Firebase project |
| Google SSO | Credential Manager Google ID token; API checks audience/signature/issuer/verified email and exchanges with Firebase | Configure real Android and Web OAuth clients, SHA fingerprints and client ID |
| Change settings | Theme/language/notification/privacy update controls persisted through DataStore/API | Exercise on physical phone against deployed API |
| REST API and database | Authenticated Node REST API; Firestore is the NoSQL source of record, Room is the offline cache | API unit/contract tests; deploy API and configure Firestore |
| Blob storage | Photo picker, private app staging, size/orientation normalization, API MIME signature validation, private Firebase Storage, membership-checked reads | Configure bucket and verify a real photo upload on phone |
| Offline mode with sync | Room queue contains action, payload, stable ID and timestamp; WorkManager retry; immediate requests acknowledge queue; conflict UI offers both versions | JVM and connected Room instrumentation; use airplane mode to record proof |
| Real-time notification | FCM token lifecycle, server inbox-before-push, permission-aware localized local notification and inbox deep link | Configure Firebase/Google Play device; demonstrate two accounts/devices |
| South African languages | English, isiZulu and Sesotho XML catalogs cover 395 matching keys; notification copy localizes | Run `python3 tools/check_strings.py`; inspect all languages on device |
| Play Store preparation | Release signing configuration is local-only; shrink/minify, lint, version settings and explicit release preflight; Android CI | Add real API/OAuth/Firebase config and private upload keystore; validate Play listing, privacy/data-safety and policy |
| App icon/assets | Existing VIBE adaptive/round icon and launcher art retained | Inspect exported APK on Android device; supply store screenshots/feature graphic |
| Physical-phone demonstration video | No source code can honestly produce evidence of operation on the student's phone | Install APK and record the physical-device checklist in `SETUP.md`; submit the resulting video separately |

## Automated checks

```bash
python3 tools/check_strings.py
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
npm ci --prefix server && npm test --prefix server
```

The Node suite covers decision rules (hidden tallies, fairness, deadlines, ties), API authentication/validation, sync rejections and blob-reference validation. Android JVM tests cover the pure decision engine, filters, validators and Room codecs. Room instrumentation needs a device/emulator. Live OAuth/Firestore/Storage/FCM, release signing, Play publication and physical-phone video require the student's real project/accounts/device.
