package com.afra.wenwen;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Locale;

public class DeepSeekAccessibilityService extends AccessibilityService {

    public static volatile DeepSeekAccessibilityService instance;

    private static final String TARGET_PACKAGE = "com.deepseek.chat";
    private static final String PREFS = "wenwen";
    private static final String KEY_TEXT = "pending_text";
    private static final String KEY_UNTIL = "pending_until";
    private static final String KEY_LAST_DIAG = "last_diag";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String activeText = "";
    private boolean automationRunning = false;
    private boolean shareIntentSupported = false;

    private WindowManager windowManager;
    private android.view.View diagnosticOverlay;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // v1.3 不再依赖事件触发自动化。
        // 若任务正在进行，事件只用于加速下一次主动检查。
        if (!automationRunning) return;

        if (event != null
                && event.getPackageName() != null
                && TARGET_PACKAGE.contentEquals(event.getPackageName())) {
            handler.removeCallbacks(pollRunnable);
            handler.postDelayed(pollRunnable, 120L);
        }
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        automationRunning = false;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        hideDiagnosticOverlay();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public void beginAutomation(String text) {
        String clean = text == null ? "" : text.trim();

        if (clean.isEmpty()) return;

        handler.removeCallbacksAndMessages(null);
        hideDiagnosticOverlay();

        activeText = clean;
        automationRunning = true;
        shareIntentSupported = false;

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_TEXT, clean)
                .putLong(KEY_UNTIL, System.currentTimeMillis() + 30000L)
                .putString(KEY_LAST_DIAG, "")
                .apply();

        putTextOnClipboard(clean);

        if (!launchByShareIntent(clean)) {
            launchDeepSeekNormally();
        }

