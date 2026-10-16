# Agent instructions for Light Chat

Guidance for AI coding agents and automated tools working in this repository.
Human contributors should read [CONTRIBUTING.md](CONTRIBUTING.md) instead.

## Project

Light Chat is a Google Workspace Chat client for Light Phone III (LightOS). It is
written in Kotlin with Jetpack Compose, MVVM, Coroutines/Flow, Ktor (OkHttp), and
Room, built on the Light SDK scaffold.

## Hard constraints

- Kotlin only, Jetpack Compose for UI, MVVM with Coroutines/Flow.
- No Firebase, Google Play Services, or GMS libraries. `:tool:verifyGmsFreeRuntime`
  enforces this for debug and release.
- Network access goes through Ktor to Google Workspace Chat and OpenID endpoints
  only.
- UI is a 1080x1240 monochrome layout: white background, black text, no decorative
  animation.
- Do not commit credentials, tokens, client secrets, keystores, or
  `local.properties`.

## Repository layout

- `tool/`: the application module (UI, ViewModels, repositories, Ktor API, Room
  database, encrypted credential storage).
- `host-tests/`: host-only test module that compiles the production data sources
  for Room and repository tests under Robolectric. It also holds the on-device
  `androidTest` for the Android Keystore store.
- `light-sdk-reference/`: pinned upstream SDK checkout, gitignored. It is not part
  of this repository's history. Restore it at commit
  `69216b3cd2e6a45abab63268c4d08800a5aa33da` before building. The build reads the
  dev signing key from it.
- `.github/workflows/build-apk.yml`: CI. It runs tests, lint, and builds the APK.
  A `v*` tag publishes a GitHub release with the APK.
- `Google-Cloud-Setup.md`, `Light-Phone-Setup.md`: user-facing setup guides.

## Commands

Run the full verification from the repository root:

```sh
./gradlew :host-tests:testDebugUnitTest :tool:testDebugUnitTest :tool:verifyGmsFreeRuntime :tool:lintDebug :tool:assembleDebug :tool:assembleRelease
```

On-device Keystore tests need a running emulator or device:

```sh
./gradlew :host-tests:connectedDebugAndroidTest
```

The repository lives in an iCloud-synced folder on the maintainer's machine. Sync
can create `* 2.class` copies and hang Gradle. Build output outside the synced
tree:

```sh
./gradlew -Plightchat.buildRoot="$HOME/.cache/lightchat-build" \
  --project-cache-dir "$HOME/.cache/lightchat-build/.gradle" :tool:assembleDebug
```

Local builds need JDK 17 and an Android SDK with platform 36 and build-tools
36.0.0, set through `ANDROID_HOME` or `sdk.dir` in `local.properties`.

## Writing style

- Do not use em dashes or en dashes in code comments, documentation, commit
  messages, pull request text, or issue text. Use commas, colons, or separate
  sentences.
- Avoid generic AI-sounding phrasing such as "seamless", "robust", "delve", or
  "leverage". Write plainly and specifically.
- Comment code only where it needs clarification.
- Use Markdown links for files and symbols when referring to them in prose.

## Git workflow

- Make a focused change, run the relevant checks, and make one detailed commit per
  coding run. The commit message has a short summary line and a body that explains
  the reason for the change.
- Include the trailer `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`
  unless the user asks you not to.
- Push when the user asks for it. Pushing to `main` triggers the APK workflow.
- Release tags use `v<versionName>`, for example `v1.0.0`. The version comes from
  `tool/lighttool.toml`.

## Security-sensitive areas

- `tool/src/main/kotlin/.../security/EncryptedCredentialStore.kt` stores sign-in
  data encrypted with a non-exportable Keystore key. Do not log, print, or persist
  plaintext credentials. Do not weaken the redaction in `Credentials.kt`.
- Never log request or response bodies from the chat or OAuth endpoints.
