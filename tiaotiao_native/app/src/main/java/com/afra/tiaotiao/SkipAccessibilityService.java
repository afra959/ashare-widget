package com.afra.tiaotiao;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SkipAccessibilityService extends AccessibilityService {

    private static final String PREFS = "tiaotiao";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_LOG = "last_log";

    private static final long SCAN_WINDOW_MS = 8000L;

    private static final Set<String> TARGETS = new HashSet<>();
    private static final Map<String, String> LABELS = new HashMap<>();

    static {
        TARGETS.add("com.douban.frodo");
        TARGETS.add("com.xingin.xhs");
        TARGETS.add("com.taobao.taobao");
        TARGETS.add("com.sina.weibo");
        TARGETS.add("app.podcast.cosmos");
        TARGETS.add("com.netease.cloudmusic");

        LABELS.put("com.douban.frodo", "豆瓣");
        LABELS.put("com.xingin.xhs", "小红书");
        LABELS.put("com.taobao.taobao", "淘宝");
        LABELS.put("com.sina.weibo", "微博");
        LABELS.put("app.podcast.cosmos", "小宇宙");
        LABELS.put("com.netease.cloudmusic", "网易云音乐");
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String lastForegroundPackage = "";
    private String scanningPackage = "";
    private long scanUntil = 0L;
    private long scanStartedAt = 0L;

    private final Runnable scanRunnable = this::scanOnce;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        writeLog("跳跳服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }

        if (event.getEventType()
                != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        String pkg = event.getPackageName().toString();

        if (pkg.equals(lastForegroundPackage)) {
            return;
        }

        String previous = lastForegroundPackage;
        lastForegroundPackage = pkg;

        if (!TARGETS.contains(pkg)) {
            if (!pkg.equals(getPackageName())) {
                stopScan();
            }
            return;
        }

        if (!isMasterEnabled()) {
            return;
        }

        // 只有真正从其他包切进目标 App 时才开始一次检测窗口。
        if (!pkg.equals(previous)) {
            startScan(pkg);
        }
    }

    @Override
    public void onInterrupt() {
        stopScan();
    }

    @Override
    public void onDestroy() {
        stopScan();
        super.onDestroy();
    }

    private void startScan(String pkg) {
        handler.removeCallbacks(scanRunnable);

        scanningPackage = pkg;
        scanStartedAt = SystemClock.uptimeMillis();
        scanUntil = scanStartedAt + SCAN_WINDOW_MS;

        writeLog("开始检测：" + label(pkg));

        handler.postDelayed(scanRunnable, 90L);
    }

    private void stopScan() {
        handler.removeCallbacks(scanRunnable);
        scanningPackage = "";
        scanUntil = 0L;
        scanStartedAt = 0L;
    }

    private void scanOnce() {
        if (!isMasterEnabled()
                || scanningPackage.isEmpty()
                || SystemClock.uptimeMillis() > scanUntil) {

            if (!scanningPackage.isEmpty()) {
                writeLog("未发现可安全点击的开屏跳过按钮：" + label(scanningPackage));
            }

            stopScan();
            return;
        }

        PowerManager pm =
                (PowerManager) getSystemService(POWER_SERVICE);

        if (pm != null && !pm.isInteractive()) {
            stopScan();
            return;
        }

        AccessibilityNodeInfo root =
                getRootInActiveWindow();

        if (root != null
                && root.getPackageName() != null
                && scanningPackage.contentEquals(root.getPackageName())) {

            AccessibilityNodeInfo candidate =
                    findSkipCandidate(root);

            if (candidate != null) {
                String description = describe(candidate);

                if (clickCandidate(candidate)) {
                    writeLog(
                            "已跳过：" + label(scanningPackage)
                                    + "\n命中：" + description
                    );
                    stopScan();
                    return;
                }
            }
        }

        long elapsed =
                SystemClock.uptimeMillis() - scanStartedAt;

        long nextDelay = elapsed < 2200L ? 170L : 320L;

        handler.postDelayed(scanRunnable, nextDelay);
    }

    private AccessibilityNodeInfo findSkipCandidate(
            AccessibilityNodeInfo root
    ) {
        ArrayDeque<AccessibilityNodeInfo> queue =
                new ArrayDeque<>();

        queue.add(root);

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        int screenWidth =
                getResources().getDisplayMetrics().widthPixels;

        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node =
                    queue.removeFirst();

            if (node.isVisibleToUser()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);

                if (!r.isEmpty()) {
                    String text = safe(node.getText()).trim();
                    String desc = safe(node.getContentDescription()).trim();
                    String id = safe(node.getViewIdResourceName()).trim();

                    String textLower = text.toLowerCase(Locale.ROOT);
                    String descLower = desc.toLowerCase(Locale.ROOT);
                    String idLower = id.toLowerCase(Locale.ROOT);

                    String joined =
                            textLower + " "
                                    + descLower + " "
                                    + idLower;

                    boolean explicitSkip =
                            containsSkipWord(textLower)
                                    || containsSkipWord(descLower);

                    boolean explicitAdClose =
                            joined.contains("关闭广告")
                                    || joined.contains("关闭开屏")
                                    || joined.contains("关闭推广")
                                    || joined.contains("close ad")
                                    || joined.contains("close_ad")
                                    || joined.contains("ad close");

                    boolean skipId =
                            idLower.contains("skip");

                    boolean splashCloseId =
                            idLower.contains("splash")
                                    && idLower.contains("close");

                    boolean adCloseId =
                            idLower.contains("ad")
                                    && idLower.contains("close");

                    if (explicitSkip
                            || explicitAdClose
                            || skipId
                            || splashCloseId
                            || adCloseId) {

                        int score = 0;

                        if (explicitSkip) score += 320;
                        if (explicitAdClose) score += 300;
                        if (skipId) score += 260;
                        if (splashCloseId) score += 240;
                        if (adCloseId) score += 220;

                        boolean smallEnough =
                                r.width() <= screenWidth * 0.58f
                                        && r.height() <= screenHeight * 0.24f;

                        boolean upperArea =
                                r.centerY() <= screenHeight * 0.58f;

                        boolean rightArea =
                                r.centerX() >= screenWidth * 0.48f;

                        if (smallEnough) score += 45;
                        else score -= 100;

                        if (upperArea) score += 35;
                        if (rightArea) score += 20;

                        if (node.isClickable()
                                || hasClickableParent(node)) {
                            score += 35;
                        }

                        // 明确文字“跳过”可以稍宽松；
                        // 纯 id 规则必须同时满足尺寸和位置约束。
                        boolean safeCandidate =
                                explicitSkip
                                        || explicitAdClose
                                        || (smallEnough
                                        && upperArea
                                        && (skipId
                                        || splashCloseId
                                        || adCloseId));

                        if (safeCandidate && score > bestScore) {
                            best = node;
                            bestScore = score;
                        }
                    }
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child =
                        node.getChild(i);

                if (child != null) {
                    queue.addLast(child);
                }
            }
        }

        return best;
    }

    private boolean containsSkipWord(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        String v = value.trim().toLowerCase(Locale.ROOT);

        return v.contains("跳过")
                || v.equals("skip")
                || v.startsWith("skip ")
                || v.endsWith(" skip");
    }

    private boolean clickCandidate(
            AccessibilityNodeInfo node
    ) {
        try {
            if (node.isClickable()
                    && node.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
            )) {
                return true;
            }

            AccessibilityNodeInfo parent =
                    node.getParent();

            for (int depth = 0;
                 parent != null && depth < 4;
                 depth++) {

                if (parent.isClickable()
                        && parent.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                )) {
                    return true;
                }

                parent = parent.getParent();
            }

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if (r.isEmpty()) {
                return false;
            }

            Path path = new Path();
            path.moveTo(
                    r.exactCenterX(),
                    r.exactCenterY()
            );

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(
                            path,
                            0L,
                            55L
                    );

            GestureDescription gesture =
                    new GestureDescription.Builder()
                            .addStroke(stroke)
                            .build();

            return dispatchGesture(
                    gesture,
                    null,
                    null
            );

        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasClickableParent(
            AccessibilityNodeInfo node
    ) {
        AccessibilityNodeInfo parent =
                node.getParent();

        for (int depth = 0;
             parent != null && depth < 4;
             depth++) {

            if (parent.isClickable()) {
                return true;
            }

            parent = parent.getParent();
        }

        return false;
    }

    private String describe(
            AccessibilityNodeInfo node
    ) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);

        return "text=" + safe(node.getText())
                + " desc=" + safe(node.getContentDescription())
                + " id=" + safe(node.getViewIdResourceName())
                + " bounds=" + r.toShortString();
    }

    private boolean isMasterEnabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    private String label(String pkg) {
        String label = LABELS.get(pkg);
        return label == null ? pkg : label;
    }

    private void writeLog(String message) {
        String time =
                new SimpleDateFormat(
                        "MM-dd HH:mm:ss",
                        Locale.getDefault()
                ).format(new Date());

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(
                        KEY_LAST_LOG,
                        time + "\n" + message
                )
                .apply();
    }

    private String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
