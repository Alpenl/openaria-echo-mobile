package com.openaria.openaria_echo_mobile;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

public final class AppUpdateManager {
    private static final String MANIFEST_URL =
            "https://github.com/Alpenl/openaria-echo-mobile/releases/latest/download/android-update.json";
    private static final String SCHEMA = "openaria.echo.mobile.android-update.v1";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String EXPECTED_PACKAGE = "com.openaria.openaria_echo_mobile";
    static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final Object SESSION_LOCK = new Object();
    private static SharedSession sharedSession;

    public interface Listener {
        void onStateChanged(State state);
    }

    private final SharedSession session;
    // The shared process session keeps only a weak reference so an old Activity can be collected.
    private final Listener listener;

    public AppUpdateManager(Context context, Listener listener) {
        Context applicationContext = context.getApplicationContext();
        synchronized (SESSION_LOCK) {
            if (sharedSession == null) {
                sharedSession = new SharedSession(applicationContext);
            }
            session = sharedSession;
        }
        this.listener = listener;
        session.attach(listener);
    }

    public State state() {
        return session.state();
    }

    public void check() {
        session.check();
    }

    public void downloadAndInstall() {
        session.downloadAndInstall();
    }

    static State evaluateManifestResponse(int statusCode, String body, ApkIdentity installed)
            throws JSONException, UpdateException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new UpdateException("Update manifest request failed: HTTP " + statusCode);
        }

        Manifest manifest = Manifest.fromJson(new JSONObject(body));
        if (!EXPECTED_PACKAGE.equals(manifest.packageName)
                || !installed.packageName.equals(manifest.packageName)) {
            throw new UpdateException(
                    "Update package mismatch: expected " + installed.packageName + ", got " + manifest.packageName);
        }
        if (!installed.signingCertificateSha256.equals(manifest.signingCertificateSha256)) {
            throw new UpdateException("Update manifest signing certificate does not match the installed app");
        }
        if (manifest.versionCode == installed.versionCode
                && !manifest.version.equals(installed.versionName)) {
            throw new UpdateException("Update manifest version name does not match the installed version code");
        }
        if (manifest.versionCode > installed.versionCode) {
            return State.available(installed.versionCode, installed.versionName, manifest);
        }
        return State.current(
                installed.versionCode,
                installed.versionName,
                "Open Aria Echo is up to date.");
    }

    static void verifyDownloadedFile(File file, Artifact artifact) throws IOException, UpdateException {
        long actualBytes = file.length();
        if (actualBytes != artifact.bytes) {
            throw new UpdateException(
                    "APK size mismatch: expected " + artifact.bytes + ", got " + actualBytes);
        }
        String digest = sha256(file);
        if (!artifact.sha256.equals(digest)) {
            throw new UpdateException("APK SHA-256 verification failed");
        }
    }

    static void requireSuccessfulAssetResponse(int statusCode) throws UpdateException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new UpdateException("APK download failed: HTTP " + statusCode);
        }
    }

    static void verifyCandidateIdentity(Manifest manifest, ApkIdentity installed, ApkIdentity candidate)
            throws UpdateException {
        if (!EXPECTED_PACKAGE.equals(candidate.packageName)
                || !manifest.packageName.equals(candidate.packageName)) {
            throw new UpdateException(
                    "APK package mismatch: expected " + manifest.packageName + ", got " + candidate.packageName);
        }
        if (!manifest.version.equals(candidate.versionName)) {
            throw new UpdateException(
                    "APK version name mismatch: expected " + manifest.version + ", got " + candidate.versionName);
        }
        if (manifest.versionCode != candidate.versionCode) {
            throw new UpdateException(
                    "APK version code mismatch: expected " + manifest.versionCode + ", got " + candidate.versionCode);
        }
        if (candidate.versionCode <= installed.versionCode) {
            throw new UpdateException("APK version code is not newer than the installed app");
        }
        if (!manifest.signingCertificateSha256.equals(candidate.signingCertificateSha256)) {
            throw new UpdateException("APK signing certificate does not match the update manifest");
        }
        if (!installed.signingCertificateSha256.equals(candidate.signingCertificateSha256)) {
            throw new UpdateException("APK signing certificate does not match the installed app");
        }
    }

    static final class ProcessOperation {
        private State state;

        ProcessOperation(State initialState) {
            state = initialState;
        }

        synchronized State state() {
            return state;
        }

        synchronized boolean beginCheck() {
            if (!state.canCheck()) {
                return false;
            }
            state = State.checking(state.currentBuildNumber, state.currentVersionName);
            return true;
        }

        synchronized Manifest beginDownload() {
            if (state.phase != Phase.AVAILABLE || state.manifest == null) {
                return null;
            }
            Manifest manifest = state.manifest;
            state = State.downloading(
                    state.currentBuildNumber,
                    state.currentVersionName,
                    manifest,
                    0,
                    manifest.apk.bytes);
            return manifest;
        }

        synchronized Manifest beginReadyVerification() {
            if (state.phase != Phase.READY_TO_INSTALL || state.manifest == null) {
                return null;
            }
            Manifest manifest = state.manifest;
            state = State.verifying(state.currentBuildNumber, state.currentVersionName, manifest);
            return manifest;
        }

        synchronized boolean beginInstallHandoff() {
            if (state.phase != Phase.READY_TO_INSTALL || state.manifest == null) {
                return false;
            }
            state = State.installingHandoff(
                    state.currentBuildNumber,
                    state.currentVersionName,
                    state.manifest);
            return true;
        }

        synchronized void complete(State next) {
            state = next;
        }
    }

    static final class ActiveListener {
        private final Object lock = new Object();
        private WeakReference<Listener> listener = new WeakReference<>(null);

        void attach(Listener next) {
            synchronized (lock) {
                listener = new WeakReference<>(next);
            }
        }

        void deliver(State state) {
            Listener current;
            synchronized (lock) {
                current = listener.get();
            }
            if (current != null) {
                current.onStateChanged(state);
            }
        }
    }

    private static final class SharedSession {
        private final Context context;
        private final ProcessOperation operation;
        private final Object verifiedLock = new Object();
        private final ActiveListener activeListener = new ActiveListener();
        private File verifiedApk;
        private Manifest verifiedManifest;

        SharedSession(Context context) {
            this.context = context;
            operation = new ProcessOperation(State.idle(
                    loadCurrentBuildNumber(context),
                    loadCurrentVersionName(context)));
        }

        void attach(Listener listener) {
            activeListener.attach(listener);
        }

        State state() {
            return operation.state();
        }

        void check() {
            if (!operation.beginCheck()) {
                return;
            }
            discardVerifiedApk();
            notifyListener();
            new Thread(this::runCheck, "openaria-app-update-check").start();
        }

        void downloadAndInstall() {
            if (operation.state().phase == Phase.READY_TO_INSTALL) {
                Manifest readyManifest = operation.beginReadyVerification();
                if (readyManifest == null) {
                    return;
                }
                notifyListener();
                new Thread(
                                () -> reverifyAndInstall(readyManifest),
                                "openaria-app-update-reverify")
                        .start();
                return;
            }
            Manifest manifest = operation.beginDownload();
            if (manifest == null) {
                return;
            }
            notifyListener();
            new Thread(
                            () -> runDownloadAndInstall(manifest),
                            "openaria-app-update-download")
                    .start();
        }

        private void runCheck() {
            try {
                ApkIdentity installed = loadInstalledIdentity(context);
                HttpResult result = get(new URL(MANIFEST_URL));
                State next = evaluateManifestResponse(result.statusCode, result.body, installed);
                Manifest manifest = Manifest.fromJson(new JSONObject(result.body));
                probeAsset(manifest.apk);
                complete(next);
            } catch (Exception exception) {
                fail(formatError(exception));
            }
        }

        private void runDownloadAndInstall(Manifest manifest) {
            File partial = null;
            File target = null;
            try {
                File updateDir = new File(context.getCacheDir(), "updates");
                if (!updateDir.exists() && !updateDir.mkdirs()) {
                    throw new UpdateException("Could not create update cache directory.");
                }
                target = new File(updateDir, "openaria-echo-mobile-" + manifest.versionCode + ".apk");
                partial = new File(target.getPath() + ".part");
                deleteIfPresent(partial);
                deleteIfPresent(target);

                HttpURLConnection connection = (HttpURLConnection) manifest.apk.url.openConnection();
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(30_000);
                connection.setRequestMethod("GET");
                State downloadState = operation.state();
                try {
                    int status = connection.getResponseCode();
                    requireSuccessfulAssetResponse(status);
                    if (!"https".equals(connection.getURL().getProtocol())) {
                        throw new UpdateException("APK download redirected to a non-HTTPS URL");
                    }

                    long contentLength = connection.getContentLengthLong();
                    long total = contentLength > 0 ? contentLength : manifest.apk.bytes;
                    long downloaded = 0;
                    try (InputStream in = connection.getInputStream();
                            FileOutputStream out = new FileOutputStream(partial)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            downloaded += read;
                            if (downloaded > manifest.apk.bytes) {
                                throw new UpdateException(
                                        "APK size exceeds manifest bytes: " + manifest.apk.bytes);
                            }
                            out.write(buffer, 0, read);
                            complete(State.downloading(
                                    downloadState.currentBuildNumber,
                                    downloadState.currentVersionName,
                                    manifest,
                                    downloaded,
                                    total));
                        }
                    }
                } finally {
                    connection.disconnect();
                }

                complete(State.verifying(
                        downloadState.currentBuildNumber,
                        downloadState.currentVersionName,
                        manifest));
                verifyDownloadedFile(partial, manifest.apk);
                if (!partial.renameTo(target)) {
                    throw new UpdateException("Could not finalize downloaded APK.");
                }

                ApkIdentity installed = loadInstalledIdentity(context);
                ApkIdentity candidate = loadArchiveIdentity(context, target);
                verifyCandidateIdentity(manifest, installed, candidate);

                rememberVerifiedApk(manifest, target);
                complete(State.readyToInstall(installed.versionCode, installed.versionName, manifest));
                handoffVerifiedApk(manifest, target);
            } catch (Exception exception) {
                if (partial != null) {
                    deleteIfPresent(partial);
                }
                if (target != null) {
                    deleteIfPresent(target);
                }
                fail(formatError(exception));
            }
        }

        private void reverifyAndInstall(Manifest manifest) {
            File apk;
            synchronized (verifiedLock) {
                apk = verifiedManifest == manifest ? verifiedApk : null;
            }
            if (apk == null) {
                fail("Verified update APK is no longer available; check for updates again.");
                return;
            }
            try {
                verifyDownloadedFile(apk, manifest.apk);
                ApkIdentity installed = loadInstalledIdentity(context);
                verifyCandidateIdentity(manifest, installed, loadArchiveIdentity(context, apk));
                complete(State.readyToInstall(installed.versionCode, installed.versionName, manifest));
                handoffVerifiedApk(manifest, apk);
            } catch (Exception exception) {
                discardVerifiedApk();
                fail(formatError(exception));
            }
        }

        private void handoffVerifiedApk(Manifest manifest, File apk) {
            if (!ensureInstallPermission(manifest)) {
                return;
            }
            if (!operation.beginInstallHandoff()) {
                return;
            }
            notifyListener();
            try {
                installApk(context, apk);
                complete(State.readyToInstall(
                                operation.state().currentBuildNumber,
                                operation.state().currentVersionName,
                                manifest)
                        .withMessage("Android installer opened. Tap Install update to retry if you cancel it."));
            } catch (UpdateException exception) {
                complete(State.readyToInstall(
                                operation.state().currentBuildNumber,
                                operation.state().currentVersionName,
                                manifest)
                        .withMessage(formatError(exception)));
            }
        }

        private boolean ensureInstallPermission(Manifest manifest) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || context.getPackageManager().canRequestPackageInstalls()) {
                return true;
            }
            Intent intent = new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                complete(State.readyToInstall(
                                operation.state().currentBuildNumber,
                                operation.state().currentVersionName,
                                manifest)
                        .withMessage(
                                "Allow Open Aria Echo to install updates, then return and tap Install update again."));
            } catch (ActivityNotFoundException | SecurityException exception) {
                complete(State.readyToInstall(
                                operation.state().currentBuildNumber,
                                operation.state().currentVersionName,
                                manifest)
                        .withMessage("Android could not open the install unknown apps permission screen."));
            }
            return false;
        }

        private void rememberVerifiedApk(Manifest manifest, File apk) {
            synchronized (verifiedLock) {
                verifiedManifest = manifest;
                verifiedApk = apk;
            }
        }

        private void discardVerifiedApk() {
            File apk;
            synchronized (verifiedLock) {
                apk = verifiedApk;
                verifiedApk = null;
                verifiedManifest = null;
            }
            if (apk != null) {
                deleteIfPresent(apk);
            }
        }

        private void complete(State next) {
            operation.complete(next);
            notifyListener();
        }

        private void fail(String message) {
            complete(operation.state().failed(message));
        }

        private void notifyListener() {
            activeListener.deliver(operation.state());
        }
    }

    private static HttpResult get(URL url) throws IOException, UpdateException {
        if (!"https".equals(url.getProtocol())) {
            throw new UpdateException("Update manifest URL must be HTTPS.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("GET");
        try {
            int status = connection.getResponseCode();
            if (!"https".equals(connection.getURL().getProtocol())) {
                throw new UpdateException("Update manifest redirected to a non-HTTPS URL");
            }
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = stream == null
                    ? ""
                    : readManifestUtf8(stream, connection.getContentLengthLong());
            return new HttpResult(status, body);
        } finally {
            connection.disconnect();
        }
    }

    private static void probeAsset(Artifact artifact) throws IOException, UpdateException {
        HttpURLConnection connection = (HttpURLConnection) artifact.url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestMethod("HEAD");
        try {
            requireSuccessfulAssetResponse(connection.getResponseCode());
            if (!"https".equals(connection.getURL().getProtocol())) {
                throw new UpdateException("APK asset redirected to a non-HTTPS URL");
            }
            long bytes = connection.getContentLengthLong();
            if (bytes > 0 && bytes != artifact.bytes) {
                throw new UpdateException(
                        "APK asset size mismatch: expected " + artifact.bytes + ", got " + bytes);
            }
        } finally {
            connection.disconnect();
        }
    }

    static String readManifestUtf8(InputStream stream, long contentLength)
            throws IOException, UpdateException {
        try (InputStream input = stream) {
            if (contentLength > MAX_MANIFEST_BYTES) {
                throw new UpdateException(
                        "Update manifest response exceeds " + MAX_MANIFEST_BYTES + " bytes");
            }
            byte[] buffer = new byte[8192];
            int initialCapacity = contentLength >= 0
                    ? (int) contentLength
                    : buffer.length;
            ByteArrayOutputStream body = new ByteArrayOutputStream(initialCapacity);
            int total = 0;
            while (true) {
                int remaining = MAX_MANIFEST_BYTES + 1 - total;
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read == -1) {
                    return new String(body.toByteArray(), StandardCharsets.UTF_8);
                }
                if (read == 0) {
                    int value = input.read();
                    if (value == -1) {
                        return new String(body.toByteArray(), StandardCharsets.UTF_8);
                    }
                    buffer[0] = (byte) value;
                    read = 1;
                }
                total += read;
                if (total > MAX_MANIFEST_BYTES) {
                    throw new UpdateException(
                            "Update manifest response exceeds " + MAX_MANIFEST_BYTES + " bytes");
                }
                body.write(buffer, 0, read);
            }
        }
    }

    private static void installApk(Context context, File apk) throws UpdateException {
        if (!apk.isFile()) {
            throw new UpdateException("APK file does not exist.");
        }
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            throw new UpdateException("No Android package installer is available.");
        } catch (SecurityException exception) {
            throw new UpdateException("Android denied package installer access.");
        }
    }

    @SuppressWarnings("deprecation")
    private static ApkIdentity loadInstalledIdentity(Context context) throws UpdateException {
        try {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), flags);
            return identityFromPackageInfo(info);
        } catch (PackageManager.NameNotFoundException exception) {
            throw new UpdateException("Could not inspect the installed app identity.");
        }
    }

    @SuppressWarnings("deprecation")
    private static ApkIdentity loadArchiveIdentity(Context context, File apk) throws UpdateException {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (info == null) {
            throw new UpdateException("Downloaded file is not a readable Android APK.");
        }
        return identityFromPackageInfo(info);
    }

    @SuppressWarnings("deprecation")
    private static ApkIdentity identityFromPackageInfo(PackageInfo info) throws UpdateException {
        long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signingInfo = info.signingInfo;
            signatures = signingInfo == null ? null : signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length != 1) {
            throw new UpdateException("Android update APK must have exactly one signing certificate.");
        }
        return new ApkIdentity(
                info.packageName == null ? "" : info.packageName,
                info.versionName == null ? "" : info.versionName,
                versionCode,
                sha256(signatures[0].toByteArray()));
    }

    @SuppressWarnings("deprecation")
    private static long loadCurrentBuildNumber(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException exception) {
            return 0;
        }
    }

    private static String loadCurrentVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException exception) {
            return "";
        }
    }

    private static void deleteIfPresent(File file) {
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static String sha256(File file) throws IOException, UpdateException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        }
    }

    private static String sha256(byte[] value) throws UpdateException {
        MessageDigest digest = sha256Digest();
        return toHex(digest.digest(value));
    }

    private static MessageDigest sha256Digest() throws UpdateException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new UpdateException("SHA-256 is not available on this device.");
        }
    }

    private static String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder();
        for (byte item : value) {
            builder.append(String.format(Locale.US, "%02x", item));
        }
        return builder.toString();
    }

    private static String formatError(Exception exception) {
        return exception.getMessage() == null ? exception.toString() : exception.getMessage();
    }

    public enum Phase {
        IDLE,
        CHECKING,
        CURRENT,
        AVAILABLE,
        DOWNLOADING,
        VERIFYING,
        READY_TO_INSTALL,
        INSTALLING_HANDOFF,
        FAILED
    }

    public static final class State {
        public final Phase phase;
        public final long currentBuildNumber;
        public final String currentVersionName;
        public final Manifest manifest;
        public final long downloadedBytes;
        public final long totalBytes;
        public final String message;

        private State(
                Phase phase,
                long currentBuildNumber,
                String currentVersionName,
                Manifest manifest,
                long downloadedBytes,
                long totalBytes,
                String message) {
            this.phase = phase;
            this.currentBuildNumber = currentBuildNumber;
            this.currentVersionName = currentVersionName;
            this.manifest = manifest;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.message = message;
        }

        static State idle(long currentBuildNumber, String currentVersionName) {
            return new State(
                    Phase.IDLE,
                    currentBuildNumber,
                    currentVersionName,
                    null,
                    0,
                    0,
                    "");
        }

        static State current(long currentBuildNumber, String currentVersionName, String message) {
            return new State(Phase.CURRENT, currentBuildNumber, currentVersionName, null, 0, 0, message);
        }

        static State checking(long currentBuildNumber, String currentVersionName) {
            return new State(
                    Phase.CHECKING,
                    currentBuildNumber,
                    currentVersionName,
                    null,
                    0,
                    0,
                    "Checking for updates");
        }

        static State available(long currentBuildNumber, String currentVersionName, Manifest manifest) {
            return new State(
                    Phase.AVAILABLE,
                    currentBuildNumber,
                    currentVersionName,
                    manifest,
                    0,
                    manifest.apk.bytes,
                    "Version " + manifest.version + " is available.");
        }

        static State downloading(
                long currentBuildNumber,
                String currentVersionName,
                Manifest manifest,
                long downloadedBytes,
                long totalBytes) {
            return new State(
                    Phase.DOWNLOADING,
                    currentBuildNumber,
                    currentVersionName,
                    manifest,
                    downloadedBytes,
                    totalBytes,
                    "Downloading update");
        }

        static State verifying(long currentBuildNumber, String currentVersionName, Manifest manifest) {
            return new State(
                    Phase.VERIFYING,
                    currentBuildNumber,
                    currentVersionName,
                    manifest,
                    manifest.apk.bytes,
                    manifest.apk.bytes,
                    "Verifying downloaded APK identity");
        }

        static State readyToInstall(long currentBuildNumber, String currentVersionName, Manifest manifest) {
            return new State(
                    Phase.READY_TO_INSTALL,
                    currentBuildNumber,
                    currentVersionName,
                    manifest,
                    manifest.apk.bytes,
                    manifest.apk.bytes,
                    "Verified update is ready to install");
        }

        static State installingHandoff(long currentBuildNumber, String currentVersionName, Manifest manifest) {
            return new State(
                    Phase.INSTALLING_HANDOFF,
                    currentBuildNumber,
                    currentVersionName,
                    manifest,
                    manifest.apk.bytes,
                    manifest.apk.bytes,
                    "Opening Android installer");
        }

        State withMessage(String nextMessage) {
            return new State(
                    phase,
                    currentBuildNumber,
                    currentVersionName,
                    manifest,
                    downloadedBytes,
                    totalBytes,
                    nextMessage);
        }

        State failed(String failure) {
            return new State(
                    Phase.FAILED,
                    currentBuildNumber,
                    currentVersionName,
                    manifest,
                    downloadedBytes,
                    totalBytes,
                    failure);
        }

        public boolean canCheck() {
            return phase != Phase.CHECKING
                    && phase != Phase.DOWNLOADING
                    && phase != Phase.VERIFYING
                    && phase != Phase.INSTALLING_HANDOFF;
        }

        public boolean canInstall() {
            return (phase == Phase.AVAILABLE || phase == Phase.READY_TO_INSTALL)
                    && manifest != null;
        }
    }

    public static final class Manifest {
        public final String version;
        public final long versionCode;
        public final String packageName;
        public final String signingCertificateSha256;
        public final String pubDate;
        public final String notes;
        public final Artifact apk;

        private Manifest(
                String version,
                long versionCode,
                String packageName,
                String signingCertificateSha256,
                String pubDate,
                String notes,
                Artifact apk) {
            this.version = version;
            this.versionCode = versionCode;
            this.packageName = packageName;
            this.signingCertificateSha256 = signingCertificateSha256;
            this.pubDate = pubDate;
            this.notes = notes;
            this.apk = apk;
        }

        static Manifest fromJson(JSONObject json) throws JSONException, UpdateException {
            if (!SCHEMA.equals(json.optString("schema"))) {
                throw new UpdateException("unsupported update manifest schema");
            }
            String version = json.optString("version", "");
            long versionCode = json.optLong("versionCode", 0);
            String packageName = json.optString("packageName", "");
            String signingCertificateSha256 = json.optString("signingCertificateSha256", "");
            if (!version.matches("^[0-9]+\\.[0-9]+\\.[0-9]+$")) {
                throw new UpdateException("version must use X.Y.Z form");
            }
            if (versionCode <= 0) {
                throw new UpdateException("versionCode must be a positive integer");
            }
            if (packageName.trim().isEmpty()) {
                throw new UpdateException("packageName must be non-empty");
            }
            if (!signingCertificateSha256.matches("^[0-9a-fA-F]{64}$")) {
                throw new UpdateException(
                        "signingCertificateSha256 must be a 64-character hex digest");
            }
            JSONObject android = json.optJSONObject("android");
            JSONObject apk = android == null ? null : android.optJSONObject("apk");
            if (apk == null) {
                throw new UpdateException("android.apk must be an object");
            }
            Artifact apkArtifact = Artifact.fromJson(apk);
            String repositoryPath = "/Alpenl/openaria-echo-mobile/releases/download/";
            if (!"github.com".equalsIgnoreCase(apkArtifact.url.getHost())) {
                throw new UpdateException("android.apk.url must use the Open Aria GitHub repository");
            }
            if (!apkArtifact.url.getPath().startsWith(repositoryPath)) {
                throw new UpdateException("android.apk.url must use the Open Aria GitHub repository");
            }
            String expectedPath = repositoryPath + "v" + version + "/";
            if (!apkArtifact.url.getPath().startsWith(expectedPath)) {
                throw new UpdateException("android.apk.url must use the offered version tag");
            }
            String assetName = apkArtifact.url.getPath().substring(expectedPath.length());
            if (assetName.isEmpty()
                    || assetName.contains("/")
                    || !assetName.endsWith(".apk")
                    || (apkArtifact.url.getPort() != -1 && apkArtifact.url.getPort() != 443)
                    || apkArtifact.url.getUserInfo() != null
                    || apkArtifact.url.getQuery() != null
                    || apkArtifact.url.getRef() != null) {
                throw new UpdateException("android.apk.url must identify one APK release asset");
            }
            return new Manifest(
                    version,
                    versionCode,
                    packageName,
                    signingCertificateSha256.toLowerCase(Locale.US),
                    json.optString("pubDate", null),
                    json.optString("notes", null),
                    apkArtifact);
        }
    }

    public static final class Artifact {
        public final URL url;
        public final String sha256;
        public final long bytes;

        private Artifact(URL url, String sha256, long bytes) {
            this.url = url;
            this.sha256 = sha256;
            this.bytes = bytes;
        }

        static Artifact fromJson(JSONObject json) throws UpdateException {
            try {
                URL url = new URL(json.optString("url", ""));
                String sha256 = json.optString("sha256", "");
                long bytes = json.optLong("bytes", 0);
                if (!"https".equals(url.getProtocol())) {
                    throw new UpdateException("android.apk.url must be an HTTPS URL");
                }
                if (!sha256.matches("^[0-9a-fA-F]{64}$")) {
                    throw new UpdateException("android.apk.sha256 must be a 64-character hex digest");
                }
                if (bytes <= 0) {
                    throw new UpdateException("android.apk.bytes must be a positive integer");
                }
                return new Artifact(url, sha256.toLowerCase(Locale.US), bytes);
            } catch (IOException exception) {
                throw new UpdateException("android.apk.url must be an HTTPS URL");
            }
        }
    }

    public static final class ApkIdentity {
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final String signingCertificateSha256;

        ApkIdentity(
                String packageName,
                String versionName,
                long versionCode,
                String signingCertificateSha256) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.signingCertificateSha256 = signingCertificateSha256.toLowerCase(Locale.US);
        }
    }

    private static final class HttpResult {
        final int statusCode;
        final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    static final class UpdateException extends Exception {
        UpdateException(String message) {
            super(message);
        }
    }
}
