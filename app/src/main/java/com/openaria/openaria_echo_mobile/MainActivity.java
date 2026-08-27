package com.openaria.openaria_echo_mobile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(new ApertureView(this));
    }

    private static final class ApertureView extends View {
        private static final int VOID = Color.rgb(0, 0, 0);
        private static final int DECK = Color.rgb(7, 9, 10);
        private static final int INK = Color.rgb(240, 243, 244);
        private static final int INK_2 = Color.rgb(170, 179, 184);
        private static final int INK_3 = Color.rgb(125, 135, 140);
        private static final int RECORD = Color.rgb(255, 59, 45);
        private static final int CAUTION = Color.rgb(224, 160, 32);
        private static final int PERMIT = Color.rgb(70, 201, 138);
        private static final int LIVE = Color.rgb(127, 227, 245);
        private static final int PEAK = Color.rgb(232, 88, 255);
        private static final int HAIR = Color.argb(30, 255, 255, 255);
        private static final int HAIR_2 = Color.argb(48, 255, 255, 255);
        private static final int GLASS = Color.argb(205, 8, 10, 11);
        private static final int GLASS_STRONG = Color.argb(238, 9, 11, 12);
        private static final int SUNKEN = Color.argb(16, 255, 255, 255);

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final List<Hotspot> hotspots = new ArrayList<>();
        private final Typeface sans = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface cond = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        private final Typeface mono = Typeface.MONOSPACE;
        private final AppUpdateManager appUpdates;

        private boolean mounted;
        private boolean recording;
        private boolean focusOpen;
        private boolean sessionOpen;
        private boolean peak = true;
        private boolean grid = true;
        private boolean imuHud;
        private int eyeMode;
        private Mode mode = Mode.RECORD;

        ApertureView(Context context) {
            super(context);
            appUpdates = new AppUpdateManager(context, ignored -> post(this::invalidate));
            setBackgroundColor(VOID);
            setFocusable(true);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            hotspots.clear();

            if (!mounted) {
                drawMount(canvas);
                return;
            }

            switch (mode) {
                case RECORD:
                    drawRecord(canvas);
                    break;
                case ROLL:
                    drawRoll(canvas);
                    break;
                case BODY:
                    drawBody(canvas);
                    break;
                case NET:
                    drawNet(canvas);
                    break;
            }

            if (sessionOpen) {
                drawSessionSheet(canvas);
            } else if (focusOpen) {
                drawFocusSheet(canvas);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) {
                return true;
            }

            for (int i = hotspots.size() - 1; i >= 0; i--) {
                Hotspot hotspot = hotspots.get(i);
                if (hotspot.bounds.contains(event.getX(), event.getY())) {
                    handle(hotspot.action);
                    performClick();
                    return true;
                }
            }

            performClick();
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private void handle(String action) {
            switch (action) {
                case "mount":
                    mounted = true;
                    mode = Mode.RECORD;
                    break;
                case "shutter":
                    if (mode == Mode.RECORD) {
                        recording = !recording;
                        focusOpen = false;
                        sessionOpen = false;
                    }
                    break;
                case "mode-record":
                    mode = Mode.RECORD;
                    sessionOpen = false;
                    break;
                case "mode-roll":
                    mode = Mode.ROLL;
                    focusOpen = false;
                    break;
                case "mode-body":
                    mode = Mode.BODY;
                    focusOpen = false;
                    sessionOpen = false;
                    break;
                case "mode-net":
                    mode = Mode.NET;
                    focusOpen = false;
                    sessionOpen = false;
                    break;
                case "focus":
                    focusOpen = !focusOpen;
                    sessionOpen = false;
                    break;
                case "close-focus":
                    focusOpen = false;
                    break;
                case "cell":
                    sessionOpen = true;
                    focusOpen = false;
                    break;
                case "close-session":
                    sessionOpen = false;
                    break;
                case "eye":
                    eyeMode = (eyeMode + 1) % 3;
                    break;
                case "grid":
                    grid = !grid;
                    break;
                case "peak":
                    peak = !peak;
                    break;
                case "imu":
                    imuHud = !imuHud;
                    break;
                case "update-check":
                    appUpdates.check();
                    break;
                case "update-install":
                    appUpdates.downloadAndInstall();
                    break;
                default:
                    break;
            }
            invalidate();
        }

        private void drawMount(Canvas canvas) {
            drawViewfinder(canvas, true, false, false);
            float w = getWidth();
            float h = getHeight();
            float m = dp(12);

            drawHud(canvas, "Aperture", "no body", Status.OFF, null);

            float y = dp(86);
            smallCaps(canvas, "局域网内的机身", m, y, INK_3);
            y += dp(17);

            RectF card = rect(m, y, w - m, y + dp(104));
            panel(canvas, card, dp(11), GLASS, HAIR_2);
            circleStroke(canvas, card.left + dp(26), card.top + dp(28), dp(16), PERMIT, dp(2));
            fillCircle(canvas, card.left + dp(26), card.top + dp(28), dp(6), PERMIT);
            label(canvas, "rp-ylx-a13f", card.left + dp(52), card.top + dp(24), 17, INK, cond, Paint.Align.LEFT);
            label(canvas, "10.42.0.1:8080 · API v4 · pkg 0.5.2", card.left + dp(52), card.top + dp(42), 9.5f, INK_3, mono, Paint.Align.LEFT);
            RectF mount = rect(card.right - dp(76), card.top + dp(14), card.right - dp(10), card.top + dp(42));
            pill(canvas, mount, INK, VOID, "Mount");
            addHotspot("mount", mount);
            drawFacts(canvas, rect(card.left + dp(10), card.top + dp(58), card.right - dp(10), card.bottom - dp(10)),
                    new Fact[] {
                            new Fact("FREE", "412G", INK),
                            new Fact("TEMP", "48.2", INK),
                            new Fact("CAM", "ready", LIVE)
                    });

            y = card.bottom + dp(9);
            RectF bad = rect(m, y, w - m, y + dp(76));
            panel(canvas, bad, dp(11), Color.argb(150, 8, 10, 11), HAIR);
            hatchCircle(canvas, bad.left + dp(26), bad.top + dp(27), dp(16), CAUTION);
            circleStroke(canvas, bad.left + dp(26), bad.top + dp(27), dp(16), CAUTION, dp(2));
            label(canvas, "openaria-2", bad.left + dp(52), bad.top + dp(24), 17, INK, cond, Paint.Align.LEFT);
            label(canvas, "探测失败 · connection refused", bad.left + dp(52), bad.top + dp(42), 9.5f, CAUTION, mono, Paint.Align.LEFT);
            pill(canvas, rect(bad.right - dp(72), bad.top + dp(14), bad.right - dp(10), bad.top + dp(42)), Color.TRANSPARENT, INK_3, "Retry");

            y = bad.bottom + dp(8);
            drawLineItem(canvas, "手动填地址", "10.42.0.1:8080 或 https://rp-ylx.local", "Probe", rect(m, y, w - m, y + dp(46)), false);
            y += dp(46);
            drawLineItem(canvas, "访问令牌", "••••••••••••  存入系统安全区", "Edit", rect(m, y, w - m, y + dp(46)), false);

            smallCaps(canvas, "mDNS 只给候选 · 连接前必须探测 Device API v4", m, h - dp(88), INK_3);
            drawMountBottom(canvas);
        }

        private void drawRecord(Canvas canvas) {
            drawViewfinder(canvas, false, grid, recording);
            if (recording) {
                drawHud(canvas, "rp-ylx-a13f", "rec 04:12", Status.RECORDING,
                        new Fact[] {
                                new Fact("FRAMES", "7524", LIVE),
                                new Fact("WRITTEN", "3.1 G", INK),
                                new Fact("TEMP", "61.7 C", CAUTION),
                                new Fact("FREE", "409 G", INK)
                        });
            } else {
                drawHud(canvas, "rp-ylx-a13f", "idle", Status.IDLE,
                        new Fact[] {
                                new Fact("TEMP", "48.2 C", INK),
                                new Fact("FREE", "412 G", INK),
                                new Fact("LINK", "AP", LIVE),
                                new Fact("SYNC", "LOCK", LIVE)
                        });
            }

            drawRails(canvas);

            if (recording || imuHud) {
                float w = getWidth();
                float h = getHeight();
                RectF imu = rect(w - dp(178), h * 0.47f, w - dp(14), h * 0.47f + dp(94));
                panel(canvas, imu, dp(10), GLASS_STRONG, HAIR);
                smallCaps(canvas, "LIVE IMU · LOCKED", imu.left + dp(10), imu.top + dp(18), INK_3);
                label(canvas, "ACC", imu.left + dp(10), imu.top + dp(42), 10, INK_3, mono, Paint.Align.LEFT);
                label(canvas, "12 -3 981", imu.right - dp(10), imu.top + dp(42), 10, LIVE, mono, Paint.Align.RIGHT);
                label(canvas, "GYR", imu.left + dp(10), imu.top + dp(60), 10, INK_3, mono, Paint.Align.LEFT);
                label(canvas, "0 1 -2", imu.right - dp(10), imu.top + dp(60), 10, LIVE, mono, Paint.Align.RIGHT);
                label(canvas, "GAP", imu.left + dp(10), imu.top + dp(78), 10, INK_3, mono, Paint.Align.LEFT);
                label(canvas, "214 ms", imu.right - dp(10), imu.top + dp(78), 10, CAUTION, mono, Paint.Align.RIGHT);
            }

            drawRecordBottom(canvas);
        }

        private void drawRoll(Canvas canvas) {
            drawViewfinder(canvas, true, false, false);
            drawHud(canvas, "ROLL · 24 段", "idle", Status.OFF, null);
            float w = getWidth();
            float h = getHeight();
            float m = dp(12);

            drawSegment(canvas, rect(m, dp(70), w - m, dp(102)), new String[] {"全部", "可用", "失败", "未封存"}, 0);

            float gridTop = dp(116);
            float gap = dp(7);
            float cell = (w - m * 2 - gap * 2) / 3f;
            String[] states = {"USABLE", "USABLE", "SEALING", "UNUSABLE", "USABLE", "USABLE", "UNSEALED", "USABLE", "USABLE"};
            String[] lens = {"12:04", "07:41", "03:12", "0:38", "21:57", "05:03", "09:20", "14:36", "02:11"};
            for (int i = 0; i < states.length; i++) {
                int col = i % 3;
                int row = i / 3;
                RectF r = rect(m + col * (cell + gap), gridTop + row * (cell + gap), m + col * (cell + gap) + cell, gridTop + row * (cell + gap) + cell);
                drawFilmCell(canvas, r, states[i], lens[i]);
                addHotspot("cell", r);
            }

            RectF warn = rect(m, gridTop + 3 * cell + 3 * gap, w - m, gridTop + 3 * cell + 3 * gap + dp(38));
            drawBand(canvas, warn, RECORD, "1 段留存的失败会话仍占用 240 MiB，未自动清除", true);
            drawModeBottom(canvas);
        }

        private void drawBody(Canvas canvas) {
            drawViewfinder(canvas, true, false, false);
            drawHud(canvas, "BODY", "idle", Status.IDLE, null);
            float w = getWidth();
            float h = getHeight();
            float m = dp(12);
            float y = dp(86);

            smallCaps(canvas, "健康", m, y, INK_3);
            y += dp(8);
            drawFacts(canvas, rect(m, y, w - m, y + dp(48)),
                    new Fact[] {
                            new Fact("TEMP", "48.2 C", INK),
                            new Fact("CAM", "ready", LIVE),
                            new Fact("WRITE", "yes", INK)
                    });
            y += dp(62);
            drawMeter(canvas, rect(m, y, w - m, y + dp(4)), 0.56f, LIVE);
            label(canvas, "已用 519 GiB", m, y + dp(20), 9.5f, INK_3, mono, Paint.Align.LEFT);
            label(canvas, "共 931.5 GiB", w - m, y + dp(20), 9.5f, INK_3, mono, Paint.Align.RIGHT);

            y += dp(52);
            smallCaps(canvas, "标识", m, y, INK_3);
            y += dp(18);
            drawKv(canvas, m, y, new String[][] {
                    {"Device", "dev_01J9E8ZC4T"},
                    {"API", "v4"},
                    {"Package", "0.5.2 · 77f24f3"},
                    {"Volume", "vol_nvme0n1p2"}
            });

            AppUpdateManager.State updateState = appUpdates.state();
            RectF update = rect(m, Math.max(y + dp(110), h - dp(298)), w - m, Math.max(y + dp(110), h - dp(298)) + dp(164));
            panel(canvas, update, dp(11), GLASS, HAIR_2);
            smallCaps(canvas, "应用升级", update.left + dp(10), update.top + dp(22), INK_3);
            label(canvas, updateVersionLabel(updateState), update.right - dp(10), update.top + dp(22), 10, updateVersionColor(updateState), mono, Paint.Align.RIGHT);
            drawMeter(canvas, rect(update.left + dp(10), update.top + dp(42), update.right - dp(10), update.top + dp(46)), updateProgress(updateState), updateMeterColor(updateState));
            labelClip(canvas, updateState.message, update.left + dp(10), update.top + dp(66), 9.5f, updateState.phase == AppUpdateManager.Phase.FAILED ? Color.rgb(255, 185, 179) : INK_3, mono, Paint.Align.LEFT, update.width() - dp(20));
            labelClip(canvas, updateDetail(updateState), update.left + dp(10), update.top + dp(88), 9.5f, INK_3, mono, Paint.Align.LEFT, update.width() - dp(20));
            RectF check = rect(update.left + dp(10), update.bottom - dp(42), update.centerX() - dp(4), update.bottom - dp(12));
            RectF install = rect(update.centerX() + dp(4), update.bottom - dp(42), update.right - dp(10), update.bottom - dp(12));
            pill(canvas, check, Color.TRANSPARENT, updateState.canCheck() ? INK : INK_3, "Check");
            pill(canvas, install, updateState.canInstall() ? INK : Color.TRANSPARENT, updateState.canInstall() ? VOID : INK_3, "Install");
            addHotspot("update-check", check);
            if (updateState.canInstall()) {
                addHotspot("update-install", install);
            }

            drawModeBottom(canvas);
        }

        private void drawNet(Canvas canvas) {
            drawViewfinder(canvas, true, false, false);
            drawHud(canvas, "NET · AP", "verified", Status.IDLE, null);
            float w = getWidth();
            float h = getHeight();
            float m = dp(12);
            float y = dp(82);

            drawSegment(canvas, rect(m, y, w - m, y + dp(34)), new String[] {"热点", "Wi-Fi 客户端", "有线 DHCP"}, 0);
            y += dp(46);
            drawLineItem(canvas, "wlan0 · up", "OpenAria-A13F · 10.42.0.1", "route", rect(m, y, w - m, y + dp(48)), true);
            y += dp(48);
            drawLineItem(canvas, "wlan1 · down", "—", "", rect(m, y, w - m, y + dp(48)), true);
            y += dp(48);
            drawLineItem(canvas, "eth0 · up", "192.168.7.24", "", rect(m, y, w - m, y + dp(48)), true);

            y += dp(18);
            smallCaps(canvas, "附近网络", m, y, INK_3);
            y += dp(12);
            drawLineItem(canvas, "lab-5g", "wpa2 · -46 dBm", "Join", rect(m, y, w - m, y + dp(48)), true);
            y += dp(48);
            drawLineItem(canvas, "site-mesh", "wpa2 · -63 dBm", "Join", rect(m, y, w - m, y + dp(48)), true);
            y += dp(48);
            drawLineItem(canvas, "<hidden>", "wpa3 · -71 dBm", "Join", rect(m, y, w - m, y + dp(48)), true);

            drawBand(canvas, rect(m, h - dp(164), w - m, h - dp(112)), CAUTION, "切换链路会中断预览与事件流；录制中该区整块锁定。", false);
            drawModeBottom(canvas);
        }

        private void drawFocusSheet(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float sheetH = Math.min(dp(362), h * 0.56f);
            RectF close = rect(0, 0, w, h - sheetH);
            addHotspot("close-focus", close);
            RectF sheet = rect(0, h - sheetH, w, h);
            sheet(canvas, sheet);
            float m = dp(12);
            float y = sheet.top + dp(19);
            grab(canvas, sheet.centerX(), sheet.top + dp(8));
            label(canvas, "Focus", m, y + dp(18), 22, INK, cond, Paint.Align.LEFT);
            y += dp(34);
            drawSegment(canvas, rect(m, y, w - m, y + dp(34)), new String[] {"Auto", "Manual"}, 1);

            float cx = w / 2f;
            float cy = y + dp(106);
            float r = dp(58);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new android.graphics.SweepGradient(cx, cy, new int[] {Color.argb(72, 127, 227, 245), Color.TRANSPARENT, Color.argb(72, 127, 227, 245)}, null));
            canvas.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            circleStroke(canvas, cx, cy, r, HAIR_2, dp(1));
            fillCircle(canvas, cx, cy, r - dp(14), Color.rgb(5, 8, 10));
            circleStroke(canvas, cx, cy, r - dp(14), HAIR, dp(1));
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45 - 90);
                float x1 = cx + (float) Math.cos(a) * (r - dp(2));
                float y1 = cy + (float) Math.sin(a) * (r - dp(2));
                float x2 = cx + (float) Math.cos(a) * (r - dp(9));
                float y2 = cy + (float) Math.sin(a) * (r - dp(9));
                line(canvas, x1, y1, x2, y2, HAIR_2, dp(1));
            }
            label(canvas, "512", cx, cy - dp(2), 24, LIVE, mono, Paint.Align.CENTER);
            smallCaps(canvas, "focus", cx - dp(18), cy + dp(18), INK_3);
            label(canvas, "MIN 0", m, cy + r + dp(18), 9.5f, INK_3, mono, Paint.Align.LEFT);
            label(canvas, "STEP 1", cx, cy + r + dp(18), 9.5f, INK_3, mono, Paint.Align.CENTER);
            label(canvas, "MAX 1023", w - m, cy + r + dp(18), 9.5f, INK_3, mono, Paint.Align.RIGHT);

            y = cy + r + dp(28);
            drawLineItem(canvas, "峰值对焦", "边缘着紫，仅辅助，不写入会话", peak ? "On" : "Off", rect(m, y, w - m, y + dp(48)), true);
            y += dp(48);
            drawLineItem(canvas, "放大检查", "双击画面 100% 检视中心", "2×", rect(m, y, w - m, y + dp(48)), true);
        }

        private void drawSessionSheet(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float sheetH = Math.min(dp(474), h * 0.72f);
            RectF sheet = rect(0, h - sheetH, w, h);
            dim(canvas, 110);
            sheet(canvas, sheet);
            float m = dp(12);
            grab(canvas, sheet.centerX(), sheet.top + dp(8));

            RectF back = rect(m, sheet.top + dp(18), m + dp(72), sheet.top + dp(46));
            pill(canvas, back, Color.TRANSPARENT, INK_3, "← Roll");
            addHotspot("close-session", back);
            label(canvas, "take 2", w - m, sheet.top + dp(38), 18, INK, cond, Paint.Align.RIGHT);

            float y = sheet.top + dp(70);
            label(canvas, "Kitchen pour · take 2", m, y, 22, INK, cond, Paint.Align.LEFT);
            y += dp(18);
            statusChip(canvas, rect(m, y, m + dp(82), y + dp(24)), "usable", Status.IDLE);
            statusChip(canvas, rect(m + dp(88), y, m + dp(154), y + dp(24)), "sealed", Status.OFF);
            statusChip(canvas, rect(m + dp(160), y, m + dp(258), y + dp(24)), "stereo+imu", Status.OFF);

            y += dp(44);
            drawKv(canvas, m, y, new String[][] {
                    {"Session", "01J9F3K7QX8N2M4B6C0D5E7A"},
                    {"Manifest", "mf_7c1e9a04"},
                    {"Duration", "12:04 · 8.7 GiB"},
                    {"Device", "rp-ylx-a13f"}
            });

            y += dp(112);
            smallCaps(canvas, "制品 3", m, y, INK_3);
            y += dp(12);
            drawLineItem(canvas, "video/left.mp4", "primary · video/mp4 · 4.4 GiB", "Copy URL", rect(m, y, w - m, y + dp(52)), true);
            y += dp(52);
            drawLineItem(canvas, "video/right.mp4", "primary · video/mp4 · 4.2 GiB", "Copy URL", rect(m, y, w - m, y + dp(52)), true);
            y += dp(52);
            drawLineItem(canvas, "imu/imu.jsonl", "telemetry · application/jsonl · 96.2 MiB", "Copy URL", rect(m, y, w - m, y + dp(52)), true);
        }

        private void drawViewfinder(Canvas canvas, boolean dark, boolean showGrid, boolean rec) {
            float w = getWidth();
            float h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(
                    w * 0.5f,
                    h * 0.38f,
                    Math.max(w, h) * 0.72f,
                    dark
                            ? new int[] {Color.rgb(20, 25, 28), Color.rgb(8, 11, 13), VOID}
                            : new int[] {Color.rgb(34, 50, 58), Color.rgb(13, 20, 24), Color.rgb(5, 8, 10)},
                    new float[] {0f, 0.58f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            if (!dark) {
                paint.setShader(new LinearGradient(0, 0, w, h, Color.argb(70, 127, 227, 245), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setShader(null);
            }

            if (showGrid) {
                line(canvas, w / 3f, 0, w / 3f, h, Color.argb(34, 255, 255, 255), dp(1));
                line(canvas, 2 * w / 3f, 0, 2 * w / 3f, h, Color.argb(34, 255, 255, 255), dp(1));
                line(canvas, 0, h / 3f, w, h / 3f, Color.argb(34, 255, 255, 255), dp(1));
                line(canvas, 0, 2 * h / 3f, w, 2 * h / 3f, Color.argb(34, 255, 255, 255), dp(1));
            }

            if (rec) {
                strokeRound(canvas, rect(dp(4), dp(4), w - dp(4), h - dp(4)), dp(26), RECORD, dp(2));
            }
        }

        private void drawHud(Canvas canvas, String name, String status, Status statusKind, Fact[] facts) {
            float w = getWidth();
            float m = dp(12);
            RectF top = rect(m, dp(12), w - m, dp(48));
            panel(canvas, top, dp(9), GLASS, HAIR);
            fillRound(canvas, rect(top.left + dp(9), top.centerY() - dp(5), top.left + dp(19), top.centerY() + dp(5)), dp(2),
                    statusKind == Status.RECORDING ? RECORD : INK);
            label(canvas, name, top.left + dp(28), top.centerY() + dp(5), 16, INK, cond, Paint.Align.LEFT);
            RectF chip = rect(top.right - dp(96), top.top + dp(8), top.right - dp(8), top.bottom - dp(8));
            statusChip(canvas, chip, status, statusKind);

            if (facts != null && facts.length > 0) {
                drawFacts(canvas, rect(m, dp(55), w - m, dp(103)), facts);
            }
        }

        private void drawRails(Canvas canvas) {
            float w = getWidth();
            float top = dp(126);
            float railW = dp(42);
            float gap = dp(7);
            float left = dp(12);
            float right = w - dp(12) - railW;

            RectF eye = rect(left, top, left + railW, top + dp(84));
            panel(canvas, eye, dp(9), GLASS, HAIR);
            String[] eyes = {"L+R", "L", "R"};
            for (int i = 0; i < eyes.length; i++) {
                RectF seg = rect(eye.left, eye.top + i * dp(28), eye.right, eye.top + (i + 1) * dp(28));
                if (i == eyeMode) {
                    fillRound(canvas, inset(seg, dp(1), dp(1)), dp(7), SUNKEN);
                }
                label(canvas, eyes[i], eye.centerX(), seg.centerY() + dp(4), 8.5f, i == eyeMode ? INK : INK_3, mono, Paint.Align.CENTER);
                if (i < 2) {
                    line(canvas, eye.left, seg.bottom, eye.right, seg.bottom, HAIR, dp(1));
                }
            }
            addHotspot("eye", eye);

            RectF gridButton = rect(left, eye.bottom + gap, left + railW, eye.bottom + gap + dp(55));
            railButton(canvas, gridButton, "Grid", grid ? LIVE : INK_2, Icon.GRID);
            addHotspot("grid", gridButton);

            RectF af = rect(right, top, right + railW, top + dp(55));
            railButton(canvas, af, "AF", focusOpen ? LIVE : INK_2, Icon.FOCUS);
            addHotspot("focus", af);
            RectF pk = rect(right, af.bottom + gap, right + railW, af.bottom + gap + dp(55));
            railButton(canvas, pk, "Peak", peak ? PEAK : INK_2, Icon.PEAK);
            addHotspot("peak", pk);
            RectF im = rect(right, pk.bottom + gap, right + railW, pk.bottom + gap + dp(55));
            railButton(canvas, im, "IMU", imuHud ? LIVE : INK_2, Icon.IMU);
            addHotspot("imu", im);
            RectF body = rect(right, im.bottom + gap, right + railW, im.bottom + gap + dp(55));
            railButton(canvas, body, "Body", INK_2, Icon.BODY);
            addHotspot("mode-body", body);
        }

        private void drawRecordBottom(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float m = dp(12);
            RectF fade = rect(0, h - dp(188), w, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, fade.top, 0, fade.bottom, Color.TRANSPARENT, Color.argb(210, 0, 0, 0), Shader.TileMode.CLAMP));
            canvas.drawRect(fade, paint);
            paint.setShader(null);

            if (recording) {
                drawBand(canvas, rect(m, h - dp(158), w - m, h - dp(116)), CAUTION,
                        "imu_gap_detected · IMU 采样间隔 214 ms 超出预算", false);
            }

            drawModeSelector(canvas, h - dp(92));

            float cy = h - dp(44);
            RectF nameSlot = rect(m, cy - dp(31), w / 2f - dp(42), cy + dp(31));
            smallCaps(canvas, recording ? "正在录" : "下一段命名", nameSlot.left, nameSlot.top + dp(10), INK_3);
            RectF chip = rect(nameSlot.left, nameSlot.top + dp(18), nameSlot.right, nameSlot.top + dp(48));
            panel(canvas, chip, dp(8), GLASS, HAIR);
            labelClip(canvas, "Kitchen pour · take 3", chip.left + dp(8), chip.centerY() + dp(4), 12, INK, sansMedium, Paint.Align.LEFT, chip.width() - dp(16));
            if (recording) {
                labelClip(canvas, "01J9F3K7QX8N2M4B6C0D5E7A", nameSlot.left, nameSlot.bottom - dp(1), 8.5f, INK_3, mono, Paint.Align.LEFT, nameSlot.width());
            }

            RectF shutter = rect(w / 2f - dp(33), cy - dp(33), w / 2f + dp(33), cy + dp(33));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(82, 0, 0, 0));
            canvas.drawOval(shutter, paint);
            circleStroke(canvas, shutter.centerX(), shutter.centerY(), dp(31), Color.argb(220, 255, 255, 255), dp(2));
            if (recording) {
                fillRound(canvas, rect(shutter.centerX() - dp(14), shutter.centerY() - dp(14), shutter.centerX() + dp(14), shutter.centerY() + dp(14)), dp(6), RECORD);
            } else {
                fillCircle(canvas, shutter.centerX(), shutter.centerY(), dp(22), RECORD);
            }
            addHotspot("shutter", shutter);

            RectF thumb = rect(w - m - dp(48), cy - dp(24), w - m, cy + dp(24));
            panel(canvas, thumb, dp(9), Color.rgb(16, 27, 31), HAIR_2);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(thumb.left, thumb.top, thumb.right, thumb.bottom, Color.rgb(42, 59, 68), Color.rgb(13, 21, 24), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(thumb, dp(9), dp(9), paint);
            paint.setShader(null);
            fillRound(canvas, rect(thumb.left, thumb.bottom - dp(14), thumb.right, thumb.bottom), dp(4), Color.argb(170, 0, 0, 0));
            label(canvas, "12:04", thumb.centerX(), thumb.bottom - dp(4), 8, INK_2, mono, Paint.Align.CENTER);
            addHotspot("mode-roll", thumb);
        }

        private void drawModeBottom(Canvas canvas) {
            float h = getHeight();
            float w = getWidth();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, h - dp(140), 0, h, Color.TRANSPARENT, Color.argb(210, 0, 0, 0), Shader.TileMode.CLAMP));
            canvas.drawRect(0, h - dp(140), w, h, paint);
            paint.setShader(null);
            drawModeSelector(canvas, h - dp(56));
        }

        private void drawMountBottom(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, h - dp(118), 0, h, Color.TRANSPARENT, Color.argb(205, 0, 0, 0), Shader.TileMode.CLAMP));
            canvas.drawRect(0, h - dp(118), w, h, paint);
            paint.setShader(null);
            String[] labels = {"扫描", "装机", "历史"};
            float total = dp(150);
            float start = w / 2f - total / 2f;
            for (int i = 0; i < labels.length; i++) {
                float x = start + i * total / 2f;
                label(canvas, labels[i], x, h - dp(36), 14, i == 1 ? INK : INK_3, cond, Paint.Align.CENTER);
                if (i == 1) {
                    fillCircle(canvas, x, h - dp(28), dp(2.5f), RECORD);
                }
            }
        }

        private void drawModeSelector(Canvas canvas, float baseline) {
            float w = getWidth();
            String[] labels = {"RECORD", "ROLL", "BODY", "NET"};
            Mode[] modes = {Mode.RECORD, Mode.ROLL, Mode.BODY, Mode.NET};
            float width = Math.min(w - dp(28), dp(304));
            float start = w / 2f - width / 2f;
            float step = width / 4f;
            for (int i = 0; i < labels.length; i++) {
                float x = start + step * i + step / 2f;
                boolean active = mode == modes[i];
                label(canvas, labels[i], x, baseline, 14, active ? INK : INK_3, cond, Paint.Align.CENTER);
                RectF hit = rect(start + i * step, baseline - dp(26), start + (i + 1) * step, baseline + dp(16));
                addHotspot("mode-" + labels[i].toLowerCase(Locale.US), hit);
                if (active) {
                    fillCircle(canvas, x, baseline + dp(8), dp(2.5f), RECORD);
                }
            }
        }

        private void drawFacts(Canvas canvas, RectF r, Fact[] facts) {
            panel(canvas, r, dp(9), GLASS, HAIR);
            float cell = r.width() / facts.length;
            for (int i = 0; i < facts.length; i++) {
                float left = r.left + cell * i;
                if (i > 0) {
                    line(canvas, left, r.top, left, r.bottom, HAIR, dp(1));
                }
                label(canvas, facts[i].key, left + cell / 2f, r.top + dp(16), 8.5f, INK_3, mono, Paint.Align.CENTER);
                label(canvas, facts[i].value, left + cell / 2f, r.top + dp(34), 11.5f, facts[i].color, mono, Paint.Align.CENTER);
            }
        }

        private void drawFilmCell(Canvas canvas, RectF r, String state, String len) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, Color.rgb(36, 49, 57), Color.rgb(11, 17, 20), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r, dp(7), dp(7), paint);
            paint.setShader(null);
            strokeRound(canvas, r, dp(7), HAIR, dp(1));
            if ("UNUSABLE".equals(state)) {
                hatchRound(canvas, r, dp(7), RECORD);
            }
            int stateColor = "USABLE".equals(state) ? PERMIT : ("UNUSABLE".equals(state) ? RECORD : CAUTION);
            RectF tag = rect(r.left + dp(4), r.top + dp(4), r.left + dp(70), r.top + dp(22));
            fillRound(canvas, tag, dp(3), Color.argb(170, 0, 0, 0));
            labelClip(canvas, state, tag.left + dp(4), tag.centerY() + dp(3), 7.5f, stateColor, mono, Paint.Align.LEFT, tag.width() - dp(8));
            label(canvas, len, r.right - dp(5), r.bottom - dp(5), 8.5f, INK_2, mono, Paint.Align.RIGHT);
        }

        private void drawLineItem(Canvas canvas, String title, String subtitle, String action, RectF r, boolean border) {
            if (border) {
                line(canvas, r.left, r.bottom, r.right, r.bottom, HAIR, dp(1));
            }
            labelClip(canvas, title, r.left, r.top + dp(22), 12.5f, INK, sansMedium, Paint.Align.LEFT, r.width() - dp(102));
            labelClip(canvas, subtitle, r.left, r.top + dp(39), 9.5f, INK_3, mono, Paint.Align.LEFT, r.width() - dp(102));
            if (!action.isEmpty()) {
                RectF pill = rect(r.right - dp(86), r.centerY() - dp(14), r.right, r.centerY() + dp(14));
                if ("route".equals(action)) {
                    statusChip(canvas, pill, action, Status.IDLE);
                } else {
                    pill(canvas, pill, "Join".equals(action) || "On".equals(action) || "Mount".equals(action) ? INK : Color.TRANSPARENT,
                            "Join".equals(action) || "On".equals(action) || "Mount".equals(action) ? VOID : INK, action);
                }
            }
        }

        private void drawKv(Canvas canvas, float x, float y, String[][] rows) {
            float keyW = dp(78);
            for (int i = 0; i < rows.length; i++) {
                float rowY = y + i * dp(24);
                label(canvas, rows[i][0].toUpperCase(Locale.US), x, rowY, 9.5f, INK_3, mono, Paint.Align.LEFT);
                labelClip(canvas, rows[i][1], x + keyW, rowY, 11, INK, mono, Paint.Align.LEFT, getWidth() - x - keyW - dp(12));
            }
        }

        private void drawSegment(Canvas canvas, RectF r, String[] labels, int active) {
            panel(canvas, r, dp(8), Color.TRANSPARENT, HAIR);
            float cell = r.width() / labels.length;
            for (int i = 0; i < labels.length; i++) {
                RectF seg = rect(r.left + i * cell, r.top, r.left + (i + 1) * cell, r.bottom);
                if (i == active) {
                    fillRound(canvas, inset(seg, dp(1), dp(1)), dp(7), SUNKEN);
                }
                if (i > 0) {
                    line(canvas, seg.left, r.top, seg.left, r.bottom, HAIR, dp(1));
                }
                labelClip(canvas, labels[i], seg.centerX(), seg.centerY() + dp(4), 9.5f, i == active ? INK : INK_3, mono, Paint.Align.CENTER, cell - dp(6));
            }
        }

        private void drawBand(Canvas canvas, RectF r, int color, String message, boolean redFault) {
            fillRound(canvas, r, dp(8), Color.argb(redFault ? 52 : 34, Color.red(color), Color.green(color), Color.blue(color)));
            hatchRound(canvas, r, dp(8), color);
            strokeRound(canvas, r, dp(8), Color.argb(112, Color.red(color), Color.green(color), Color.blue(color)), dp(1));
            labelClip(canvas, message, r.left + dp(9), r.centerY() + dp(4), 11, redFault ? Color.rgb(255, 185, 179) : Color.rgb(243, 213, 154), sans, Paint.Align.LEFT, r.width() - dp(18));
        }

        private void drawMeter(Canvas canvas, RectF r, float value, int color) {
            fillRound(canvas, r, dp(3), SUNKEN);
            fillRound(canvas, rect(r.left, r.top, r.left + r.width() * Math.max(0, Math.min(1, value)), r.bottom), dp(3), color);
        }

        private String updateVersionLabel(AppUpdateManager.State state) {
            if (state.manifest != null) {
                return state.manifest.version + "+" + state.manifest.versionCode;
            }
            return state.currentVersionName + "+" + state.currentBuildNumber;
        }

        private int updateVersionColor(AppUpdateManager.State state) {
            if (state.phase == AppUpdateManager.Phase.AVAILABLE || state.phase == AppUpdateManager.Phase.DOWNLOADING || state.phase == AppUpdateManager.Phase.INSTALLING) {
                return LIVE;
            }
            if (state.phase == AppUpdateManager.Phase.FAILED) {
                return CAUTION;
            }
            return INK;
        }

        private int updateMeterColor(AppUpdateManager.State state) {
            return state.phase == AppUpdateManager.Phase.FAILED ? CAUTION : LIVE;
        }

        private float updateProgress(AppUpdateManager.State state) {
            if (state.totalBytes <= 0) {
                return state.phase == AppUpdateManager.Phase.CURRENT ? 1f : 0f;
            }
            return (float) state.downloadedBytes / (float) state.totalBytes;
        }

        private String updateDetail(AppUpdateManager.State state) {
            if (state.phase == AppUpdateManager.Phase.DOWNLOADING || state.phase == AppUpdateManager.Phase.INSTALLING) {
                return formatBytes(state.downloadedBytes) + " / " + formatBytes(state.totalBytes) + " · 完成后校验 SHA-256";
            }
            if (state.phase == AppUpdateManager.Phase.AVAILABLE && state.manifest != null) {
                return formatBytes(state.manifest.apk.bytes) + " · 下载后校验 SHA-256 · 交系统安装器";
            }
            if (state.phase == AppUpdateManager.Phase.CURRENT) {
                return "当前 build " + state.currentBuildNumber + " · " + state.currentVersionName;
            }
            if (state.phase == AppUpdateManager.Phase.CHECKING) {
                return "读取 releases/latest/download/android-update.json";
            }
            if (state.phase == AppUpdateManager.Phase.FAILED) {
                return "可重新检查；APK 不会在校验失败后保留";
            }
            return "GitHub Releases latest manifest · HTTPS only";
        }

        private String formatBytes(long bytes) {
            if (bytes <= 0) {
                return "0 B";
            }
            double value = bytes;
            String[] units = {"B", "KiB", "MiB", "GiB"};
            int unit = 0;
            while (value >= 1024 && unit < units.length - 1) {
                value /= 1024;
                unit++;
            }
            return unit == 0
                    ? String.format(Locale.US, "%d %s", bytes, units[unit])
                    : String.format(Locale.US, "%.1f %s", value, units[unit]);
        }

        private void railButton(Canvas canvas, RectF r, String caption, int color, Icon icon) {
            panel(canvas, r, dp(9), GLASS, color == INK_2 ? HAIR : Color.argb(115, Color.red(color), Color.green(color), Color.blue(color)));
            float cx = r.centerX();
            float top = r.top + dp(10);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.6f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
            switch (icon) {
                case GRID:
                    RectF g = rect(cx - dp(8), top, cx + dp(8), top + dp(16));
                    canvas.drawRect(g, paint);
                    line(canvas, g.left + g.width() / 3f, g.top, g.left + g.width() / 3f, g.bottom, color, dp(1.2f));
                    line(canvas, g.left + 2 * g.width() / 3f, g.top, g.left + 2 * g.width() / 3f, g.bottom, color, dp(1.2f));
                    line(canvas, g.left, g.top + g.height() / 3f, g.right, g.top + g.height() / 3f, color, dp(1.2f));
                    line(canvas, g.left, g.top + 2 * g.height() / 3f, g.right, g.top + 2 * g.height() / 3f, color, dp(1.2f));
                    break;
                case FOCUS:
                    canvas.drawCircle(cx, top + dp(8), dp(8), paint);
                    line(canvas, cx, top, cx, top + dp(4), color, dp(1.2f));
                    line(canvas, cx, top + dp(12), cx, top + dp(16), color, dp(1.2f));
                    line(canvas, cx - dp(8), top + dp(8), cx - dp(4), top + dp(8), color, dp(1.2f));
                    line(canvas, cx + dp(4), top + dp(8), cx + dp(8), top + dp(8), color, dp(1.2f));
                    break;
                case PEAK:
                    Path peakPath = new Path();
                    peakPath.moveTo(cx - dp(10), top + dp(14));
                    peakPath.lineTo(cx - dp(4), top + dp(6));
                    peakPath.lineTo(cx + dp(2), top + dp(12));
                    peakPath.lineTo(cx + dp(7), top + dp(8));
                    peakPath.lineTo(cx + dp(12), top + dp(14));
                    canvas.drawPath(peakPath, paint);
                    break;
                case IMU:
                    for (int i = 0; i < 4; i++) {
                        float x = cx - dp(10) + i * dp(7);
                        line(canvas, x, top + dp(16), x, top + dp(5 + i * 3), color, dp(1.8f));
                    }
                    break;
                case BODY:
                    RectF b = rect(cx - dp(8), top + dp(2), cx + dp(8), top + dp(18));
                    canvas.drawRoundRect(b, dp(2), dp(2), paint);
                    line(canvas, b.left - dp(4), b.top + dp(5), b.left, b.top + dp(5), color, dp(1.2f));
                    line(canvas, b.right, b.top + dp(5), b.right + dp(4), b.top + dp(5), color, dp(1.2f));
                    break;
            }
            label(canvas, caption, cx, r.bottom - dp(7), 7.8f, color, mono, Paint.Align.CENTER);
        }

        private void statusChip(Canvas canvas, RectF r, String status, Status kind) {
            int color;
            int bg;
            switch (kind) {
                case RECORDING:
                    color = RECORD;
                    bg = Color.argb(36, 255, 59, 45);
                    break;
                case IDLE:
                    color = PERMIT;
                    bg = Color.TRANSPARENT;
                    break;
                case WARN:
                    color = CAUTION;
                    bg = Color.argb(28, 224, 160, 32);
                    break;
                case OFF:
                default:
                    color = INK_3;
                    bg = Color.TRANSPARENT;
                    break;
            }
            fillRound(canvas, r, dp(5), bg);
            if (kind == Status.WARN) {
                hatchRound(canvas, r, dp(5), CAUTION);
            }
            strokeRound(canvas, r, dp(5), color, dp(1));
            fillCircle(canvas, r.left + dp(10), r.centerY(), dp(3), color);
            labelClip(canvas, status, r.left + dp(19), r.centerY() + dp(3.5f), 9.5f, color, mono, Paint.Align.LEFT, r.width() - dp(24));
        }

        private void pill(Canvas canvas, RectF r, int fill, int color, String label) {
            fillRound(canvas, r, Math.min(r.height() / 2f, dp(100)), fill);
            strokeRound(canvas, r, Math.min(r.height() / 2f, dp(100)), fill == INK ? INK : HAIR_2, dp(1));
            labelClip(canvas, label, r.centerX(), r.centerY() + dp(3.5f), 9.5f, color, mono, Paint.Align.CENTER, r.width() - dp(8));
        }

        private void panel(Canvas canvas, RectF r, float radius, int fill, int stroke) {
            fillRound(canvas, r, radius, fill);
            strokeRound(canvas, r, radius, stroke, dp(1));
        }

        private void sheet(Canvas canvas, RectF r) {
            fillRound(canvas, r, dp(16), GLASS_STRONG);
            strokeRound(canvas, r, dp(16), HAIR_2, dp(1));
        }

        private void grab(Canvas canvas, float cx, float cy) {
            fillRound(canvas, rect(cx - dp(17), cy - dp(1.5f), cx + dp(17), cy + dp(1.5f)), dp(3), HAIR_2);
        }

        private void dim(Canvas canvas, int alpha) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(alpha, 0, 0, 0));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }

        private void smallCaps(Canvas canvas, String value, float x, float baseline, int color) {
            label(canvas, value, x, baseline, 9.5f, color, mono, Paint.Align.LEFT);
        }

        private void label(Canvas canvas, String value, float x, float baseline, float sizeSp, int color, Typeface typeface, Paint.Align align) {
            text.setShader(null);
            text.setStyle(Paint.Style.FILL);
            text.setColor(color);
            text.setTextSize(sp(sizeSp));
            text.setTypeface(typeface);
            text.setTextAlign(align);
            canvas.drawText(value, x, baseline, text);
        }

        private void labelClip(Canvas canvas, String value, float x, float baseline, float sizeSp, int color, Typeface typeface, Paint.Align align, float maxWidth) {
            text.setShader(null);
            text.setStyle(Paint.Style.FILL);
            text.setColor(color);
            text.setTextSize(sp(sizeSp));
            text.setTypeface(typeface);
            text.setTextAlign(align);
            canvas.drawText(ellipsize(value, maxWidth), x, baseline, text);
        }

        private String ellipsize(String value, float maxWidth) {
            if (text.measureText(value) <= maxWidth) {
                return value;
            }
            String marker = "…";
            float markerWidth = text.measureText(marker);
            int end = value.length();
            while (end > 0 && text.measureText(value, 0, end) + markerWidth > maxWidth) {
                end--;
            }
            return value.substring(0, Math.max(0, end)) + marker;
        }

        private void fillRound(Canvas canvas, RectF r, float radius, int color) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawRoundRect(r, radius, radius, paint);
        }

        private void strokeRound(Canvas canvas, RectF r, float radius, int color, float width) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            canvas.drawRoundRect(inset(r, width / 2f, width / 2f), radius, radius, paint);
        }

        private void fillCircle(Canvas canvas, float cx, float cy, float r, int color) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(cx, cy, r, paint);
        }

        private void circleStroke(Canvas canvas, float cx, float cy, float r, int color, float width) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            canvas.drawCircle(cx, cy, r, paint);
        }

        private void hatchRound(Canvas canvas, RectF r, float radius, int color) {
            Path path = new Path();
            path.addRoundRect(r, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(path);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb(52, Color.red(color), Color.green(color), Color.blue(color)));
            for (float x = r.left - r.height(); x < r.right + r.height(); x += dp(10)) {
                canvas.drawLine(x, r.bottom, x + r.height(), r.top, paint);
            }
            canvas.restoreToCount(save);
        }

        private void hatchCircle(Canvas canvas, float cx, float cy, float radius, int color) {
            int save = canvas.save();
            Path path = new Path();
            path.addCircle(cx, cy, radius, Path.Direction.CW);
            canvas.clipPath(path);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb(58, Color.red(color), Color.green(color), Color.blue(color)));
            for (float x = cx - radius * 2; x < cx + radius * 2; x += dp(8)) {
                canvas.drawLine(x, cy + radius, x + radius * 2, cy - radius, paint);
            }
            canvas.restoreToCount(save);
        }

        private void line(Canvas canvas, float x1, float y1, float x2, float y2, int color, float width) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(color);
            canvas.drawLine(x1, y1, x2, y2, paint);
        }

        private RectF rect(float left, float top, float right, float bottom) {
            return new RectF(left, top, right, bottom);
        }

        private RectF inset(RectF source, float dx, float dy) {
            return new RectF(source.left + dx, source.top + dy, source.right - dx, source.bottom - dy);
        }

        private void addHotspot(String action, RectF bounds) {
            hotspots.add(new Hotspot(action, new RectF(bounds)));
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        private float sp(float value) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
        }

        private enum Mode {
            RECORD,
            ROLL,
            BODY,
            NET
        }

        private enum Status {
            RECORDING,
            IDLE,
            WARN,
            OFF
        }

        private enum Icon {
            GRID,
            FOCUS,
            PEAK,
            IMU,
            BODY
        }

        private static final class Fact {
            final String key;
            final String value;
            final int color;

            Fact(String key, String value, int color) {
                this.key = key;
                this.value = value;
                this.color = color;
            }
        }

        private static final class Hotspot {
            final String action;
            final RectF bounds;

            Hotspot(String action, RectF bounds) {
                this.action = action;
                this.bounds = bounds;
            }
        }
    }
}
