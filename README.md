# Light Chat

A Google Workspace Chat client for Light Phone III, written in Kotlin with
Jetpack Compose, MVVM, Coroutines/Flow, Ktor (OkHttp), and Room. No Firebase or
Google Play Services dependencies are used for networking or notifications.

## Build

Requirements: JDK 17, Android SDK platform 36/build-tools 36.0.0, and an Android
34+ device. Set `ANDROID_HOME` or `sdk.dir` in your local, ignored
`local.properties`.

The upstream scaffold, examples, documentation, and SDK implementation live
entirely in the **ignored** `light-sdk-reference/` checkout. They are not part of
this application's git history. From a fresh application checkout, restore the
pinned reference before building:

```sh
git clone https://github.com/lightphone/light-sdk.git light-sdk-reference
git -C light-sdk-reference checkout 69216b3cd2e6a45abab63268c4d08800a5aa33da
./gradlew :host-tests:testDebugUnitTest :tool:testDebugUnitTest :tool:verifyGmsFreeRuntime :tool:lintDebug :tool:assembleDebug :tool:assembleRelease
```

The application and HTTP/presentation tests are in `tool/`. Room repository
tests are in `host-tests/`, a host-only module compiling the same production data
sources. The SDK sandbox disallows Robolectric/framework access even in a tool's
test sources, so this separate test harness keeps those dependencies out of the
tool without changing or bypassing the SDK's production policy. The Gradle wrapper,
version catalog, root build configuration, and development signing configuration
come from the official scaffold. Only the SDK modules needed by this app are
included in the build. The upstream SDK license is preserved in `LICENSE`.

The debug APK is `tool/build/outputs/apk/debug/tool-debug.apk`. The upstream
development signing certificate is for sideload testing, not production signing.
Follow Light's distribution/signing process before distributing a release.

**Cloud-synced folders:** if this checkout lives in iCloud Drive (e.g. the macOS
Desktop), sync can create `* 2.class` conflict copies in `build/` folders and
stall Gradle's file reads so the build hangs. Keep build output elsewhere:

```sh
./gradlew -Plightchat.buildRoot="$HOME/.cache/lightchat-build" \
  --project-cache-dir "$HOME/.cache/lightchat-build/.gradle" :tool:assembleDebug
```

With that option the APK is under `$HOME/.cache/lightchat-build/tool/outputs/`.

The SDK's optional QR scanner dependencies (ML Kit and CameraX's ML Kit adapter)
are excluded from this tool, removing their transitive Firebase/Play Services
libraries. Chat does not call the scanner. `verifyGmsFreeRuntime` enforces this
for both debug and release; a release R8 rule also fails the build if SDK scanner
code becomes reachable. The ignored SDK source itself is not modified.

## Connect a Workspace account

The app has no built-in Google Sign-In (there is no Play Services, and the SDK
sandbox restricts browser launches). Instead you provide credentials obtained
through a trusted OAuth flow on another computer, and the app **remembers them
across app restarts**, encrypted on the phone. A real browser sign-in inside the
app would need a separately designed external authorization integration.

1. Enable the Google Chat API in a Google Cloud project. Configure the Chat app
   and OAuth consent screen and add your user as a test user if applicable.
   Workspace administrators may also need to approve the application/scopes.
2. Through an OAuth authorization-code flow on a separate computer, obtain
   credentials with these scopes:
   - `openid`
   - `email`
   - `https://www.googleapis.com/auth/chat.spaces.readonly`
   - `https://www.googleapis.com/auth/chat.messages`
3. Choose **Connect** or **Account → Sign in** and enter **one** of:
   - **Lasting sign-in (recommended):** `client_id client_secret refresh_token`
     separated by spaces (the client secret may be omitted only if your OAuth
     client type has none, in which case use the JSON form), or the standard
     "authorized user" JSON (`client_id`, `client_secret`, `refresh_token`). The
     app exchanges the refresh token for short-lived access tokens
     automatically, so you stay signed in until you sign out or Google revokes
     the grant.
   - **Access token only:** a single access token. It is remembered too, but
     Google expires it about an hour after issuing it; after that you must
     enter a new one.
4. Google OpenID userinfo verifies the account subject/email before anything is
   accepted or saved. The verified subject must match the locally cached
   account. Choose **Forget account** before switching users.
5. Open a conversation to download its messages. Refresh is explicit. An
   expired sign-in never removes cached messages.

