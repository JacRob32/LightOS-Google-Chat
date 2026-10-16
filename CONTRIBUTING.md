# Contributing to Light Chat

This is a new project. There is a lot to build, and collaboration and support
are encouraged. Bug reports, testing on real devices, documentation fixes, and
code are all useful. You do not need to be an expert in Android or Light Phone
development to help.

## Before you start

- Read the [README](README.md) for the build steps and the current scope.
- For anything larger than a small fix, open an issue first so we can agree on
  the approach.
- Never include real credentials, refresh tokens, client secrets, or personal
  account data in an issue, pull request, log, or screenshot.

## Reporting an issue

Use a short, specific title. Include:

- What you expected to happen and what happened instead.
- Steps to reproduce, numbered.
- Phone model, LightOS version, and the Light Chat version or commit.
- Relevant log output, with any tokens or emails removed.

One issue should cover one problem. If you find several, open several issues.

## Opening a pull request

1. Fork the repository and create a branch from `main`, for example
   `fix/thread-ordering` or `docs/setup-steps`.
2. Keep the change focused. Avoid unrelated formatting or refactoring in the same
   pull request.
3. Run the checks in the README before you push:

   ```sh
   ./gradlew :host-tests:testDebugUnitTest :tool:testDebugUnitTest :tool:verifyGmsFreeRuntime :tool:lintDebug :tool:assembleDebug :tool:assembleRelease
   ```

4. Add or update tests for behavior you change.
5. Update the README or setup guides if the change affects them.
6. Open the pull request with:
   - A title that says what changed.
   - A description of the problem, the change, and how you tested it.
   - The linked issue, if there is one (for example `Closes #12`).
   - Screenshots or a short recording for UI changes.

Commit messages should be short in the first line and explain the reason in the
body when the reason is not obvious.

## Code guidelines

- Kotlin, Jetpack Compose, MVVM, and Coroutines/Flow, following the existing
  structure.
- No Firebase or Google Play Services dependencies. The GMS-free check must pass.
- Keep the monochrome, low-motion Light Phone style. Do not add decorative
  animation.
- Comment only where the code needs clarification.

## Questions

If something here is unclear, open an issue with the label or title "question".
