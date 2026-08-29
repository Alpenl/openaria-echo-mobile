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

That workflow has only `actions: read` and `contents: read`, and it shares the
literal `openaria-mobile-release-publication` concurrency group with the
publication workflow. It binds the exact attempt-specific jobs and step order:
the ownership receipt, staged upgrade and its evidence, and publication must
have succeeded before the original post-publish verification failed. The
source and triggering actors must still be the repository owner, and the
receipt's raw immutable-release preflight, default-branch head, source, tag,
and dispatch time are revalidated.

The supplement selects the unique ownership artifact and downloads it by its
numeric artifact ID into an exact one-file root. It anonymously downloads the
closed four-asset public set through a per-byte hard ceiling taken from the
validated API state. Each download runs in a credential-stripped isolated
process. The parent applies the total wall-clock deadline to process startup,
DNS, TCP/TLS, redirects, response headers, body transfer, termination, and
reaping; the worker also applies connect and body deadlines while reading at
most one socket payload at a time. On timeout the parent kills and reaps the
worker and removes its partial file, so a slow-drip response cannot keep a
resolver, thread, or connection alive. A partial file must match the exact state
size and SHA-256 before an atomic rename makes it available to any APK or AAB
tool. The verifier job also has a 30-minute hard timeout. It checks bytes, manifest, checksums,
package/version, and APK identity, and verifies the AAB with a pinned
certificate in an ephemeral one-entry truststore plus strict full-entry JAR
verification. Before `ZipFile` can materialize the central directory, a bounded
EOCD/ZIP64 preflight enforces a 256 MiB archive limit, a 16 MiB central-directory
limit, at most 4096 declared and actually parsed entries, exact offsets, and an
unambiguous single-disk structure. Before any ZIP payload is decompressed, each
entry is then limited to 64 MiB, the total uncompressed size to 256 MiB, and the
per-entry compression ratio to 100x. Duplicate ZIP entries and every extra JAR
signature-control entry are rejected using the JDK's case-insensitive
`META-INF`, extension, and `SIG-*` semantics, including empty basenames such as
`META-INF/.SF` and `META-INF/SIG-`. Keytool and jarsigner calls have bounded
timeouts and the temporary truststore is cleaned on timeout or failure. After
all downloads and signature checks, it refetches the source
run, attempt jobs, numeric artifact metadata and digest, latest Release,
Release by ID, Release by tag, and tag ref. The v2 evidence is written only if
that final state exactly matches the initial state. Its only successful output
is an auditable Actions artifact; it has no Release, tag, workflow, or
repository mutation path.

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
