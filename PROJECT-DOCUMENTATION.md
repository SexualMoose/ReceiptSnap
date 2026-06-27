# ReceiptSnap

Android app that scans multiple paper receipts in a single camera shot (or a folder of images), crops/deskews each one, OCRs it, builds a compact searchable PDF, and emails each receipt to a Coupa expense-ingest address over SMTP.

## Overview / Purpose
ReceiptSnap is a personal expense-automation tool for submitting receipts to Coupa (a corporate spend-management platform). It detects one or more receipts in a photo, isolates each, extracts metadata (date, amount, vendor/address, meal detection) via on-device OCR, generates a small grayscale PDF per receipt, and sends it by SMTP to the user's Coupa "wallet" ingest address. Coupa attributes the receipt to the employee based on a derived ingest address that encodes the sender's corporate email. A library/gallery screen tracks which receipts have been sent, and an append-only send log records recipient, sender, SMTP host, timestamp, and the RFC 5322 Message-ID for each successful send (intended for "prove you submitted by the deadline" audits).

## Status
Working / actively iterated WIP. Evidence:
- 24 commits from 2026-04-23 to 2026-06-25 (last commit "2 days ago" as of audit), steady feature progression from initial scanner to multi-account SMTP, failover, folder uploads, and audit hardening.
- versionCode 2 / versionName "1.1"; commit history references a TestFlight-style release-key rotation ("migrate to fleet release key").
- Known caveat from the author: commit a6d73f9 notes a "pre-existing local build-env issue (dep resolution fails before compile)" that was not build-verified that session. There are no automated tests.

## Technical Requirements
- Language: Kotlin 2.1.0 (Jetpack Compose UI).
- Build tool: Gradle 8.11.1 (wrapper committed), Android Gradle Plugin 8.7.3.
- JDK: Java 17 (source/target compatibility 17, jvmTarget 17).
- Android: compileSdk 35, targetSdk 35, minSdk 29 (Android 10). ABI restricted to arm64-v8a (OpenCV native libs).
- Hardware: a physical Android device with a camera (the app uses CameraX, ML Kit OCR, and OpenCV; emulators with no camera will not exercise the core flow).
- Accounts/keys needed at runtime: a working outbound SMTP account (Gmail with app password, Office 365 with app password, etc.) and the user's corporate email + Coupa instance host to derive the ingest address. No API keys are compiled into the app.
- App signing: no signing config exists in the repo; release signing is handled externally (CI/keystore not in tree).

## Dependencies (key libs + licenses)
- AndroidX Core KTX, Lifecycle, Activity Compose, Compose BOM 2024.12.01, Material3, Navigation Compose, ExifInterface, DocumentFile — Apache-2.0.
- Jetpack Compose UI / Material Icons Extended — Apache-2.0.
- CameraX (core, camera2, lifecycle, view, extensions) 1.4.1 — Apache-2.0.
- Google ML Kit text-recognition 16.0.1 — Google ML Kit Terms / Android Software Development Kit License (proprietary Google license, free to use).
- OpenCV org.opencv:opencv 4.13.0 — Apache-2.0 (OpenCV 4.5.0+ is Apache-2.0).
- Accompanist permissions 0.36.0 — Apache-2.0.
- kotlinx-coroutines-android 1.9.0 — Apache-2.0.
- Coil compose 2.7.0 — Apache-2.0.
- JavaMail / Jakarta Mail for Android: com.sun.mail:android-mail and android-activation 1.6.7 — CDDL-1.0 / GPL-2.0-with-Classpath-Exception (dual). Used under the Classpath Exception; fine for app distribution.
- Declared-but-unused catalog entries: com.google.android.gms:play-services-location 21.3.0 and a documentScanner version (16.0.0-beta1) appear in gradle/libs.versions.toml but are not referenced in app/build.gradle.kts dependencies.

