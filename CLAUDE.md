# CLAUDE.md

## Architecture map and decisions

- `docs/ARCHITECTURE.md` maps the application: packages, data flow, head unit flags, invariants, and a symptom-to-code guide.
  Read it before exploring the code; it often answers the question directly.
- `docs/adr/` explains why the implementation works this way. Before changing an approach such as passthrough/transcoding,
  heartbeat, chunking, or the server on port 5277, read the relevant ADR.
- Record non-obvious decisions in a new ADR in the same PR. Examples include choosing between approaches, rejecting an
  option after a vehicle test, or introducing an invariant. Follow `docs/adr/README.md`. Use the reason discussed with
  Yegor; if it is unknown, say so explicitly.
- If the structure changes, such as a new package, head unit flag, or data path, update ARCHITECTURE.md in the same PR.

## Ticket workflow

### Ticket isolation
- Each session owns exactly ONE ticket (hNNN). At session start, state the ticket ID, the reporter's name/handle and their head unit. Re-confirm it before any message, build or log analysis.
- Never message, build for, or act on another ticket's user (e.g. h319 vs h295) unless explicitly told to.
- If the user says stop or do nothing, stop all actions immediately.

### Research before building
- Default for a new ticket: collect ALL logs, photos and videos, then analyze and report a diagnosis.
- Do NOT build test APKs (hNNNtestN) or start implementing until the user explicitly says to build.
- Before adding a new video mode or fix, check existing branches, commits and open PRs for overlap (`git log --all --oneline | grep -i <keyword>`, `gh pr list`).

## How changes reach main

These are the upstream workflow conventions for github.com/YegorkaPomidorka/headswap, owned by Yegor:

1. **Worktree.** Make changes in a separate worktree with its own `claude/...` branch. Other sessions work in the main
   checkout concurrently; do not commit there.
2. **Branch.** Commit to the worktree branch. Use `git add` with explicit paths, without `-a`. End the commit message
   with a `Co-Authored-By` line.
3. **Push.** Use the personal gh account rather than GCM, which selects the work organization:
   `git -c credential.helper= -c "credential.helper=!gh auth git-credential" push -u origin <branch>`
   If main has advanced, first update the branch from main (`sync_with_base_branch` in Claude Desktop) and run the tests.
4. **PR** targeting `main`: `gh pr create --repo YegorkaPomidorka/headswap --base main --head <branch>`.
5. **Merge.** When Yegor says to push the change through or merge it, merge immediately without waiting for a review;
   that instruction is his authorization:
   `gh pr merge <number> --repo YegorkaPomidorka/headswap --merge`
6. **After merging**, GitHub main is updated. The session using the main checkout will update its local `main`;
   do not modify it from the worktree.

Increment `versionCode`/`versionName` in `android/app/build.gradle.kts` only when releasing, in a separate commit
called “Version X.Y.Z (versionCode N)”. Test builds such as `-cx5test1` use temporary version edits that are not committed.

Publish releases to GitHub Releases as well as the website; the application updates from there (`update/UpdateLogic.kt`).
Use tag `vX.Y.Z` and file `headswap-X.Y.Z.apk`, matching the website filename. The app displays the release notes before installation:
`gh release create vX.Y.Z headswap-X.Y.Z.apk --repo YegorkaPomidorka/headswap --title "Headswap X.Y.Z" --notes-file <release-notes>`
Only publish test builds with `--prerelease`; GitHub will not treat them as the latest stable release, and the app will not offer them.

## Release

### Release checklist
- Certificates must be in app assets at `assets/aa/hur/` (NOT `assets/hur/`).
- After building the signed APK, verify it before deploying: `unzip -l app.apk | grep assets/aa/hur` must list the cert files.
- Before creating a GitHub release, run `gh release list` and check for drafts. If the release exists, publish it rather than create a new one.

## Working conventions

### Design & scope
- For UI features, discuss the design first. Then show a real screen mockup in the Claude Design canvas, not an inline widget, before writing code.
- Car-brand fixes (e.g. Honda cluster) apply to all models and channels of that brand unless told otherwise.
- 'Install on phone' means adb install, not sending the APK via Telegram.
- Take the user's hypotheses seriously and test them before arguing against them.
