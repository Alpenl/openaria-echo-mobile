# Android release procedure

Android releases are an irreversible publication. GitHub immutable Releases
cannot be withdrawn or repaired after publication, so the workflow does all
build, signer, byte-identity, and real in-app upgrade acceptance before the
numeric Release ID is made public.

The repository `GITHUB_TOKEN` intentionally has no Administration permission
and cannot prove that immutable Releases are currently enabled. The repository
owner must use an admin-capable local `gh` login and dispatch through the
versioned operator command:

```bash
scripts/dispatch-android-release.sh <40-character-source-commit> <vX.Y.Z>
```

The command performs the official
`GET /repos/Alpenl/openaria-echo-mobile/immutable-releases` request, records the
exact raw response and SHA-256, binds it to the owner, repository, source
commit, current default branch and its exact HEAD, and tag, then immediately
creates a fresh `workflow_dispatch` run. Production releases may only use the
protected default-branch HEAD observed at dispatch. The workflow independently
rechecks that HEAD and requires its own `GITHUB_REF` and `GITHUB_SHA` to identify
the same branch and commit. A branch update during dispatch fails closed and
requires a fresh preflight. The workflow compares the GET time with the Actions
run `created_at`; it does not use job start time as evidence of an immediate
dispatch.

The local admin credential stays in the operator's existing `gh` credential
store. It is never placed in a repository secret, workflow input, log message,
receipt, or artifact; only the non-secret setting response and its digest are
recorded.

Do not rerun a failed release run. Actions reruns retain the original inputs,
so the workflow rejects every `GITHUB_RUN_ATTEMPT` other than `1`. Run the local
command again to obtain a fresh admin preflight. If a never-public draft already
uses the tag, the workflow does not adopt, edit, delete, or overwrite it. Inspect
that draft manually, resolve it, then use a fresh tag when required and dispatch
again with a new preflight. A tag or Release that was ever public always
requires a strictly higher SemVer and Android `versionCode`.

The external setting check and GitHub's eventual publication are not one atomic
API operation. The five-minute dispatch binding, single global release
concurrency group, exact ownership receipt, pre-publication state closure, and
immutable postcheck reduce this residual race, but they cannot make the two
GitHub operations atomic.

If publication succeeds but the bounded post-publication read check fails, do
not rerun the release workflow and do not edit, delete, or re-upload the public
Release. After a verifier fix reaches the protected default branch, dispatch
the separate read-only supplement with the original run, tag, and commit:

```bash
gh workflow run mobile-release-readonly-postpublish.yml \
  -f source_run_id=<original-mobile-release-run-id> \
  -f release_tag=<vX.Y.Z> \
  -f source_commit=<40-character-published-commit>
```

That workflow has only `actions: read` and `contents: read`. It downloads the
exact source-run ownership receipt, binds its numeric Release ID, anonymously
downloads the closed four-asset public set, and verifies latest/immutable/tag,
checksums, package/version, and APK/AAB signer identity. Its only output is an
auditable Actions artifact; it has no Release, tag, workflow, or repository
mutation path.

The mutable v0.1.6 migration is disabled by default. Its only authorized use is
the exact v0.1.7, versionCode 10 release from the frozen v0.1.6 Release ID, tag
commit, and four-asset digest closure. For that one migration, use:

```bash
scripts/dispatch-android-release.sh <v0.1.7-source-commit> v0.1.7 --allow-legacy-baseline-bootstrap
```

The flag is bound into the local admin preflight, ownership receipt, staged
upgrade evidence, publication recheck, and post-publication evidence. It is
rejected once an immutable Release is latest and cannot authorize any later
version.

The release matrix runs the current Android `testDebugUnitTest` suite only.
Frozen safe-swap wire/parser checks are kept in the manual `:app:testFrozenCompatibility`
task for source compatibility; CI and release workflows do not invoke that task,
and it is never release acceptance evidence.