## Setup Instructions
1. Install JDK 17 and Android Studio (Ladybug or newer, AGP 8.7.3 compatible) with Android SDK platform 35.
2. Clone the repo:
   `git clone https://github.com/SexualMoose/ReceiptSnap.git`
3. Create a `local.properties` in the repo root pointing to your SDK (Android Studio creates this automatically), e.g. `sdk.dir=/Users/you/Library/Android/sdk`. (This file is gitignored.)
4. Open the project in Android Studio and let Gradle sync, or build from CLI (below). Note: the author flagged a dependency-resolution issue in their local build env; if Gradle sync fails on dep resolution, verify network access to Google's Maven and Maven Central and that OpenCV 4.13.0 is resolvable.

## Build & Run
- Debug build:  `./gradlew assembleDebug`
- Install on a connected device:  `./gradlew installDebug`
- Release build (unsigned unless a signing config is supplied externally):  `./gradlew assembleRelease`
- Run: launch the "ReceiptSnap" app on the device; grant Camera and (on first folder upload) media permissions.
- First-run config: open Settings, enter your corporate email and Coupa instance host (to derive the ingest address), and add at least one SMTP account (email + app password + host/port; defaults to smtp.office365.com:587).

## Usage
- Capture: point the camera at one or more receipts laid out in frame and shoot; the app bursts frames, picks the sharpest, detects each receipt cluster, crops/deskews, and OCRs.
- Review: the Review screen shows detected receipts with parsed date/amount/vendor and a quality badge.
- Library: browse captured receipts, multi-select, delete, view single items, and see a "Sent" marker for items already submitted.
- Send: send a receipt (or batch) to the derived Coupa ingest address via the active SMTP account; on rate-limit failures the app can auto-failover to the next saved SMTP account.
- Folder upload: point the app at a folder of images to run the full detect/crop/route pipeline and either send to Coupa or sort into Pictures/Receipts Sorted/yyyy-MM/.

## Architecture (components + data flow)
- UI (Jetpack Compose): MainActivity, MainViewModel, CameraScreen, ReviewScreen, LibraryScreen, SettingsScreen, theme.
- Camera: CameraController + CameraScreen (CameraX; multi-cam/telephoto capture, burst-and-pick primary frame).
- Processing pipeline: DocumentDetector (OpenCV contour/cluster detection + ML Kit OCR seed-grow), AddressParser (vendor city/address scoring), ReceiptParser (date/amount/meal extraction), PdfMaker (hand-rolled compact grayscale JPEG-in-PDF), EmailContent (subject/body + filename metadata), FolderUploadProcessor, CoupaUploadsFolder, ReceiptsSortedFolder.
- Delivery: SmtpSender (JavaMail; persistent authenticated connection per worker, STARTTLS-required TLS1.2/1.3, SMTPS for 465, friendly error mapping for 535/545).
- Data/state: SettingsStore (Coupa identity + multi SMTP account list, active-account selection, failover flag, legacy-pref migration; SharedPreferences), SmtpAccount (model), SentTracker (sent markers), SendLog (append-only audit log), ReceiptStorage (image/file storage).
- Data flow: camera/folder image -> DocumentDetector crops -> ReceiptParser/AddressParser metadata -> PdfMaker PDF -> SmtpSender to Coupa ingest address (derived by SettingsStore.deriveCoupaAddress) -> SentTracker + SendLog updated. PDFs/crops are written to the app cache (coupa_pdfs/) and deleted after each send pass.

## Integrations & Interconnects
- Coupa (coupahost.com / coupa-expenses.com): receipts are emailed to a derived Coupa expense-ingest address; the app encodes the user's corporate email into the ingest local-part so Coupa attributes the receipt. ReceiptSnap does not call any Coupa API — integration is purely the email ingest channel.
- SMTP providers: Office 365 (smtp.office365.com:587 default) and Gmail; uses app passwords; multi-account with auto-failover on send-limit errors.
- Google ML Kit (on-device text recognition) and OpenCV (on-device image processing) — both run locally, no network calls.
- The release-key rotation commit references the user's "fleet" release-key tooling (external CI/signing), but no such config is committed here.

