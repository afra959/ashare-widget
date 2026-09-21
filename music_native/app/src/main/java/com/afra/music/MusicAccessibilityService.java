package com.afra.music;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

public class MusicAccessibilityService extends AccessibilityService {

    private static final String TARGET_PACKAGE = "com.netease.cloudmusic";
    private static final String PREFS = "music";
    private static final String KEY_PENDING = "pending_play_until";

    // 由用户 2026-09-21 实机截图标定。
    private static final float HOME_PLAY_X = 0.765f;
    private static final float HOME_PLAY_Y = 0.889f;
    private static final float PLAYER_PLAY_X = 0.500f;
    private static final float PLAYER_PLAY_Y = 0.862f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean attemptsScheduled = false;
    private boolean clickInFlight = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }

        if (!TARGET_PACKAGE.contentEquals(event.getPackageName())) {
            return;
        }

        long until = prefs().getLong(KEY_PENDING, 0L);

        if (System.currentTimeMillis() > until || attemptsScheduled) {
            return;
        }

        attemptsScheduled = true;

        // 首次冷启动和后台恢复速度不同，留出多轮机会。
        handler.postDelayed(this::attemptPlayback, 700L);
        handler.postDelayed(this::attemptPlayback, 1500L);
        handler.postDelayed(this::attemptPlayback, 2600L);
        handler.postDelayed(this::attemptPlayback, 4000L);
        handler.postDelayed(this::attemptPlayback, 6000L);

        handler.postDelayed(() -> attemptsScheduled = false, 6800L);
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        attemptsScheduled = false;
        clickInFlight = false;
    }

    private void attemptPlayback() {
        if (!isPending()) {
            return;
        }

        if (isMusicActive()) {
            finishSuccess();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null) {
            return;
        }

        // 如果无障碍树已经明确暴露“暂停”，说明正在播放。
        if (uiSaysPlaying(root)) {
            finishSuccess();
            return;
        }

        if (clickInFlight) {
            return;
        }

        // 先尝试真正的无障碍播放节点；网易云某些版本会暴露，某些版本不会。
        AccessibilityNodeInfo directPlayNode = findExactPlayNode(root);

        if (directPlayNode != null && clickNodeOrParent(directPlayNode)) {
            beginVerification();
            return;
        }

        // 控件语义没有暴露时，按用户实机界面做坐标兜底。
        boolean homeScreen = hasBottomHomeTab(root);

        if (homeScreen) {
            clickByRatio(HOME_PLAY_X, HOME_PLAY_Y);
        } else {
            clickByRatio(PLAYER_PLAY_X, PLAYER_PLAY_Y);
        }

        beginVerification();
    }

    private void beginVerification() {
        clickInFlight = true;

        handler.postDelayed(() -> {
            if (isMusicActive()) {
                finishSuccess();
                return;
            }

            AccessibilityNodeInfo verifyRoot = getRootInActiveWindow();

            if (verifyRoot != null && uiSaysPlaying(verifyRoot)) {
                finishSuccess();
                return;
            }

            // 本轮未触发，允许后面的定时尝试再次点击。
            clickInFlight = false;
        }, 650L);
    }

    private boolean isPending() {
        long until = prefs().getLong(KEY_PENDING, 0L);
        return System.currentTimeMillis() <= until;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean isMusicActive() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        return audioManager != null && audioManager.isMusicActive();
    }

    private void finishSuccess() {
        prefs().edit().putLong(KEY_PENDING, 0L).apply();
        handler.removeCallbacksAndMessages(null);
        attemptsScheduled = false;
        clickInFlight = false;
    }

    private boolean hasBottomHomeTab(AccessibilityNodeInfo root) {
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("首页");
            int screenHeight = getResources().getDisplayMetrics().heightPixels;

            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) {
                    continue;
                }

                Rect r = new Rect();
                node.getBoundsInScreen(r);

                if (!r.isEmpty() && r.centerY() > screenHeight * 0.82f) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private boolean uiSaysPlaying(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            String id = safe(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            String text = safe(node.getText()).trim();
            String desc = safe(node.getContentDescription()).trim();

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            boolean lowerArea = !r.isEmpty() && r.centerY() > screenHeight * 0.65f;

            if (lowerArea) {
                if ("暂停".equals(text)
                        || "暂停".equals(desc)
                        || id.contains("pause")) {
                    return true;
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.addLast(child);
                }
            }
        }

        return false;
    }

    private AccessibilityNodeInfo findExactPlayNode(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            String id = safe(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            String text = safe(node.getText()).trim();
            String desc = safe(node.getContentDescription()).trim();

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            boolean lowerArea = !r.isEmpty() && r.centerY() > screenHeight * 0.65f;

            if (lowerArea) {
                boolean exactPlay =
                        "播放".equals(text)
                                || "播放".equals(desc)
                                || "继续播放".equals(text)
                                || "继续播放".equals(desc);

                boolean safePlayId =
                        id.contains("play")
                                && !id.contains("playlist")
                                && !id.contains("display");

                if ((exactPlay || safePlayId) && isSmallEnough(node)) {
                    return node;
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.addLast(child);
                }
            }
        }

        return null;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        if (node.isClickable()
                && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }

        AccessibilityNodeInfo parent = node.getParent();

        for (int depth = 0; parent != null && depth < 3; depth++) {
            if (parent.isClickable()
                    && isSmallEnough(parent)
                    && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }

            parent = parent.getParent();
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        if (bounds.isEmpty()) {
            return false;
        }

        return clickAt(bounds.exactCenterX(), bounds.exactCenterY());
    }

    private boolean clickByRatio(float xRatio, float yRatio) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;

        return clickAt(width * xRatio, height * yRatio);
    }

    private boolean clickAt(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 80L);

        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();

        return dispatchGesture(gesture, null, null);
    }

    private boolean isSmallEnough(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);

        if (r.isEmpty()) {
            return false;
        }

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        return r.width() <= screenWidth * 0.45f
                && r.height() <= screenHeight * 0.20f;
    }

    private String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
