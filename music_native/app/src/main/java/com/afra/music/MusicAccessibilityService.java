package com.afra.music;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;

public class MusicAccessibilityService extends AccessibilityService {

    private static final String TARGET_PACKAGE = "com.netease.cloudmusic";
    private static final String PREFS = "music";
    private static final String KEY_PENDING = "pending_play_until";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean attemptsScheduled = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }

        if (!TARGET_PACKAGE.contentEquals(event.getPackageName())) {
            return;
        }

        long until = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_PENDING, 0L);

        if (System.currentTimeMillis() > until || attemptsScheduled) {
            return;
        }

        attemptsScheduled = true;
        handler.postDelayed(this::tryPlay, 250L);
        handler.postDelayed(this::tryPlay, 700L);
        handler.postDelayed(this::tryPlay, 1400L);
        handler.postDelayed(this::tryPlay, 2300L);
        handler.postDelayed(this::tryPlay, 3600L);
        handler.postDelayed(() -> attemptsScheduled = false, 4300L);
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        attemptsScheduled = false;
    }

    private void tryPlay() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (System.currentTimeMillis() > prefs.getLong(KEY_PENDING, 0L)) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }

        SearchResult result = findPlaybackControl(root);

        if (result.alreadyPlaying) {
            prefs.edit().putLong(KEY_PENDING, 0L).apply();
            handler.removeCallbacksAndMessages(null);
            attemptsScheduled = false;
            return;
        }

        if (result.node != null && clickCandidate(result.node)) {
            prefs.edit().putLong(KEY_PENDING, 0L).apply();
            handler.removeCallbacksAndMessages(null);
            attemptsScheduled = false;
        }
    }

    private SearchResult findPlaybackControl(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        AccessibilityNodeInfo fallback = null;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            String id = safe(node.getViewIdResourceName());
            String text = safe(node.getText());
            String desc = safe(node.getContentDescription());

            String idLower = id.toLowerCase(Locale.ROOT);
            String textTrim = text.trim();
            String descTrim = desc.trim();

            boolean small = isSmallEnough(node);

            if (small) {
                boolean saysPause =
                        "暂停".equals(textTrim)
                                || "暂停".equals(descTrim)
                                || idLower.contains("pause");

                if (saysPause) {
                    return new SearchResult(null, true);
                }

                boolean exactPlayText =
                        "播放".equals(textTrim)
                                || "播放".equals(descTrim)
                                || "继续播放".equals(textTrim)
                                || "继续播放".equals(descTrim)
                                || "继续".equals(textTrim)
                                || "继续".equals(descTrim);

                boolean playId =
                        idLower.contains("play")
                                && !idLower.contains("playlist");

                if (playId || exactPlayText) {
                    if (playId && !id.isEmpty()) {
                        return new SearchResult(node, false);
                    }
                    if (fallback == null) {
                        fallback = node;
                    }
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.addLast(child);
                }
            }
        }

        return new SearchResult(fallback, false);
    }

    private boolean clickCandidate(AccessibilityNodeInfo node) {
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

        Path path = new Path();
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());

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

    private static class SearchResult {
        final AccessibilityNodeInfo node;
        final boolean alreadyPlaying;

        SearchResult(AccessibilityNodeInfo node, boolean alreadyPlaying) {
            this.node = node;
            this.alreadyPlaying = alreadyPlaying;
        }
    }
}