## Configuration & Secrets
- Configured at runtime via the Settings screen; nothing is compiled in.
- SMTP credentials (email + password/app password + host + port) are stored as a JSON array in SharedPreferences file `receipt_snap_settings.xml` in PLAINTEXT (MODE_PRIVATE, not encrypted). This pref is excluded from cloud backup and device-to-device transfer via data_extraction_rules.xml.
- Send log (recipient/sender emails, SMTP host, message IDs) is stored in SharedPreferences file `receipt_snap_send_log.xml`.
- Earlier versions hardcoded the developer's personal employer email (Tyler.Keller@psabdp.com) and Coupa host (bdpinternational.coupahost.com) as defaults; these were removed in commit a6d73f9 but remain in git history and still appear as a code comment and a Settings UI placeholder string.
- Never commit real SMTP passwords, app passwords, or a keystore; none are present in the repo.

## Testing
- No automated tests exist (no src/test or src/androidTest, no test dependencies). Verification is manual on-device.

## Known Issues / TODO
- data_extraction_rules.xml backup-exclusion path mismatch: it excludes `send_log.xml` but the actual pref file is `receipt_snap_send_log.xml`, so the send log (which contains recipient/sender email PII) is NOT actually excluded from cloud backup. The settings exclusion path is correct.
- Author-noted local build-env dependency-resolution failure (not build-verified in the audit-hardening commit); release builds may need network/dep-source fixes.
- No README or LICENSE file in the repo.
- Dead catalog entries (play-services-location, documentScanner) declared but unused.
- SMTP passwords stored in plaintext SharedPreferences rather than EncryptedSharedPreferences / Android Keystore.

## Third-party & Licensing notes
- No LICENSE file present; the project is "all rights reserved" by default (acceptable for a personal tool, but undefined for redistribution).
- JavaMail (com.sun.mail:android-mail/android-activation) is CDDL-1.0 / GPL-2.0-with-Classpath-Exception — fine to bundle in a closed app under the Classpath Exception; redistribution should preserve its notices.
- ML Kit ships under Google's proprietary SDK terms (not OSS) — fine for use, not for re-licensing.
- No vendored/copied third-party source code detected; all third-party code is pulled via Gradle.
- Trademark sensitivity: "Coupa" (and coupahost.com / coupa-expenses.com), "Office 365"/Microsoft, and "Gmail"/Google brand names are referenced in code, comments, and UI. The app name and package id (com.tyler.receiptsnap) do not impersonate any brand. This is interoperability use, but the developer's real corporate email/employer (psabdp.com / BDP International) is embedded in history/comments and should be scrubbed before any public release.

## Security notes
- HIGH: Earlier hardcoded personal/employer identity (Tyler.Keller@psabdp.com, bdpinternational.coupahost.com) still lives in git history and lingers as a comment + Settings placeholder. Scrub from working tree and consider history rewrite if the repo is ever made public.
- MEDIUM: SMTP passwords/app passwords stored in plaintext SharedPreferences (receipt_snap_settings.xml). Migrate to EncryptedSharedPreferences or the Android Keystore.
- MEDIUM/PRIVACY: send-log backup-exclusion path is wrong (send_log.xml vs receipt_snap_send_log.xml), so recipient/sender email PII can be backed up to the cloud. Fix the path to receipt_snap_send_log.xml.
- LOW: SMTP error strings instruct the user to create provider app passwords (no secret leakage); no credentials are logged.
- Positives: TLS is enforced (STARTTLS required, TLS 1.2/1.3, SMTPS for 465); no WebView/JS bridge; no SQL; no exec/eval; only MainActivity exported; FileProvider not exported and scoped to a cache subdir; allowBackup=false; no secret files in tree or history; no overly-broad permissions (Camera, READ_MEDIA_IMAGES, Internet, pre-Q WRITE_EXTERNAL_STORAGE).
