# Owner evaluation: desktop Qt preview, not a release

Push the integration branch under the name `qt/desktop-preview` to trigger
**Windscribe Qt UI integration (experimental)** on GitHub Actions. No release
or public distribution approval is implied. If the repository's Actions or
branch settings block that push event, have an administrator enable it. Do not
place credentials or signing material in the branch.

The matrix builds Windows x64, Linux x64, and both Intel and Apple Silicon
macOS bundles. Each job:

1. Verifies the published 1.19.0 desktop archive against its pinned SHA-256,
   then extracts only `gozar-core` (`gozar-core.exe` on Windows).
2. Builds Qt and runs the offline UI fixture plus signed-out official-core
   checks. No account credentials or live VPN connection are used.
3. Captures screenshots from the actual Qt executable with simulated server
   and traffic data in English and Persian, and uploads them as a distinct
   artifact. No fixture core is included in the development package.
4. Stages the unchanged Flutter sign-in sources and Qt candidate for owner
   evaluation. The macOS bundle is ad-hoc signed during packaging, so its
   executable signatures need not be byte-for-byte identical to the source
   archive. The Go sources are never modified or rebuilt by this workflow.

GitHub artifact names: `gozar-qt-screenshots-*` and
`gozar-qt-real-core-*-EXPERIMENTAL-NOT-RELEASE`. The local test results do not
establish Windows/macOS runtime behavior, independent tunnel cleanup, or legal
approval. See `EXPERIMENTAL-WARNING.txt` and `RELEASE-LICENSE-REVIEW.md`.
