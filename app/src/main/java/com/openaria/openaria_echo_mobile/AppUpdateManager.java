package com.openaria.openaria_echo_mobile;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

    public interface Listener {
        void onStateChanged(State state);
    }

    private final Context context;
    private final Listener listener;
    private final Object lock = new Object();
    private State state;

    public AppUpdateManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.state = State.idle(loadCurrentBuildNumber(), loadCurrentVersionName());
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public void check() {
        synchronized (lock) {
            if (!state.canCheck()) {
                return;
            }
            setStateLocked(state.withPhase(Phase.CHECKING, "Checking for updates"));
        }
        new Thread(this::runCheck, "openaria-app-update-check").start();
    }

    public void downloadAndInstall() {
        final Manifest manifest;
        synchronized (lock) {
            if (!state.canInstall()) {
                return;
            }
            manifest = state.manifest;
            setStateLocked(State.downloading(state.currentBuildNumber, state.currentVersionName, manifest, 0, manifest.apk.bytes));
        }
        new Thread(() -> runDownloadAndInstall(manifest), "openaria-app-update-download").start();
    }

    private void runCheck() {
        try {
            long currentBuild = loadCurrentBuildNumber();
            String currentVersion = loadCurrentVersionName();
            HttpResult result = get(new URL(MANIFEST_URL));
            if (result.statusCode == 404) {
                update(State.current(currentBuild, currentVersion, "Open Aria Echo is up to date."));
                return;
            }
            if (result.statusCode < 200 || result.statusCode >= 300) {
                throw new UpdateException("Update manifest request failed: HTTP " + result.statusCode);
            }
            Manifest manifest = Manifest.fromJson(new JSONObject(result.body));
            if (!EXPECTED_PACKAGE.equals(manifest.packageName)) {
                throw new UpdateException(
                        "Update package mismatch: expected " + EXPECTED_PACKAGE + ", got " + manifest.packageName);
            }
            if (manifest.versionCode > currentBuild) {
                update(State.available(currentBuild, currentVersion, manifest));
            } else {
                update(State.current(currentBuild, currentVersion, "Open Aria Echo is up to date."));
            }
        } catch (Exception exception) {
            fail(formatError(exception));
        }
    }

    private void runDownloadAndInstall(Manifest manifest) {
        File partial = null;
        try {
            File updateDir = new File(context.getCacheDir(), "updates");
            if (!updateDir.exists() && !updateDir.mkdirs()) {
                throw new UpdateException("Could not create update cache directory.");
            }
            File target = new File(updateDir, "openaria-echo-mobile-" + manifest.versionCode + ".apk");
            partial = new File(target.getPath() + ".part");
            deleteIfPresent(partial);
            deleteIfPresent(target);

            HttpURLConnection connection = (HttpURLConnection) manifest.apk.url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new UpdateException("APK download failed: HTTP " + status);
            }

            long total = connection.getContentLengthLong() > 0 ? connection.getContentLengthLong() : manifest.apk.bytes;
            long downloaded = 0;
            try (InputStream in = connection.getInputStream();
                    FileOutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    update(State.downloading(loadCurrentBuildNumber(), loadCurrentVersionName(), manifest, downloaded, total));
                }
            } finally {
                connection.disconnect();
            }

            if (downloaded != manifest.apk.bytes) {
                deleteIfPresent(partial);
                throw new UpdateException(
                        "APK size mismatch: expected " + manifest.apk.bytes + ", got " + downloaded);
            }
            String digest = sha256(partial);
            if (!manifest.apk.sha256.equals(digest)) {
                deleteIfPresent(partial);
                throw new UpdateException("APK SHA-256 verification failed");
            }
            if (!partial.renameTo(target)) {
                throw new UpdateException("Could not finalize downloaded APK.");
            }

            update(State.installing(loadCurrentBuildNumber(), loadCurrentVersionName(), manifest));
            installApk(target);
        } catch (Exception exception) {
            if (partial != null) {
                deleteIfPresent(partial);
            }
            fail(formatError(exception));
        }
    }

    private void installApk(File apk) throws UpdateException {
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

    private HttpResult get(URL url) throws IOException, UpdateException {
        if (!"https".equals(url.getProtocol())) {
            throw new UpdateException("Update manifest URL must be HTTPS.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null ? "" : readUtf8(stream);
        connection.disconnect();
        return new HttpResult(status, body);
    }

    private static String readUtf8(InputStream stream) throws IOException {
        byte[] buffer = new byte[8192];
        StringBuilder builder = new StringBuilder();
        int read;
        while ((read = stream.read(buffer)) != -1) {
            builder.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private void fail(String message) {
        synchronized (lock) {
            setStateLocked(state.failed(message));
        }
    }

    private void update(State next) {
        synchronized (lock) {
            setStateLocked(next);
        }
    }

    private void setStateLocked(State next) {
        state = next;
        listener.onStateChanged(next);
    }

    @SuppressWarnings("deprecation")
    private long loadCurrentBuildNumber() {
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

    private String loadCurrentVersionName() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException exception) {
            return "";
        }
    }

    private static void deleteIfPresent(File file) {
        if (file.exists()) {
            // A stale partial should not hide the real update error.
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static String sha256(File file) throws IOException, UpdateException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) {
                builder.append(String.format(Locale.US, "%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new UpdateException("SHA-256 is not available on this device.");
        }
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
        INSTALLING,
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
            return new State(Phase.IDLE, currentBuildNumber, currentVersionName, null, 0, 0, "Tap Check to query GitHub Releases.");
        }

        static State current(long currentBuildNumber, String currentVersionName, String message) {
            return new State(Phase.CURRENT, currentBuildNumber, currentVersionName, null, 0, 0, message);
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

        static State downloading(long currentBuildNumber, String currentVersionName, Manifest manifest, long downloadedBytes, long totalBytes) {
            return new State(
                    Phase.DOWNLOADING,
                    currentBuildNumber,
                    currentVersionName,
                    manifest,
                    downloadedBytes,
                    totalBytes,
                    "Downloading update");
        }

        static State installing(long currentBuildNumber, String currentVersionName, Manifest manifest) {
            return new State(
                    Phase.INSTALLING,
                    currentBuildNumber,
                    currentVersionName,
                    manifest,
                    manifest.apk.bytes,
                    manifest.apk.bytes,
                    "Opening Android installer");
        }

        State withPhase(Phase nextPhase, String nextMessage) {
            return new State(nextPhase, currentBuildNumber, currentVersionName, manifest, downloadedBytes, totalBytes, nextMessage);
        }

        State failed(String failure) {
            return new State(Phase.FAILED, currentBuildNumber, currentVersionName, manifest, downloadedBytes, totalBytes, failure);
        }

        public boolean canCheck() {
            return phase != Phase.CHECKING && phase != Phase.DOWNLOADING && phase != Phase.INSTALLING;
        }

        public boolean canInstall() {
            return phase == Phase.AVAILABLE && manifest != null;
        }
    }

    public static final class Manifest {
        public final String version;
        public final long versionCode;
        public final String packageName;
        public final String pubDate;
        public final String notes;
        public final Artifact apk;

        private Manifest(String version, long versionCode, String packageName, String pubDate, String notes, Artifact apk) {
            this.version = version;
            this.versionCode = versionCode;
            this.packageName = packageName;
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
            if (version.trim().isEmpty()) {
                throw new UpdateException("version must be non-empty");
            }
            if (versionCode <= 0) {
                throw new UpdateException("versionCode must be a positive integer");
            }
            if (packageName.trim().isEmpty()) {
                throw new UpdateException("packageName must be non-empty");
            }
            JSONObject android = json.optJSONObject("android");
            JSONObject apk = android == null ? null : android.optJSONObject("apk");
            if (apk == null) {
                throw new UpdateException("android.apk must be an object");
            }
            return new Manifest(
                    version,
                    versionCode,
                    packageName,
                    json.optString("pubDate", null),
                    json.optString("notes", null),
                    Artifact.fromJson(apk));
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

    private static final class HttpResult {
        final int statusCode;
        final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private static final class UpdateException extends Exception {
        UpdateException(String message) {
            super(message);
        }
    }
}
