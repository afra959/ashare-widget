package com.afra.wenwen;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
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
    private static final String KEY_LAST_DIAG = "last_diag";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean attemptsScheduled = false;
    private boolean failureReported = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!TARGET_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!isPending() || pendingText().isEmpty() || attemptsScheduled) return;

        attemptsScheduled = true;
        failureReported = false;

        handler.postDelayed(this::tryAutomate, 250L);
        handler.postDelayed(this::tryAutomate, 650L);
        handler.postDelayed(this::tryAutomate, 1100L);
        handler.postDelayed(this::tryAutomate, 1800L);
        handler.postDelayed(this::tryAutomate, 2800L);
        handler.postDelayed(this::tryAutomate, 4200L);
        handler.postDelayed(this::reportFailureIfNeeded, 5400L);
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        attemptsScheduled = false;
    }

    private void tryAutomate() {
        if (!isPending()) return;

        String pending = pendingText();
        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null) return;

        AccessibilityNodeInfo input = findBestInput(root);

        if (input == null) {
            AccessibilityNodeInfo inputContainer =
                    findLikelyInputContainer(root);

            if (inputContainer != null
                    && clickNodeOrParent(inputContainer)) {
                handler.postDelayed(this::tryAutomate, 260L);
            }
            return;
        }

        if (!writeText(input, pending)) return;

        handler.postDelayed(
                () -> trySend(pending),
                260L
        );
    }

    private boolean writeText(
            AccessibilityNodeInfo input,
            String pending
    ) {
        String current = safe(input.getText()).trim();

        if (pending.equals(current)) {
            return true;
        }

        try {
            input.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS
            );
        } catch (Exception ignored) {
        }

        if (setNodeText(input, pending)) {
            return true;
        }

        try {
            if (input.isFocused()) {
                return input.performAction(
                        AccessibilityNodeInfo.ACTION_PASTE
                );
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private void trySend(String pending) {
        if (!isPending()) return;

        AccessibilityNodeInfo root =
                getRootInActiveWindow();

        if (root == null) return;

        AccessibilityNodeInfo input =
                findBestInput(root);

        if (input == null) return;

        String current =
                safe(input.getText()).trim();

        if (!pending.equals(current)) {
            if (current.isEmpty()) finishSuccess();
            return;
        }

        AccessibilityNodeInfo semanticSend =
                findSemanticSendCandidate(root, input);

        if (semanticSend != null
                && clickNodeOrParent(semanticSend)) {
            handler.postDelayed(
                    () -> verifyAfterSend(pending),
                    650L
            );
            return;
        }

        // 只有在文字已经确认写入后，才允许使用几何兜底。
        AccessibilityNodeInfo safeGeometricSend =
                findSafeGeometricSendCandidate(root, input);

        if (safeGeometricSend != null
                && clickNodeOrParent(safeGeometricSend)) {
            handler.postDelayed(
                    () -> verifyAfterSend(pending),
                    650L
            );
        }
    }

    private void verifyAfterSend(String pending) {
        if (!isPending()) return;

        AccessibilityNodeInfo root =
                getRootInActiveWindow();

        if (root == null) return;

        AccessibilityNodeInfo input =
                findBestInput(root);

        if (input == null) {
            finishSuccess();
            return;
        }

        String current =
                safe(input.getText()).trim();

        if (!pending.equals(current)) {
            finishSuccess();
        }
    }

    private AccessibilityNodeInfo findBestInput(
            AccessibilityNodeInfo root
    ) {
        AccessibilityNodeInfo focused =
                root.findFocus(
                        AccessibilityNodeInfo.FOCUS_INPUT
                );

        if (focused != null
                && (focused.isEditable()
                || hasSetTextAction(focused))) {
            return focused;
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
            AccessibilityNodeInfo node =
                    queue.removeFirst();

            if (node.isVisibleToUser()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);

                String cls =
                        safe(node.getClassName())
                                .toLowerCase(Locale.ROOT);

                String joined = (
                        safe(node.getViewIdResourceName()) + " "
                                + safe(node.getText()) + " "
                                + safe(node.getHintText()) + " "
                                + safe(node.getContentDescription())
                ).toLowerCase(Locale.ROOT);

                int score = 0;

                if (node.isEditable()) score += 150;
                if (cls.contains("edittext")) score += 100;
                if (hasSetTextAction(node)) score += 80;
                if (node.isFocused()) score += 60;

                if (!r.isEmpty()) {
                    if (r.centerY() > screenHeight * 0.48f) score += 45;
                    if (r.width() > screenWidth * 0.32f) score += 25;
                    if (r.height() < screenHeight * 0.24f) score += 10;
                }

                if (joined.contains("input")
                        || joined.contains("edit")
                        || joined.contains("message")
                        || joined.contains("chat")
                        || joined.contains("ask")
                        || joined.contains("deepseek")
                        || joined.contains("发送消息")
                        || joined.contains("输入")
                        || joined.contains("提问")
                        || joined.contains("问点什么")
                        || joined.contains("有任何问题")) {
                    score += 40;
                }

                if ((node.isEditable()
                        || cls.contains("edittext")
                        || hasSetTextAction(node))
                        && score > bestScore) {
                    best = node;
                    bestScore = score;
                }
            }

            addChildren(queue, node);
        }

        return best;
    }

    private AccessibilityNodeInfo findLikelyInputContainer(
            AccessibilityNodeInfo root
    ) {
        ArrayDeque<AccessibilityNodeInfo> queue =
                new ArrayDeque<>();
        queue.add(root);

        AccessibilityNodeInfo semantic = null;
        int semanticScore = Integer.MIN_VALUE;

        AccessibilityNodeInfo geometric = null;
        int geometricScore = Integer.MIN_VALUE;

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

                String joined = (
                        safe(node.getViewIdResourceName()) + " "
                                + safe(node.getText()) + " "
                                + safe(node.getHintText()) + " "
                                + safe(node.getContentDescription())
                ).toLowerCase(Locale.ROOT);

                boolean actionable =
                        node.isClickable()
                                || node.isFocusable()
                                || hasClickableParent(node);

                if (actionable && !r.isEmpty()
                        && !containsExcludedControl(joined)) {

                    int s = 0;

                    if (joined.contains("input")
                            || joined.contains("message")
                            || joined.contains("chat")
                            || joined.contains("ask")
                            || joined.contains("deepseek")
                            || joined.contains("输入")
                            || joined.contains("提问")
                            || joined.contains("问点什么")
                            || joined.contains("有任何问题")) {
                        s += 180;
                    }

                    if (r.centerY() > screenHeight * 0.52f) s += 35;
                    if (r.width() > screenWidth * 0.38f) s += 35;
                    if (r.height() < screenHeight * 0.20f) s += 15;

                    if (s > semanticScore) {
                        semantic = node;
                        semanticScore = s;
                    }

                    int g = 0;

                    if (r.centerY() > screenHeight * 0.62f) g += 60;
                    if (r.width() > screenWidth * 0.45f) g += 70;
                    if (r.height() < screenHeight * 0.22f) g += 20;
                    if (r.left < screenWidth * 0.35f) g += 15;
                    if (r.right > screenWidth * 0.65f) g += 15;

                    if (g > geometricScore) {
                        geometric = node;
                        geometricScore = g;
                    }
                }
            }

            addChildren(queue, node);
        }

        if (semanticScore >= 180) return semantic;
        if (geometricScore >= 150) return geometric;

        return null;
    }

    private AccessibilityNodeInfo findSemanticSendCandidate(
            AccessibilityNodeInfo root,
            AccessibilityNodeInfo input
    ) {
        ArrayDeque<AccessibilityNodeInfo> queue =
                new ArrayDeque<>();
        queue.add(root);

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node =
                    queue.removeFirst();

            if (node != input && node.isVisibleToUser()) {
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
                        || "send".equalsIgnoreCase(desc)
                        || "发送消息".equals(text)
                        || "发送消息".equals(desc)) {
                    score += 280;
                }

                if (joined.contains("send")
                        || joined.contains("发送")
                        || joined.contains("submit")) {
                    score += 200;
                }

                if (containsExcludedControl(joined)) {
                    score -= 500;
                }

                if ((node.isClickable()
                        || hasClickableParent(node))
                        && score > bestScore) {
                    best = node;
                    bestScore = score;
                }
            }

            addChildren(queue, node);
        }

        return bestScore >= 200 ? best : null;
    }

    private AccessibilityNodeInfo findSafeGeometricSendCandidate(
            AccessibilityNodeInfo root,
            AccessibilityNodeInfo input
    ) {
        Rect inputBounds = new Rect();
        input.getBoundsInScreen(inputBounds);

        if (inputBounds.isEmpty()) return null;

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
                            score += 80;
                        }

                        // 必须位于输入区右侧或屏幕最右侧。
                        if (r.centerX() > inputBounds.centerX()) score += 55;
                        if (r.centerX() > screenWidth * 0.72f) score += 55;

                        // 发送按钮应是小控件，不接受整块容器。
                        if (r.width() < screenWidth * 0.22f
                                && r.height() < screenHeight * 0.14f) {
                            score += 45;
                        }

                        if (score > bestScore) {
                            best = node;
                            bestScore = score;
                        }
                    }
                }
            }

            addChildren(queue, node);
        }

        return bestScore >= 190 ? best : null;
    }

    private boolean setNodeText(
            AccessibilityNodeInfo node,
            String text
    ) {
        try {
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
                            70L
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

            if (parent.isClickable()) return true;
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

    private void reportFailureIfNeeded() {
        attemptsScheduled = false;

        if (!isPending() || failureReported) return;

        failureReported = true;

        AccessibilityNodeInfo root =
                getRootInActiveWindow();

        String diag =
                buildDiagnostic(root);

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_DIAG, diag)
                .apply();

        try {
            ClipboardManager cm =
                    (ClipboardManager)
                            getSystemService(
                                    Context.CLIPBOARD_SERVICE
                            );

            if (cm != null) {
                cm.setPrimaryClip(
                        ClipData.newPlainText(
                                "问问诊断",
                                diag
                        )
                );
            }
        } catch (Exception ignored) {
        }

        Toast.makeText(
                this,
                "自动输入/发送未完成；诊断信息已复制",
                Toast.LENGTH_LONG
        ).show();
    }

    private String buildDiagnostic(
            AccessibilityNodeInfo root
    ) {
        StringBuilder out =
                new StringBuilder();

        out.append(
                "========== 问问 v1.2 DeepSeek 诊断 ==========\n"
        );

        out.append("pending=")
                .append(pendingText())
                .append("\n");

        if (root == null) {
            out.append("root=null\n");
            return out.toString();
        }

        int screenHeight =
                getResources()
                        .getDisplayMetrics()
                        .heightPixels;

        ArrayDeque<AccessibilityNodeInfo> queue =
                new ArrayDeque<>();

        queue.add(root);

        int index = 0;

        while (!queue.isEmpty()
                && index < 180) {

            AccessibilityNodeInfo node =
                    queue.removeFirst();

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if (!r.isEmpty()
                    && r.bottom
                    >= screenHeight * 0.40f) {

                out.append("#")
                        .append(index++)
                        .append(" cls=")
                        .append(
                                safe(node.getClassName())
                        )
                        .append(" id=")
                        .append(
                                safe(
                                        node.getViewIdResourceName()
                                )
                        )
                        .append(" text=")
                        .append(
                                safe(node.getText())
                        )
                        .append(" hint=")
                        .append(
                                safe(node.getHintText())
                        )
                        .append(" desc=")
                        .append(
                                safe(
                                        node.getContentDescription()
                                )
                        )
                        .append(" editable=")
                        .append(node.isEditable())
                        .append(" focusable=")
                        .append(node.isFocusable())
                        .append(" focused=")
                        .append(node.isFocused())
                        .append(" clickable=")
                        .append(node.isClickable())
                        .append(" bounds=")
                        .append(r.toShortString())
                        .append("\n");
            }

            addChildren(queue, node);
        }

        out.append(
                "========== 诊断结束 ==========\n"
        );

        return out.toString();
    }

    private void addChildren(
            ArrayDeque<AccessibilityNodeInfo> queue,
            AccessibilityNodeInfo node
    ) {
        for (int i = 0;
             i < node.getChildCount();
             i++) {

            AccessibilityNodeInfo child =
                    node.getChild(i);

            if (child != null) {
                queue.addLast(child);
            }
        }
    }

    private boolean isPending() {
        long until =
                getSharedPreferences(
                        PREFS,
                        MODE_PRIVATE
                ).getLong(
                        KEY_UNTIL,
                        0L
                );

        return System.currentTimeMillis()
                <= until;
    }

    private String pendingText() {
        if (!isPending()) return "";

        String text =
                getSharedPreferences(
                        PREFS,
                        MODE_PRIVATE
                ).getString(
                        KEY_TEXT,
                        ""
                );

        return text == null
                ? ""
                : text.trim();
    }

    private void finishSuccess() {
        getSharedPreferences(
                PREFS,
                MODE_PRIVATE
        ).edit()
                .putString(KEY_TEXT, "")
                .putLong(KEY_UNTIL, 0L)
                .putString(KEY_LAST_DIAG, "")
                .apply();

        failureReported = false;
        attemptsScheduled = false;
        handler.removeCallbacksAndMessages(null);
    }

    private String safe(
            CharSequence value
    ) {
        return value == null
                ? ""
                : value.toString();
    }
}
