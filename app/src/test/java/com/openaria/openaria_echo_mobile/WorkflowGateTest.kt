package com.openaria.openaria_echo_mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowGateTest {
    private val releaseFile = File("../.github/workflows/mobile-release.yml")

    @Test
    fun `ci and release workflows run emulator UI tests instead of only compiling them`() {
        val ci = File("../.github/workflows/mobile-ci.yml").readText()
        val release = releaseFile.readText()

        listOf(ci, release).forEach { workflow ->
            assertContains(workflow, "assembleDebugAndroidTest")
            assertContains(workflow, "Enable KVM for Android emulator")
            assertContains(
                workflow,
                "ReactiveCircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d # v2.38.0",
            )
            assertContains(workflow, "api-level: 35")
            assertContains(workflow, "target: google_apis")
            assertContains(workflow, "arch: x86_64")
            assertContains(workflow, "profile: pixel_2")
            assertContains(workflow, "-gpu swiftshader")
            assertContains(workflow, "connectedDebugAndroidTest")
        }
    }

    @Test
    fun `third party workflow actions are pinned to immutable commits`() {
        fun actionReferences(contents: String): List<String> =
            contents.lineSequence()
                .map { it.trim().removePrefix("- ").trim() }
                .filter { it.startsWith("uses:") }
                .map { it.removePrefix("uses:").trim().substringBefore(" #").trim() }
                .toList()

        val workflows =
            listOf(
                File("../.github/workflows/mobile-ci.yml"),
                releaseFile,
            )

        assertTrue(
            actionReferences("- uses: example/action@v1") == listOf("example/action@v1"),
            "The pin scanner must cover compact '- uses:' YAML steps.",
        )
        workflows.forEach { workflow ->
            actionReferences(workflow.readText())
                .forEach { reference ->
                    if (!reference.startsWith("./")) {
                        assertTrue(
                            reference.substringAfterLast('@').matches(Regex("[0-9a-f]{40}")),
                            "${workflow.name} must pin $reference to a full commit SHA.",
                        )
                    }
                }
        }
    }

    @Test
    fun `gradle config does not keep the broken managed emulator task`() {
        val appBuild = File("build.gradle.kts").readText()

        assertFalse(
            appBuild.contains("managedDevices"),
            "AGP managed devices currently pass emulator -gpu auto-no-window, which emulator 37 rejects.",
        )
    }

    @Test
    fun `frozen safe-swap compatibility is absent from CI and release workflows`() {
        val appBuild = File("build.gradle.kts").readText()
        val ci = File("../.github/workflows/mobile-ci.yml").readText()
        val release = releaseFile.readText()
        val readme = File("../README.md").readText()
        val releaseGuide = File("../docs/ANDROID_RELEASE.md").readText()

        assertContains(appBuild, "testFrozenCompatibility")
        assertContains(appBuild, "includeFrozenCompatibility")
        assertContains(appBuild, "Manual-only frozen safe-swap parser/projection compatibility checks")
        assertFalse(ci.contains("frozen-safe-swap-compatibility"))
        assertFalse(ci.contains("testFrozenCompatibility"))
        assertFalse(release.contains("frozen-safe-swap-compatibility"))
        assertFalse(release.contains("testFrozenCompatibility"))
        assertContains(readme, "CI 和 release workflow 不调用它")
        assertContains(releaseGuide, "CI and release workflows do not invoke that task")
        assertContains(releaseGuide, "never release acceptance evidence")
    }

    @Test
    fun `release safety gate keeps dynamic local cleartext behind endpoint policy`() {
        val appBuild = File("build.gradle.kts").readText()
        val networkSecurityConfig = File("src/main/res/xml/network_security_config.xml").readText()

        assertContains(networkSecurityConfig, "cleartextTrafficPermitted=\"true\"")
        assertContains(networkSecurityConfig, "EndpointPolicy")
        assertContains(appBuild, "networkSecurityConfig")
        assertContains(appBuild, "Unexpected production network entry points bypass EndpointPolicy review")
        assertContains(appBuild, "DeviceHttpClient.kt")
        assertContains(appBuild, "DeviceProbeClient.kt")
        assertContains(appBuild, "AppUpdateManager.java")
        assertContains(appBuild, "Update manifest URL must be HTTPS.")
        assertContains(appBuild, "android.apk.url must be an HTTPS URL")
    }

    @Test
    fun `Android release proves version signer and exact published bytes`() {
        val release = releaseFile.readText()
        val appBuild = File("build.gradle.kts").readText()

        assertContains(appBuild, "versionCode = 10")
        assertContains(appBuild, "versionName = \"0.1.7\"")
        assertContains(release, "expected_tag=\"v\${version_name}\"")
        assertContains(release, "apksigner verify --verbose --print-certs")
        assertContains(release, "apkanalyzer manifest application-id")
        assertContains(release, "apkanalyzer manifest version-name")
        assertContains(release, "apkanalyzer manifest version-code")
        assertContains(release, "signingCertificateSha256")
        assertContains(release, "ANDROID_RELEASE_CERT_SHA256")
        assertContains(release, "protected release certificate")
        assertFalse(
            release.contains("print-certs \"\$apk_file\" | tee"),
            "Certificate identity must be compared without copying its raw digest into Actions logs.",
        )
        assertContains(release, "Previous release signing certificate")
        assertContains(release, "group: openaria-mobile-release-publication")
        assertContains(release, "Expected exactly four closed Android Release assets")
        assertContains(release, "name: android-release-\${{ env.RELEASE_TAG }}")
        assertFalse(release.contains("merge-multiple: true"))
        assertContains(release, "source_commit: \${{ steps.release_metadata.outputs.source_commit }}")
        assertContains(release, "ref: \${{ needs.android.outputs.source_commit }}")
        assertContains(release, "jarsigner -verify -verbose -certs")
        assertContains(release, "keytool -printcert -jarfile")
        assertContains(release, "jar verified.")
        assertContains(release, "aabSha256")
        assertContains(release, "aabBytes")
        assertTrue(
            "apkanalyzer manifest application-id".toRegex().findAll(release).count() >= 2,
            "APK package identity must be checked before and after publication.",
        )
        assertTrue(
            "apksigner verify --verbose --print-certs".toRegex().findAll(release).count() >= 3,
            "APK signature must be checked for candidate, baseline, and installed or published APK.",
        )
        assertFalse(release.contains("--clobber"))
        assertFalse(release.contains("macos-"))
        assertFalse(release.lowercase().contains("ios"))
    }

    @Test
    fun `release is dispatch only and binds a fresh external immutable setting proof`() {
        val release = releaseFile.readText()
        val trigger = release.substringBefore("permissions:")
        val metadata =
            release
                .substringAfter("      - name: Validate release metadata")
                .substringBefore("      - name: Upload immutable-release preflight evidence")

        assertContains(trigger, "workflow_dispatch:")
        assertContains(trigger, "release_tag:")
        assertContains(trigger, "allow_legacy_baseline_bootstrap:")
        assertContains(trigger, "immutable_releases_preflight:")
        val bootstrapInput =
            trigger
                .substringAfter("      allow_legacy_baseline_bootstrap:")
                .substringBefore("      immutable_releases_preflight:")
        assertContains(bootstrapInput, "required: true")
        assertContains(bootstrapInput, "type: boolean")
        assertContains(bootstrapInput, "default: false")
        assertFalse(trigger.contains("push:"), "A tag push must not bypass the trusted admin preflight.")
        assertContains(release, "actions: read")
        assertContains(release, "contents: write")
        val workflowPermissions = release.substringBefore("jobs:")
        val androidJob = release.substringAfter("  android:").substringBefore("\n  release:")
        val releaseJobPermissions =
            release.substringAfter("  release:").substringBefore("    steps:")
        assertContains(workflowPermissions, "contents: read")
        assertFalse(
            androidJob.contains("contents: write"),
            "Build, test, and signing steps must not receive repository write permission.",
        )
        assertContains(releaseJobPermissions, "contents: write")
        assertContains(metadata, "Only the repository owner may dispatch")
        assertContains(metadata, "default_branch")
        assertContains(metadata, "default_branch_head")
        assertContains(metadata, "live_default_branch")
        assertContains(metadata, "live_default_branch_head")
        assertContains(metadata, "repos/\${GITHUB_REPOSITORY}/commits/\${live_default_branch}")
        assertContains(metadata, "refs/heads/\${live_default_branch}")
        assertContains(metadata, "GITHUB_SHA")
        assertContains(metadata, "release workflow definition must run from the exact protected default-branch head")
        assertContains(metadata, "default-branch head")
        assertContains(metadata, "GITHUB_RUN_ATTEMPT")
        assertContains(metadata, "Release reruns cannot reuse immutable-release preflight evidence")
        val releaseJob = release.substringAfter("  release:")
        val releaseAttemptGuard = releaseJob.indexOf("      - name: Reject stale release rerun preflight")
        val releaseCheckout = releaseJob.indexOf("      - name: Checkout")
        val releaseApi = releaseJob.indexOf("gh api")
        assertTrue(
            releaseAttemptGuard >= 0 && releaseCheckout > releaseAttemptGuard && releaseApi > releaseAttemptGuard,
            "A failed-jobs rerun must fail before the release job performs checkout, download, or API work.",
        )
        assertContains(releaseJob.substring(releaseAttemptGuard, releaseCheckout), "GITHUB_RUN_ATTEMPT")
        assertContains(releaseJob.substring(releaseAttemptGuard, releaseCheckout), "new admin GET and fresh workflow_dispatch")
        assertContains(metadata, "response_raw_base64")
        assertContains(metadata, ".allow_legacy_baseline_bootstrap")
        assertContains(metadata, "ALLOW_LEGACY_BASELINE_BOOTSTRAP")
        assertContains(metadata, "base64 --decode")
        assertContains(metadata, "immutable-releases-response.json")
        assertContains(metadata, "computed_response_sha256")
        assertContains(metadata, "canonical_raw_response")
        assertContains(metadata, "canonical_recorded_response")
        assertContains(metadata, "GET /repos/\${GITHUB_REPOSITORY}/immutable-releases")
        assertContains(metadata, "repos/\${GITHUB_REPOSITORY}/actions/runs/\${GITHUB_RUN_ID}")
        assertContains(metadata, "run_created_at")
        assertContains(metadata, "dispatch_delay")
        assertContains(metadata, "-gt 300")
        assertContains(release, "path: release-preflight/")
        assertContains(release, "immutable_releases_preflight: \$immutable_preflight")
        assertFalse(release.contains("administration: read"))
        assertFalse(release.contains("IMMUTABLE_RELEASES_ADMIN_TOKEN"))
    }

    @Test
    fun `latest production is the upgrade baseline while every public Android manifest sets the version floor`() {
        val release = releaseFile.readText()
        val closureFilter = File("../scripts/public-android-release-closure.jq").readText()
        val metadata =
            release
                .substringAfter("      - name: Validate release metadata")
                .substringBefore("      - name: Upload immutable-release preflight evidence")

        val latestProbe = metadata.lineSequence().single { it.contains("latest_release=") }
        assertContains(latestProbe, "releases/latest")
        assertFalse(
            latestProbe.contains("|| true") || latestProbe.contains("2>/dev/null"),
            "Latest-Release authorization, network, and server failures must stop publication.",
        )
        assertContains(metadata, "previous_tag=\"\$latest_tag\"")
        assertFalse(
            closureFilter.contains(".prerelease == false"),
            "A public prerelease manifest can set an installed versionCode floor too.",
        )
        assertContains(metadata, "manifest_assets")
        assertContains(metadata, "jq -sSce -f scripts/public-android-release-closure.jq")
        assertContains(closureFilter, "select(.draft == false)")
        assertContains(closureFilter, "{id, size, digest}")
        assertContains(metadata, "ambiguous update manifest asset closure")
        assertContains(metadata, "Accept: application/octet-stream")
        assertContains(metadata, "max_public_version_code")
        assertContains(metadata, "Candidate versionCode must exceed every public Android manifest")
        assertContains(metadata, "Candidate SemVer must exceed every public Android Release")
        assertContains(metadata, "if [ \"\$version_code\" -le \"\$previous_version_code\" ]")
        assertContains(metadata, "The only mutable latest baseline path is explicit exact v0.1.7+10")
        assertContains(metadata, "\${ALLOW_LEGACY_BASELINE_BOOTSTRAP}\" != \"true")
        assertContains(metadata, "\${RELEASE_TAG}\" != \"v0.1.7")
        assertContains(metadata, "\${version_code}\" != \"10")
        assertContains(metadata, "378776133")
        assertContains(metadata, "807277e66d9922dc179f00d85ddcdcb5244c7ded")
        assertContains(metadata, "frozen_bootstrap_assets")
        assertContains(metadata, "Legacy bootstrap authorization cannot be used after an immutable Release is latest")

        val published = listOf(Triple("v0.1.6", 9, true), Triple("v0.1.7", 10, false))
        val nextCandidate = "v0.1.8" to 11
        assertTrue(published.single { it.third }.first == "v0.1.6")
        assertTrue(nextCandidate.second > published.maxOf { it.second })
        assertTrue(nextCandidate.first > published.maxOf { it.first })
    }

    @Test
    fun `draft ownership is durable before acceptance and the public transition uses its numeric id`() {
        val release = releaseFile.readText()
        val prepareIndex = release.indexOf("      - name: Prepare owned never-public GitHub Release")
        val receiptIndex = release.indexOf("      - name: Upload exact-run pre-publish ownership receipt")
        val acceptanceIndex = release.indexOf("      - name: Upgrade previous production through the staged production updater")
        val publishIndex = release.indexOf("      - name: Publish the receipt-owned GitHub Release")
        val verifyIndex = release.indexOf("      - name: Post-publish verification")

        assertTrue(
            prepareIndex >= 0 && receiptIndex > prepareIndex && acceptanceIndex > receiptIndex &&
                publishIndex > acceptanceIndex && verifyIndex > publishIndex,
        )
        val preparation = release.substring(prepareIndex, receiptIndex)
        val publication = release.substring(publishIndex, verifyIndex)
        assertContains(preparation, "openaria.mobile.release-ownership.v1")
        assertContains(preparation, "run_id: \$run_id")
        assertContains(preparation, "run_attempt: \$run_attempt")
        assertContains(preparation, "source_commit: \$source_commit")
        assertContains(preparation, "release_id: \$target_release_id")
        assertContains(preparation, "assets: \$target_assets")
        assertContains(preparation, "tag_commit: \$baseline_tag_commit")
        assertContains(preparation, "gh api --method POST \"repos/\${GITHUB_REPOSITORY}/releases\"")
        assertContains(preparation, "upload_url")
        assertContains(preparation, "Content-Type: application/octet-stream")
        assertContains(preparation, "already has a never-public draft")
        assertContains(preparation, "will not adopt, edit, or delete an unowned draft")
        assertContains(preparation, "Release tag \${RELEASE_TAG} already exists")
        assertContains(preparation, "no pre-existing tag or Release")
        assertFalse(
            preparation.contains("tag_commit") &&
                preparation.contains("but this build checked out"),
            "A matching existing tag must not be adopted by a later publication attempt.",
        )
        assertFalse(preparation.contains("--method DELETE"))
        assertFalse(preparation.contains("gh release upload"))
        assertFalse(preparation.contains("gh release create"))
        assertContains(publication, "steps.staged_upgrade.outcome == 'success'")
        assertContains(publication, "releases/\${owned_release_id}")
        assertContains(publication, "gh api --method PATCH")
        assertContains(publication, "-F draft=false")
        assertContains(publication, "-f make_latest=true")
        assertFalse(publication.contains("gh release edit"))
        assertFalse(
            publication.substringBefore("gh api --method PATCH").contains("releases/tags/\${RELEASE_TAG}"),
            "The public-only by-tag endpoint cannot authorize a draft transition.",
        )
        val liveFloorIndex = publication.indexOf("live_public_android_releases")
        val finalClosureIndex = publication.indexOf("final_target=")
        val numericPatchIndex = publication.indexOf("gh api --method PATCH")
        assertTrue(liveFloorIndex >= 0 && finalClosureIndex > liveFloorIndex && numericPatchIndex > finalClosureIndex)
        assertContains(publication, "Candidate versionCode no longer exceeds every public Android manifest")
        assertContains(publication, "final_public_android_release_closure")
        assertContains(publication, "jq -sSce -f scripts/public-android-release-closure.jq")
        assertContains(publication, "manifest_asset_digest")
        assertContains(publication, "final_baseline_assets")
        assertContains(publication, "final_baseline_tag")
        assertContains(publication, "Target, baseline, or public Android version-floor closure changed")
        assertContains(release, "continue-on-error: true")
        assertContains(release, "android-release-ownership-\${{ env.RELEASE_TAG }}-\${{ github.run_id }}-\${{ github.run_attempt }}")
    }

    @Test
    fun `only the frozen v0_1_6 tuple may be a mutable latest baseline`() {
        val release = releaseFile.readText()
        val preparation =
            release
                .substringAfter("      - name: Prepare owned never-public GitHub Release")
                .substringBefore("      - name: Upload exact-run pre-publish ownership receipt")
        val publication =
            release
                .substringAfter("      - name: Publish the receipt-owned GitHub Release")
                .substringBefore("      - name: Post-publish verification")

        listOf(preparation, publication).forEach { stateMachine ->
            assertContains(stateMachine, "v0.1.6")
            assertContains(stateMachine, "378776133")
            assertContains(stateMachine, "807277e66d9922dc179f00d85ddcdcb5244c7ded")
            assertContains(stateMachine, "5e8f1a50138099d39c309bc81557833e94fb5973004dbd811586fa584184670d")
            assertContains(stateMachine, "d9861e88ffcc9146e50804c80968fc182ef31df14c5a3ec6651423e92d5b6149")
            assertContains(stateMachine, "ddd6f8db7283fdfbfe37171361aa863309d5cfe8eda69229a2a46aba89868db7")
            assertContains(stateMachine, "e818d40f1050bc86c650600a05a28f189b7501f6744c904968562307946e376f")
        }
        assertContains(preparation, "ALLOW_LEGACY_BASELINE_BOOTSTRAP")
        assertContains(preparation, "\${RELEASE_TAG}\" != \"v0.1.7")
        assertContains(preparation, "\${VERSION_NAME}\" != \"0.1.7")
        assertContains(preparation, "\${VERSION_CODE}\" != \"10")
        assertContains(preparation, "legacy_bootstrap=true")
        assertContains(preparation, "legacy_bootstrap_authorized: \$legacy_bootstrap_authorized")
        assertContains(preparation, "valid only while the frozen mutable v0.1.6 Release is latest")
        assertContains(publication, "All post-bootstrap production baselines must be immutable")
        assertContains(publication, "owned_legacy_bootstrap_authorized")
        assertContains(publication, "Legacy bootstrap authorization cannot be reused")
        assertContains(publication, "\${RELEASE_TAG}\" != \"v0.1.7")
        assertContains(publication, "\${VERSION_CODE}\" != \"10")
    }

    @Test
    fun `old production APK reaches exact candidate through a closed API 33 TLS endpoint before publish`() {
        val release = releaseFile.readText()
        val helper = File("../scripts/android-staged-update-acceptance.sh").readText()
        val server = File("../scripts/android-staged-update-server.py").readText()
        val acceptance = File("../scripts/android-in-app-update-acceptance.py").readText()
        val stagedStep =
            release
                .substringAfter("      - name: Upgrade previous production through the staged production updater")
                .substringBefore("      - name: Upload pre-publish in-app upgrade evidence")
        val stagedEvidenceUpload =
            release
                .substringAfter("      - name: Upload pre-publish in-app upgrade evidence")
                .substringBefore("      - name: Publish the receipt-owned GitHub Release")

        assertContains(release, "system-images;android-33;google_apis;x86_64")
        assertContains(stagedStep, "api-level: 33")
        assertContains(stagedStep, "-writable-system")
        assertContains(stagedStep, "script: >-")
        assertFalse(stagedStep.contains("script: |"))
        assertFalse(stagedStep.contains("\\\n"))
        assertContains(stagedStep, "scripts/android-staged-update-acceptance.sh")
        assertContains(stagedEvidenceUpload, "if-no-files-found: error")
        assertContains(helper, "adb root")
        assertContains(helper, "adb remount")
        assertContains(helper, "getprop sys.boot_completed")
        assertContains(helper, "pm path android")
        assertContains(helper, "framework_ready")
        assertContains(helper, "/system/etc/security/cacerts")
        assertContains(helper, "10.0.2.2 github.com")
        assertContains(helper, "android-staged-update-server.py")
        assertContains(helper, "manifest_get_count")
        assertContains(helper, "apk_head_count")
        assertContains(helper, "apk_get_count")
        assertContains(helper, "productionUpdaterRequests")
        assertContains(helper, "--legacy-bootstrap-authorized")
        assertFalse(helper.contains("adb install"), "Only the Python driver may install the public baseline APK.")
        assertContains(server, "ThreadingHTTPServer")
        assertContains(server, "releases/latest/download/android-update.json")
        assertContains(server, "do_HEAD")
        assertContains(server, "do_GET")
        assertContains(acceptance, "--baseline-release-id")
        assertContains(acceptance, "--baseline-apk-sha256")
        assertContains(acceptance, "--candidate-manifest-path")
        assertContains(acceptance, "--candidate-apk-sha256")
        assertContains(acceptance, "--source-commit")
        assertContains(acceptance, "--run-attempt")
        assertContains(acceptance, "--legacy-bootstrap-authorized")
        assertContains(acceptance, "legacyBootstrapAuthorized")
        assertContains(acceptance, "candidateDownloadedByBaselineApp")
        assertContains(acceptance, "manualCandidateDownload")
        assertContains(acceptance, "installedPackageUpgraded")
        assertTrue(
            "command([\"adb\", \"install\"".toRegex(RegexOption.LITERAL).findAll(acceptance).count() == 1,
            "The acceptance driver must install only the downloaded public baseline; its updater installs the candidate.",
        )
    }

    @Test
    fun `post publish work is bounded read only verification of exact immutable state`() {
        val release = releaseFile.readText()
        val postPublish =
            release
                .substringAfter("      - name: Post-publish verification")
                .substringBefore("      - name: Upload post-publish verification evidence")
        val postPublishEvidenceUpload =
            release.substringAfter("      - name: Upload post-publish verification evidence")

        assertContains(postPublish, "if: always()")
        assertTrue(
            "--retry-all-errors".toRegex(RegexOption.LITERAL).findAll(postPublish).count() >= 3,
            "Manifest, APK, and AAB publication reads must retry HTTP and network failures.",
        )
        assertTrue(
            "for attempt in \$(seq 1 10)".toRegex(RegexOption.LITERAL).findAll(postPublish).count() >= 4,
            "Anonymous bytes and Release API state each require a bounded convergence loop.",
        )
        assertTrue(
            "cmp --silent".toRegex(RegexOption.LITERAL).findAll(postPublish).count() >= 3,
            "Every anonymous publication object must match exact staged bytes.",
        )
        assertContains(postPublish, "releases/\${owned_release_id}")
        assertContains(postPublish, "releases/tags/\${RELEASE_TAG}")
        assertContains(postPublish, "'.immutable'")
        assertContains(postPublish, "published_assets")
        assertContains(postPublish, "openaria.mobile.release-post-publish-verification.v1")
        assertContains(postPublish, "legacy_bootstrap_authorized")
        assertContains(postPublishEvidenceUpload, "if-no-files-found: error")
        assertFalse(release.contains("if-no-files-found: warn"))
        listOf("--method PATCH", "--method POST", "--method DELETE", "gh release edit", "gh release upload").forEach {
            assertFalse(postPublish.contains(it), "Post-publication verification must remain read-only: $it")
        }
        assertFalse(release.contains("release_rollback:"))
        assertFalse(release.contains("android_in_app_upgrade:"))
        assertTrue(
            "make_latest=true".toRegex(RegexOption.LITERAL).findAll(release).count() == 1,
            "Only the receipt-owned candidate publication may set latest; no baseline rollback mutation is supported.",
        )
    }

    @Test
    fun `release workflow runs source and executable staged updater tests`() {
        val release = releaseFile.readText()
        val dispatcher = File("../scripts/dispatch-android-release.sh").readText()
        val releaseGuide = File("../docs/ANDROID_RELEASE.md").readText()

        assertContains(release, "python3 -m unittest scripts/test_android_in_app_update_acceptance.py")
        assertContains(release, "python3 -m unittest scripts/test_android_staged_update_server.py")
        assertContains(release, "python3 -m unittest scripts/test_dispatch_android_release.py")
        assertContains(release, "python3 -m unittest scripts/test_public_android_release_closure.py")
        assertContains(release, "bash -n scripts/android-staged-update-acceptance.sh")
        assertContains(release, "bash -n scripts/dispatch-android-release.sh")
        assertContains(dispatcher, "repos/\${repository}/immutable-releases")
        assertContains(dispatcher, "response_raw_base64")
        assertContains(dispatcher, "response_sha256")
        assertContains(dispatcher, "--slurpfile response")
        assertContains(dispatcher, "gh workflow run")
        assertContains(dispatcher, "--allow-legacy-baseline-bootstrap")
        assertContains(dispatcher, "allow_legacy_baseline_bootstrap")
        assertContains(releaseGuide, "Do not rerun a failed release run")
        assertContains(releaseGuide, "residual race")
        assertContains(releaseGuide, "v0.1.7, versionCode 10")
        assertContains(releaseGuide, "never placed in a repository secret")
    }
}
