# PayPilot

PayPilot helps small businesses get paid: it tracks every invoice from "sent" to "paid", prepares
reminders and replies with AI, and keeps the compliance documents clients ask for up to date.
Nothing is ever sent without the owner's approval.

One Kotlin codebase builds the **Android app (APK)**, the **Windows app (MSI/EXE installer)** and
the **iPhone app**, plus a **server** that holds the AI key and the financial connections.

## Modules

| Module | What it is |
|---|---|
| `core` | Shared logic (Android, iOS, desktop, server): invoice model with the French e-invoicing statuses, cash summary, reminder rules and late-fee calculation, TOTP, password policy, breached-password check, PIN hashing, app-lock policy. |
| `server` | Ktor API: account creation and sign-in, 2-step verification, sessions, AI drafting with Claude, financial connector layer. |
| `composeApp` | Compose Multiplatform app for Android, Windows/macOS/Linux and iOS. |
| `iosApp` | Thin iOS wrapper (generated with XcodeGen) around `composeApp`. |

## Security

- **Passwords**: Argon2id on the server (OWASP parameters). Rules follow NIST SP 800-63B: at least 12 characters, passphrases welcome, common, personal and breached passwords refused (Have I Been Pwned k-anonymity, only 5 hash characters leave the server).
- **Brute force**: 5 wrong passwords lock the account for 15 minutes; sign-in routes are rate-limited; login timing does not reveal whether an e-mail exists.
- **2-step verification**: TOTP (Google/Microsoft Authenticator, 1Password...), replay-protected, with 8 one-time recovery codes.
- **Sessions**: 15-minute access tokens; 30-day refresh tokens stored hashed and rotated on every use; "sign out all devices".
- **On the device**: 6-digit PIN (PBKDF2, 60 000 iterations) plus fingerprint/face on Android; auto-lock after 2 minutes; growing wait after wrong PINs and full wipe after 10. Android: encrypted storage (Keystore), screenshots blocked, backups disabled, HTTPS only.
- **AI**: the Claude API key lives only on the server. Client messages are passed to the model as data, never as instructions. Drafts are always shown for review; the owner sends from their own phone or e-mail.
- **Secrets**: none in the code. The server reads `PAYPILOT_JWT_SECRET` and `ANTHROPIC_API_KEY` from the environment.

## Financial platforms

Every platform plugs in through `FinancialConnector` (`server/.../connectors`). Available now:
**Stripe** (read-only restricted key). Listed as coming next: approved e-invoicing platforms
(lifecycle statuses déposée / refusée / encaissée), Chorus Pro, open banking (needs a licensed
aggregator contract), Pennylane.

## Build

CI (`.github/workflows/build.yml`) produces, on every push to `main`:
- `PayPilot-android-apk` – debug-signed APK to install on Android
- `PayPilot-windows` – `.msi` and `.exe` installers
- `PayPilot-ios-simulator` – app for the iOS simulator (a real iPhone needs an Apple Developer account and signing)

Locally:

```bash
./gradlew :core:jvmTest :server:test          # tests
./gradlew :composeApp:run                      # desktop app
./gradlew :composeApp:assembleDebug            # Android APK
./gradlew :composeApp:packageMsi               # Windows installer (on Windows)
PAYPILOT_JWT_SECRET=$(openssl rand -base64 48) ANTHROPIC_API_KEY=... ./gradlew :server:run
```

The app has a **demo mode** (sample data, offline templates) that works without a server.

## Status (v0.1) and what comes next

Not production-ready yet. Known gaps, in order:
1. Server storage is in memory: add PostgreSQL (users, invoices, sync) and deploy in the EU.
2. Business data stays on each device; sync through the server comes with the database.
3. Windows/iOS keep the session in memory only: add Windows Credential Manager and iOS Keychain, plus Face ID.
4. Release signing: Android upload key, Windows code-signing certificate, Apple Developer account.
5. Passkeys, voice/photo capture, e-invoicing platform and bank connectors.
6. External security review (penetration test) before handling real customer data.