        schedulePolls();
    }

    private boolean launchByShareIntent(String text) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            send.setPackage(TARGET_PACKAGE);
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (send.resolveActivity(getPackageManager()) != null) {
                shareIntentSupported = true;
                startActivity(send);
                return true;
            }
        } catch (Exception ignored) {}

        return false;
    }

    private void launchDeepSeekNormally() {
        try {
            Intent launch =
                    getPackageManager()
                            .getLaunchIntentForPackage(TARGET_PACKAGE);

            if (launch == null) {
                showFailureOverlay(
                        "未找到 DeepSeek",
                        "package=" + TARGET_PACKAGE
                );
                automationRunning = false;
                return;
            }

            launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            );

            startActivity(launch);

        } catch (Exception e) {
            showFailureOverlay(
                    "无法打开 DeepSeek",
                    String.valueOf(e)
            );
            automationRunning = false;
        }
    }

    private void schedulePolls() {
        int[] delays = new int[]{
                350, 700, 1100, 1600,
                2300, 3200, 4300, 5600
        };

        for (int delay : delays) {
            handler.postDelayed(
                    this::pollAndAutomate,
                    delay
            );
        }

        handler.postDelayed(
                this::finalFailureCheck,
                6800L
        );
    }

    private final Runnable pollRunnable =
            this::pollAndAutomate;

    private void pollAndAutomate() {
        if (!automationRunning || !isPending()) return;

        AccessibilityNodeInfo root =
                getRootInActiveWindow();

        if (root == null) return;

        CharSequence pkg = root.getPackageName();

        if (pkg == null
                || !TARGET_PACKAGE.contentEquals(pkg)) {
            return;
        }

        AccessibilityNodeInfo input =
                findBestInput(root);

        if (input == null) {
            AccessibilityNodeInfo container =
                    findLikelyInputContainer(root);

            if (container != null) {
                clickNodeOrParent(container);
            }
            return;
        }

        String current =
                safe(input.getText()).trim();

        if (!activeText.equals(current)) {
            if (!writeText(input, activeText)) {
                return;
            }
        }

        handler.postDelayed(
                this::trySendNow,
                220L
        );
    }

    private void trySendNow() {
        if (!automationRunning || !isPending()) return;

        AccessibilityNodeInfo root =
                getRootInActiveWindow();

        if (root == null) return;

        AccessibilityNodeInfo input =
                findBestInput(root);

        if (input == null) return;

        String current =
                safe(input.getText()).trim();

        if (!activeText.equals(current)) {
            if (current.isEmpty()) {
                finishSuccess();
            }
            return;
        }

        AccessibilityNodeInfo send =
                findSemanticSendCandidate(root, input);

        if (send == null) {
            send = findSafeGeometricSendCandidate(root, input);
        }

        if (send != null
                && clickNodeOrParent(send)) {
            handler.postDelayed(
                    this::verifyAfterSend,
                    700L
            );
        }
    }

    private void verifyAfterSend() {
        if (!automationRunning || !isPending()) return;

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

        if (!activeText.equals(current)) {
            finishSuccess();
        }
    }

    private void finalFailureCheck() {
        if (!automationRunning || !isPending()) return;

        AccessibilityNodeInfo root =
                getRootInActiveWindow();

        String diag =
                buildDiagnostic(root);

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_DIAG, diag)
                .apply();

        showFailureOverlay(
                "问问没有完成自动输入",
                diag
        );
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

                if (node.isEditable()) score += 170;
                if (cls.contains("edittext")) score += 110;
                if (hasSetTextAction(node)) score += 90;
                if (node.isFocused()) score += 60;

                if (!r.isEmpty()) {
                    if (r.centerY() > screenHeight * 0.48f) score += 45;
                    if (r.width() > screenWidth * 0.30f) score += 25;
                    if (r.height() < screenHeight * 0.25f) score += 10;
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
                    score += 45;
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

                String joined = (
                        safe(node.getViewIdResourceName()) + " "
                                + safe(node.getText()) + " "
                                + safe(node.getHintText()) + " "
                                + safe(node.getContentDescription())
                ).toLowerCase(Locale.ROOT);

                if (!r.isEmpty()
                        && !containsExcludedControl(joined)
                        && (node.isClickable()
                        || node.isFocusable()
                        || hasClickableParent(node))) {

                    int score = 0;

                    if (joined.contains("input")
                            || joined.contains("message")
                            || joined.contains("chat")
                            || joined.contains("ask")
                            || joined.contains("deepseek")
                            || joined.contains("输入")
                            || joined.contains("提问")
                            || joined.contains("问点什么")
                            || joined.contains("有任何问题")) {
                        score += 180;
                    }

                    if (r.centerY() > screenHeight * 0.58f) score += 55;
                    if (r.width() > screenWidth * 0.38f) score += 55;
                    if (r.height() < screenHeight * 0.22f) score += 20;

                    if (score > bestScore) {
                        best = node;
                        bestScore = score;
                    }
                }
            }

            addChildren(queue, node);
        }

        return bestScore >= 120 ? best : null;
    }

    private boolean writeText(
            AccessibilityNodeInfo input,
            String text
    ) {
        try {
            input.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS
            );
        } catch (Exception ignored) {}

        try {
            Bundle args = new Bundle();

            args.putCharSequence(
                    AccessibilityNodeInfo
                            .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
            );

            if (input.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args
            )) {
                return true;
            }
        } catch (Exception ignored) {}

        try {
            if (input.isFocused()) {
                putTextOnClipboard(text);

                if (input.performAction(
                        AccessibilityNodeInfo.ACTION_PASTE
                )) {
                    return true;
                }
            }
        } catch (Exception ignored) {}

        return false;
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

                        if (r.centerX()
                                > inputBounds.centerX()) score += 55;

                        if (r.centerX()
                                > screenWidth * 0.72f) score += 55;

                        if (r.width()
                                < screenWidth * 0.22f
                                && r.height()
                                < screenHeight * 0.14f) {
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

        } catch (Exception ignored) {}

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

    private String buildDiagnostic(
            AccessibilityNodeInfo root
    ) {
        StringBuilder out = new StringBuilder();

        out.append(
                "========== 问问 v1.3 DeepSeek 诊断 ==========\n"
        );
        out.append("text=").append(activeText).append("\n");
        out.append("share_intent_supported=")
                .append(shareIntentSupported)
                .append("\n");

        if (root == null) {
            out.append("root=null\n");
            return out.toString();
        }

        out.append("root_package=")
                .append(safe(root.getPackageName()))
                .append("\n");

        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        ArrayDeque<AccessibilityNodeInfo> queue =
                new ArrayDeque<>();
        queue.add(root);

        int index = 0;

        while (!queue.isEmpty()
                && index < 220) {

            AccessibilityNodeInfo node =
                    queue.removeFirst();

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if (!r.isEmpty()
                    && r.bottom >= screenHeight * 0.35f) {

                out.append("#")
                        .append(index++)
                        .append(" cls=")
                        .append(safe(node.getClassName()))
                        .append(" id=")
                        .append(safe(node.getViewIdResourceName()))
                        .append(" text=")
                        .append(safe(node.getText()))
                        .append(" hint=")
                        .append(safe(node.getHintText()))
                        .append(" desc=")
                        .append(safe(node.getContentDescription()))
                        .append(" editable=")
                        .append(node.isEditable())
                        .append(" focusable=")
                        .append(node.isFocusable())
                        .append(" focused=")
                        .append(node.isFocused())
                        .append(" clickable=")
                        .append(node.isClickable())
                        .append(" actions=")
                        .append(node.getActions())
                        .append(" bounds=")
                        .append(r.toShortString())
                        .append("\n");
            }

            addChildren(queue, node);
        }

        out.append("========== 诊断结束 ==========\n");
        return out.toString();
    }

    private void showFailureOverlay(
            String title,
            String diag
    ) {
        hideDiagnosticOverlay();

        if (windowManager == null) {
            windowManager =
                    (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        if (windowManager == null) {
            putTextOnClipboard(diag);
            Toast.makeText(
                    this,
                    "诊断信息已复制",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(30, 24, 30, 24);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(32, 33, 36));
        bg.setCornerRadius(28f);
        panel.setBackground(bg);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.rgb(246, 242, 234));
        titleView.setTextSize(18f);
        panel.addView(titleView);

        TextView logView = new TextView(this);
        logView.setText(diag);
        logView.setTextColor(Color.rgb(246, 242, 234));
        logView.setTextSize(11f);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, 16, 0, 16);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);

        LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                );
        panel.addView(scroll, scrollParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button copy = new Button(this);
        copy.setText("复制诊断");
        copy.setOnClickListener(v -> {
            putTextOnClipboard(diag);
            Toast.makeText(
                    this,
                    "诊断已复制",
                    Toast.LENGTH_SHORT
            ).show();
        });

        Button close = new Button(this);
        close.setText("关闭");
        close.setOnClickListener(v -> hideDiagnosticOverlay());

        buttons.addView(
                copy,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        buttons.addView(
                close,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        panel.addView(buttons);

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        (int) (
                                getResources()
                                        .getDisplayMetrics()
                                        .widthPixels * 0.90f
                        ),
                        (int) (
                                getResources()
                                        .getDisplayMetrics()
                                        .heightPixels * 0.62f
                        ),
                        WindowManager.LayoutParams
                                .TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams
                                .FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT
                );

        params.gravity = Gravity.CENTER;

        diagnosticOverlay = panel;

        try {
            windowManager.addView(panel, params);
        } catch (Exception e) {
            diagnosticOverlay = null;
            putTextOnClipboard(diag);
            Toast.makeText(
                    this,
                    "诊断窗口显示失败，日志已复制",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void hideDiagnosticOverlay() {
        if (diagnosticOverlay != null
                && windowManager != null) {
            try {
                windowManager.removeView(
                        diagnosticOverlay
                );
            } catch (Exception ignored) {}
        }

        diagnosticOverlay = null;
    }

    private void putTextOnClipboard(String text) {
        try {
            ClipboardManager cm =
                    (ClipboardManager)
                            getSystemService(
                                    Context.CLIPBOARD_SERVICE
                            );

            if (cm != null) {
                cm.setPrimaryClip(
                        ClipData.newPlainText(
                                "问问",
                                text
                        )
                );
            }
        } catch (Exception ignored) {}
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

        return System.currentTimeMillis() <= until;
    }

    private void finishSuccess() {
        automationRunning = false;
        handler.removeCallbacksAndMessages(null);
        hideDiagnosticOverlay();

        getSharedPreferences(
                PREFS,
                MODE_PRIVATE
        ).edit()
                .putString(KEY_TEXT, "")
                .putLong(KEY_UNTIL, 0L)
                .putString(KEY_LAST_DIAG, "")
                .apply();
    }

    private void addChildren(
            ArrayDeque<AccessibilityNodeInfo> queue,
            AccessibilityNodeInfo node
    ) {
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);

            if (child != null) {
                queue.addLast(child);
            }
        }
    }

    private String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
