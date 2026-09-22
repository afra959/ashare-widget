package com.afra.wenwen;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
        if (event == null || event.getPackageName() == null) {
            return;
        }

        if (!TARGET_PACKAGE.contentEquals(event.getPackageName())) {
            return;
        }

        if (!isPending() || pendingText().isEmpty() || attemptsScheduled) {
            return;
        }

        attemptsScheduled = true;
        failureReported = false;

        handler.postDelayed(this::tryAutomate, 250L);
        handler.postDelayed(this::tryAutomate, 650L);
        handler.postDelayed(this::tryAutomate, 1150L);
        handler.postDelayed(this::tryAutomate, 1850L);
        handler.postDelayed(this::tryAutomate, 2800L);
        handler.postDelayed(this::tryAutomate, 4000L);

        handler.postDelayed(this::reportFailureIfNeeded, 5000L);
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

        handler.postDelayed(() -> trySend(pending), 220L);
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

        AccessibilityNodeInfo send = findSemanticSendCandidate(root, input);

        if (send == null) {
            return;
        }

        if (clickNodeOrParent(send)) {
            handler.postDelayed(
                    () -> verifyAfterSend(pending),
                    650L
            );
        }
    }

    private void verifyAfterSend(String pending) {
        if (!isPending()) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null) {
            // 页面在发送后重建时可能短暂没有 root；留给下一轮确认。
            return;
        }

        AccessibilityNodeInfo input = findBestInput(root);

        if (input == null) {
            finishSuccess();
            return;
        }

        String current = safe(input.getText()).trim();

        if (!pending.equals(current)) {
            finishSuccess();
        }
    }

    private AccessibilityNodeInfo findBestInput(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            if (node.isVisibleToUser()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);

                String cls = safe(node.getClassName()).toLowerCase(Locale.ROOT);
                String joined = (
                        safe(node.getViewIdResourceName()) + " "
                                + safe(node.getText()) + " "
                                + safe(node.getHintText()) + " "
                                + safe(node.getContentDescription())
                ).toLowerCase(Locale.ROOT);

                int score = 0;

                if (node.isEditable()) score += 120;
                if (cls.contains("edittext")) score += 80;
                if (hasSetTextAction(node)) score += 60;

                if (!r.isEmpty()) {
                    if (r.centerY() > screenHeight * 0.50f) score += 45;
                    if (r.width() > screenWidth * 0.35f) score += 20;
                    if (r.height() < screenHeight * 0.25f) score += 10;
                }

                if (joined.contains("input")
                        || joined.contains("edit")
                        || joined.contains("message")
                        || joined.contains("chat")
                        || joined.contains("发送消息")
                        || joined.contains("问点什么")
                        || joined.contains("有任何问题")) {
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
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        return best;
    }

    private AccessibilityNodeInfo findSemanticSendCandidate(
            AccessibilityNodeInfo root,
            AccessibilityNodeInfo input
    ) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        Rect inputBounds = new Rect();
        input.getBoundsInScreen(inputBounds);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            if (node != input && node.isVisibleToUser()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);

                String text = safe(node.getText()).trim();
                String desc = safe(node.getContentDescription()).trim();
                String id = safe(node.getViewIdResourceName());
                String joined = (id + " " + text + " " + desc)
                        .toLowerCase(Locale.ROOT);

                int score = 0;

                if ("发送".equals(text)
                        || "发送".equals(desc)
                        || "send".equalsIgnoreCase(text)
                        || "send".equalsIgnoreCase(desc)
                        || "发送消息".equals(text)
                        || "发送消息".equals(desc)) {
                    score += 260;
                }

                if (joined.contains("send")
                        || joined.contains("发送")
                        || joined.contains("submit")) {
                    score += 180;
                }

                if (containsExcludedControl(joined)) {
                    score -= 400;
                }

                if (!r.isEmpty() && !inputBounds.isEmpty()) {
                    int verticalDistance =
                            Math.abs(r.centerY() - inputBounds.centerY());

                    if (verticalDistance
                            <= Math.max(inputBounds.height(), screenHeight / 10)) {
                        score += 35;
                    }

                    if (r.centerX() > inputBounds.centerX()) score += 30;
                    if (r.centerX() > screenWidth * 0.62f) score += 20;

                    if (r.width() < screenWidth * 0.30f
                            && r.height() < screenHeight * 0.18f) {
                        score += 20;
                    }
                }

                if ((node.isClickable() || hasClickableParent(node))
                        && score > bestScore) {
                    best = node;
                    bestScore = score;
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        // 必须具有明确“发送”语义才允许点击，不使用纯坐标猜测。
        return bestScore >= 180 ? best : null;
    }

    private boolean setNodeText(AccessibilityNodeInfo node, String text) {
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
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

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        try {
            if (node.isClickable()
                    && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }

            AccessibilityNodeInfo parent = node.getParent();

            for (int depth = 0; parent != null && depth < 4; depth++) {
                if (parent.isClickable()
                        && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                parent = parent.getParent();
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private boolean hasSetTextAction(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo.AccessibilityAction action
                : node.getActionList()) {
            if (action.getId() == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                return true;
            }
        }

        return false;
    }

    private boolean hasClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();

        for (int depth = 0; parent != null && depth < 3; depth++) {
            if (parent.isClickable()) return true;
            parent = parent.getParent();
        }

        return false;
    }

    private boolean containsExcludedControl(String joined) {
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

        if (!isPending() || failureReported) {
            return;
        }

        failureReported = true;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        String diag = buildDiagnostic(root);

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_DIAG, diag)
                .apply();

        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

            if (cm != null) {
                cm.setPrimaryClip(
                        ClipData.newPlainText("问问诊断", diag)
                );
            }
        } catch (Exception ignored) {
        }

        Toast.makeText(
                this,
                "自动发送未确认；诊断信息已复制到剪贴板",
                Toast.LENGTH_LONG
        ).show();
    }

    private String buildDiagnostic(AccessibilityNodeInfo root) {
        StringBuilder out = new StringBuilder();

        out.append("========== 问问 v1.1 DeepSeek 诊断 ==========\n");
        out.append("pending=").append(pendingText()).append("\n");

        if (root == null) {
            out.append("root=null\n");
            return out.toString();
        }

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int index = 0;

        while (!queue.isEmpty() && index < 160) {
            AccessibilityNodeInfo node = queue.removeFirst();

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if (!r.isEmpty() && r.bottom >= screenHeight * 0.48f) {
                out.append("#").append(index++)
                        .append(" cls=").append(safe(node.getClassName()))
                        .append(" id=").append(safe(node.getViewIdResourceName()))
                        .append(" text=").append(safe(node.getText()))
                        .append(" hint=").append(safe(node.getHintText()))
                        .append(" desc=").append(safe(node.getContentDescription()))
                        .append(" editable=").append(node.isEditable())
                        .append(" clickable=").append(node.isClickable())
                        .append(" bounds=").append(r.toShortString())
                        .append("\n");
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        out.append("========== 诊断结束 ==========\n");
        return out.toString();
    }

    private boolean isPending() {
        long until = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_UNTIL, 0L);

        return System.currentTimeMillis() <= until;
    }

    private String pendingText() {
        if (!isPending()) return "";

        String text = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_TEXT, "");

        return text == null ? "" : text.trim();
    }

    private void finishSuccess() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_TEXT, "")
                .putLong(KEY_UNTIL, 0L)
                .putString(KEY_LAST_DIAG, "")
                .apply();

        failureReported = false;
        attemptsScheduled = false;
        handler.removeCallbacksAndMessages(null);
    }

    private String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
