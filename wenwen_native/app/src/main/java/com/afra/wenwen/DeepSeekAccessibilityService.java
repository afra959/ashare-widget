package com.afra.wenwen;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Locale;

public class DeepSeekAccessibilityService extends AccessibilityService {

    private static final String TARGET_PACKAGE = "com.deepseek.chat";
    private static final String PREFS = "wenwen";
    private static final String KEY_TEXT = "pending_text";
    private static final String KEY_UNTIL = "pending_until";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean attemptsScheduled = false;
    private boolean warned = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }

        if (!TARGET_PACKAGE.contentEquals(event.getPackageName())) {
            return;
        }

        String pending = pendingText();

        if (pending.isEmpty() || !isPending()) {
            return;
        }

        if (attemptsScheduled) {
            return;
        }

        attemptsScheduled = true;

        handler.postDelayed(this::tryAutomate, 250L);
        handler.postDelayed(this::tryAutomate, 650L);
        handler.postDelayed(this::tryAutomate, 1150L);
        handler.postDelayed(this::tryAutomate, 1850L);
        handler.postDelayed(this::tryAutomate, 2800L);
        handler.postDelayed(this::tryAutomate, 4200L);

        handler.postDelayed(() -> {
            attemptsScheduled = false;

            if (isPending() && !warned) {
                warned = true;
                Toast.makeText(
                        this,
                        "问题已填入/复制，但未确认自动发送；如仍在输入框，请手动点发送",
                        Toast.LENGTH_LONG
                ).show();
            }
        }, 5200L);
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        attemptsScheduled = false;
    }

    private void tryAutomate() {
        if (!isPending()) {
            return;
        }

        String pending = pendingText();

        if (pending.isEmpty()) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null) {
            return;
        }

        AccessibilityNodeInfo input = findBestInput(root);

        if (input == null) {
            return;
        }

        String current = safe(input.getText()).trim();

        if (!pending.equals(current)) {
            if (!setNodeText(input, pending)) {
                return;
            }
        }

        handler.postDelayed(() -> trySend(pending), 260L);
    }

    private void trySend(String pending) {
        if (!isPending()) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null) {
            return;
        }

        AccessibilityNodeInfo input = findBestInput(root);

        if (input == null) {
            return;
        }

        String current = safe(input.getText()).trim();

        if (!pending.equals(current)) {
            if (current.isEmpty()) {
                finishSuccess();
            }
            return;
        }

        AccessibilityNodeInfo strongSend =
                findStrongSendCandidate(root, input);

        if (strongSend != null && clickNodeOrParent(strongSend)) {
            handler.postDelayed(
                    () -> verifyAfterSend(pending, 1),
                    550L
            );
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                boolean imeResult = input.performAction(
                        AccessibilityNodeInfo.AccessibilityAction
                                .ACTION_IME_ENTER
                                .getId()
                );

                if (imeResult) {
                    handler.postDelayed(
                            () -> verifyAfterSend(pending, 2),
                            550L
                    );
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        AccessibilityNodeInfo geometric =
                findGeometricSendCandidate(root, input);

        if (geometric != null && clickNodeOrParent(geometric)) {
            handler.postDelayed(
                    () -> verifyAfterSend(pending, 3),
                    550L
            );
        }
    }

    private void verifyAfterSend(String pending, int stage) {
        if (!isPending()) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null) {
            return;
        }

        AccessibilityNodeInfo input = findBestInput(root);

        if (input == null) {
            // DeepSeek 发送后页面重组时，输入节点短暂消失也视为高度可能成功。
            finishSuccess();
            return;
        }

        String current = safe(input.getText()).trim();

        if (!pending.equals(current)) {
            finishSuccess();
            return;
        }

        // 保持 pending，后续定时轮次会继续尝试其他发送路径。
    }

    private AccessibilityNodeInfo findBestInput(
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
            AccessibilityNodeInfo node = queue.removeFirst();

            if (node.isVisibleToUser()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);

                String cls = safe(node.getClassName())
                        .toLowerCase(Locale.ROOT);

                String joined = (
                        safe(node.getViewIdResourceName()) + " "
                                + safe(node.getText()) + " "
                                + safe(node.getHintText()) + " "
                                + safe(node.getContentDescription())
                ).toLowerCase(Locale.ROOT);

                int score = 0;

                if (node.isEditable()) {
                    score += 120;
                }

                if (cls.contains("edittext")) {
                    score += 80;
                }

                if (hasSetTextAction(node)) {
                    score += 60;
                }

                if (!r.isEmpty()) {
                    if (r.centerY() > screenHeight * 0.52f) {
                        score += 45;
                    }

                    if (r.width() > screenWidth * 0.35f) {
                        score += 20;
                    }

                    if (r.height() < screenHeight * 0.22f) {
                        score += 10;
                    }
                }

                if (joined.contains("input")
                        || joined.contains("edit")
                        || joined.contains("message")
                        || joined.contains("chat")
                        || joined.contains("发送消息")
                        || joined.contains("问点什么")) {
                    score += 30;
                }

                if ((node.isEditable()
                        || cls.contains("edittext")
                        || hasSetTextAction(node))
                        && score > bestScore) {
                    best = node;
                    bestScore = score;
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

    private AccessibilityNodeInfo findStrongSendCandidate(
            AccessibilityNodeInfo root,
            AccessibilityNodeInfo input
    ) {
        ArrayDeque<AccessibilityNodeInfo> queue =
                new ArrayDeque<>();

        queue.add(root);

        AccessibilityNodeInfo best = null;
        int bestScore = 0;

        int screenWidth =
                getResources().getDisplayMetrics().widthPixels;

        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            if (node != input && node.isVisibleToUser()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);

                String text = safe(node.getText()).trim();
                String desc = safe(node.getContentDescription()).trim();
                String id = safe(node.getViewIdResourceName());

                String joined =
                        (id + " " + text + " " + desc)
                                .toLowerCase(Locale.ROOT);

                int score = 0;

                if ("发送".equals(text)
                        || "发送".equals(desc)
                        || "send".equalsIgnoreCase(text)
                        || "send".equalsIgnoreCase(desc)) {
                    score += 220;
                }

                if (joined.contains("发送")
                        || joined.contains("send")) {
                    score += 150;
                }

                if (id.toLowerCase(Locale.ROOT).contains("send")) {
                    score += 90;
                }

                if (containsExcludedControl(joined)) {
                    score -= 250;
                }

                if (!r.isEmpty()) {
                    if (r.centerY() > screenHeight * 0.52f) {
                        score += 25;
                    }

                    if (r.centerX() > screenWidth * 0.60f) {
                        score += 25;
                    }

                    if (r.width() < screenWidth * 0.28f
                            && r.height() < screenHeight * 0.16f) {
                        score += 20;
                    }
                }

                if ((node.isClickable()
                        || hasClickableParent(node))
                        && score > bestScore) {
                    best = node;
                    bestScore = score;
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

        return bestScore >= 150 ? best : null;
    }

    private AccessibilityNodeInfo findGeometricSendCandidate(
            AccessibilityNodeInfo root,
            AccessibilityNodeInfo input
    ) {
        Rect inputBounds = new Rect();
        input.getBoundsInScreen(inputBounds);

        if (inputBounds.isEmpty()) {
            return null;
        }

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
            AccessibilityNodeInfo node = queue.removeFirst();

            if (node != input
                    && node.isVisibleToUser()
                    && (node.isClickable()
                    || hasClickableParent(node))) {

                Rect r = new Rect();
                node.getBoundsInScreen(r);

                if (!r.isEmpty()) {
                    String joined = (
                            safe(node.getViewIdResourceName()) + " "
                                    + safe(node.getText()) + " "
                                    + safe(node.getContentDescription())
                    ).toLowerCase(Locale.ROOT);

                    if (!containsExcludedControl(joined)) {
                        int verticalDistance =
                                Math.abs(
                                        r.centerY()
                                                - inputBounds.centerY()
                                );

                        int score = 0;

                        if (verticalDistance
                                <= Math.max(
                                        inputBounds.height(),
                                        screenHeight / 12
                                )) {
                            score += 70;
                        }

                        if (r.centerX()
                                > inputBounds.centerX()) {
                            score += 45;
                        }

                        if (r.centerX()
                                > screenWidth * 0.68f) {
                            score += 35;
                        }

                        if (r.width()
                                < screenWidth * 0.24f
                                && r.height()
                                < screenHeight * 0.14f) {
                            score += 30;
                        }

                        // 在同一水平带中越靠右，越像发送按钮。
                        score += (int) (
                                30f
                                        * r.centerX()
                                        / Math.max(
                                        1,
                                        screenWidth
                                )
                        );

                        if (score > bestScore) {
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

        return bestScore >= 115 ? best : null;
    }

    private boolean setNodeText(
            AccessibilityNodeInfo node,
            String text
    ) {
        try {
            node.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS
            );

            Bundle args = new Bundle();

            args.putCharSequence(
                    AccessibilityNodeInfo
                            .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
            );

            return node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args
            );
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean clickNodeOrParent(
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

            if (!r.isEmpty()) {
                return clickAt(
                        r.exactCenterX(),
                        r.exactCenterY()
                );
            }

        } catch (Exception ignored) {
        }

        return false;
    }

    private boolean clickAt(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(
                            path,
                            0L,
                            80L
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

    private boolean hasSetTextAction(
            AccessibilityNodeInfo node
    ) {
        for (AccessibilityNodeInfo.AccessibilityAction action
                : node.getActionList()) {
            if (action.getId()
                    == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                return true;
            }
        }

        return false;
    }

    private boolean hasClickableParent(
            AccessibilityNodeInfo node
    ) {
        AccessibilityNodeInfo parent =
                node.getParent();

        for (int depth = 0;
             parent != null && depth < 3;
             depth++) {

            if (parent.isClickable()) {
                return true;
            }

            parent = parent.getParent();
        }

        return false;
    }

    private boolean containsExcludedControl(
            String joined
    ) {
        return joined.contains("mic")
                || joined.contains("voice")
                || joined.contains("语音")
                || joined.contains("麦克风")
                || joined.contains("attach")
                || joined.contains("附件")
                || joined.contains("image")
                || joined.contains("图片")
                || joined.contains("camera")
                || joined.contains("相机")
                || joined.contains("upload")
                || joined.contains("上传")
                || joined.contains("share")
                || joined.contains("分享")
                || joined.contains("feedback")
                || joined.contains("反馈")
                || joined.contains("retry")
                || joined.contains("重新生成")
                || joined.contains("stop")
                || joined.contains("停止");
    }

    private boolean isPending() {
        long until = getSharedPreferences(
                PREFS,
                MODE_PRIVATE
        ).getLong(KEY_UNTIL, 0L);

        return System.currentTimeMillis() <= until;
    }

    private String pendingText() {
        if (!isPending()) {
            return "";
        }

        String text = getSharedPreferences(
                PREFS,
                MODE_PRIVATE
        ).getString(KEY_TEXT, "");

        return text == null ? "" : text.trim();
    }

    private void finishSuccess() {
        getSharedPreferences(
                PREFS,
                MODE_PRIVATE
        ).edit()
                .putString(KEY_TEXT, "")
                .putLong(KEY_UNTIL, 0L)
                .apply();

        warned = false;
        attemptsScheduled = false;
        handler.removeCallbacksAndMessages(null);
    }

    private String safe(CharSequence value) {
        return value == null
                ? ""
                : value.toString();
    }
}