**How credentials are stored:** they are serialized, encrypted with AES-256-GCM
using a non-exportable key in the Android Keystore, and kept in the app's
private preferences (`EncryptedCredentialStore`). No plaintext token, client
secret, or refresh token is written to disk, logs, Room, or saved-instance
state; the on-device test checks the preferences file for plaintext. If the
Keystore key is invalidated or the data is unreadable or tampered with (for
example after a device restore), the saved sign-in is discarded and you sign in
again. With a refresh token the short-lived access token is kept only in memory
and renewed on first use. **Sign out** deletes the saved credentials but keeps
cached chats and unsent messages; **Forget account** deletes everything.
Treat a refresh token like a password: it grants lasting access until revoked
at <https://myaccount.google.com/permissions>. Do not put real credentials in
commits or issue reports. API access must be tested with your own authorized
Workspace account.

References: [user authentication](https://developers.google.com/workspace/chat/authenticate-authorize-chat-user),
[list spaces](https://developers.google.com/workspace/chat/api/reference/rest/v1/spaces/list),
[list messages](https://developers.google.com/workspace/chat/api/reference/rest/v1/spaces.messages/list),
[send messages](https://developers.google.com/workspace/chat/api/reference/rest/v1/spaces.messages/create).

## Behavior and architecture

- `ChatScreen` uses the SDK's `LightScreen`, `LightViewModel`, safe database
  factory, and embedded Light keyboard; it does not bypass the SDK sandbox.
- `ChatViewModel` exposes immutable `StateFlow` UI state and owns asynchronous
  operations. SDK Back navigates editor/account/thread/conversation states.
- `ChatRepository` exposes Room flows immediately, even without a token.
  Network snapshots are collected across **all pages** before a database
  transaction replaces a conversation's cached history. Failed/partial refreshes
  preserve the previous snapshot. A successful refresh reconciles edits and
  deletions. Conversations are fetched on demand, not all downloaded at startup.
- Room stores the verified account, spaces, messages with thread identifiers,
  last successful download times, and a persistent outbox. Chronological ordering
  uses parsed instants, including fractional seconds. Schema snapshots are
  exported into `tool/schemas/` for future migrations.
- Text messages and replies can be queued offline. Connected sends immediately
  attempt the outbox; otherwise choose **Retry outbox** explicitly. Stable
  client message IDs/request IDs prevent duplicate sends after uncertain network
  failures. Replies use `REPLY_MESSAGE_OR_FAIL`, never silently becoming new
  threads. Failed items remain visible with an error and can be discarded after
  confirmation. An inaccessible item stops the outbox until resolved/discarded.
- A fresh spaces snapshot marks removed spaces unavailable and removes their
  message cache. Unsent messages are retained for explicit discard. **Forget
  account** requires confirmation and clears the account, cache, outbox, and
  session credential; it does not delete anything on Google.
- The UI is white with black text/dividers only, no ripple/transition/loading
  animations, readable spacing, and at least 48dp text-action targets. It adapts
  to available space rather than treating physical pixels as dp. The Compose
  preview is 360×413dp, approximating **1080×1240px at 3× density**. Configure a
  1080×1240, API 34, non-GMS emulator for final hardware/layout testing.

## Privacy, boundaries, and validation

The application contacts only Google HTTPS Chat/OpenID endpoints. Ktor has
timeouts, does not follow redirects, and never logs request/response bodies or
credentials. The SDK disables recents screenshots by default. The cache is
ordinary app-private SQLite, **not encrypted storage**; device/OS backup policy
and device security still matter. Forgetting data is logical deletion, not a
forensic secure erase.

There is **no background polling or notification implementation** in this
version, hence no FCM dependency or claim of reliable background delivery. A
future push feature needs a Workspace Events/backend bridge to LightOS push
infrastructure. Room caching does not itself provide remote push notifications.

Supported: joined spaces/direct/group conversations, text history grouped by
thread, new text messages, replies, offline reading/outbox, and manual refresh.
Not implemented: creating spaces/DMs, attachments/cards/media rendering, search,
reactions, read receipts, message editing/deleting, or participant-name
resolution when Google returns only a user resource ID. Non-text messages are
explicitly identified rather than rendered as blank text.

Tests use a real Room database under Robolectric and a Ktor MockEngine. They
cover offline reopening, pagination and partial failure, reconciliation,
account isolation/forgetting, outbox retries, revoked spaces, token expiration,
saved sign-in restore after a restart, automatic access-token renewal, revoked
refresh tokens, sign-out, UTF-8 size limits, HTTP request shapes, thread
ordering, and monochrome colors. `EncryptedCredentialStoreTest` runs on a real
device/emulator (`./gradlew :host-tests:connectedDebugAndroidTest`) because it
needs the genuine Android Keystore.
Live OAuth/Workspace authorization and actual Light Phone behavior require an
authorized account and physical device/emulator; unit tests do not verify them.
