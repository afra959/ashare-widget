package com.afra.wenwen;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private static final String KEY_AUTOSTART = "pending_autostart";
    private static final String KEY_LAST_DIAG = "last_diag";

    private static final String PLACEHOLDER_CN = "发消息或按住说话";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String activeText = "";
    private boolean automationRunning = false;
    private int inputStage = 0;

    private WindowManager windowManager;
    private android.view.View diagnosticOverlay;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler.postDelayed(this::resumeQueuedTask, 120L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!automationRunning) return;

        if (event != null
                && event.getPackageName() != null
                && TARGET_PACKAGE.contentEquals(event.getPackageName())) {
            handler.removeCallbacks(pollRunnable);
            handler.postDelayed(pollRunnable, 100L);
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

    public synchronized void resumeQueuedTask() {
        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        boolean autostart =
                prefs.getBoolean(KEY_AUTOSTART, false);

        long until =
                prefs.getLong(KEY_UNTIL, 0L);

        String text =
                prefs.getString(KEY_TEXT, "");

        if (!autostart
                || System.currentTimeMillis() > until
                || text == null
                || text.trim().isEmpty()) {
            return;
        }

        boolean claimed = prefs.edit()
                .putBoolean(KEY_AUTOSTART, false)
                .commit();

        if (!claimed) return;

        beginAutomation(text.trim());
    }

    private void beginAutomation(String text) {
        handler.removeCallbacksAndMessages(null);
        hideDiagnosticOverlay();

        activeText = text;
        automationRunning = true;
        inputStage = 0;

        putTextOnClipboard(activeText);
        launchDeepSeekNormally();

        int[] delays = new int[]{
                450, 850, 1300, 1900,
                2700, 3700, 5000, 6500
        };

        for (int delay : delays) {
            handler.postDelayed(this::pollAndAutomate, delay);
        }

        handler.postDelayed(this::finalFailureCheck, 8000L);
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

    private final Runnable pollRunnable = this::pollAndAutomate;

    private void pollAndAutomate() {
        if (!automationRunning || !isPending()) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!isDeepSeekRoot(root)) return;

        if (isTextVisibleInComposer(root, activeText)) {
            trySendNow(root);
            return;
        }

        AccessibilityNodeInfo editable = findEditableAfterFocus(root);

        if (editable != null) {
            if (writeToEditable(editable, activeText)) {
                handler.postDelayed(this::pollAndAutomate, 250L);
                return;
            }
        }

        AccessibilityNodeInfo placeholder = findComposerPlaceholder(root);

        if (placeholder == null) {
            return;
        }

        Rect bounds = new Rect();
        placeholder.getBoundsInScreen(bounds);

        if (bounds.isEmpty()) return;

        if (inputStage == 0) {
            // Real tree shows the composer is a non-editable TextView.
            // Tap it first so DeepSeek enters editing mode.
            inputStage = 1;
            clickAt(bounds.exactCenterX(), bounds.exactCenterY());
            handler.postDelayed(this::pollAndAutomate, 320L);
            return;
        }

        if (inputStage == 1) {
            // After focus, try ACTION_PASTE on the focused/new input or placeholder.
            inputStage = 2;

            AccessibilityNodeInfo focused =
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);

            boolean pasted = false;

            if (focused != null) {
                pasted = tryPaste(focused);
            }

            if (!pasted) {
                pasted = tryPaste(placeholder);
            }

            handler.postDelayed(this::pollAndAutomate, 350L);
            return;
        }

        if (inputStage == 2) {
            // Last safe input fallback: long-press the known composer,
            // then choose the system "Paste" action if it appears.
            inputStage = 3;
            longPressAt(bounds.exactCenterX(), bounds.exactCenterY(), 620L);
            handler.postDelayed(this::clickPastePopupIfPresent, 380L);
            return;
        }
    }

    private void clickPastePopupIfPresent() {
        if (!automationRunning || !isPending()) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        AccessibilityNodeInfo paste = findByExactTextOrDesc(root, "粘贴");
        if (paste == null) {
            paste = findByExactTextOrDesc(root, "Paste");
        }

        if (paste != null) {
            clickNodeOrParent(paste);
        }

        handler.postDelayed(this::pollAndAutomate, 350L);
    }

    private AccessibilityNodeInfo findComposerPlaceholder(
            AccessibilityNodeInfo root
    ) {
        AccessibilityNodeInfo exact =
                findByExactTextOrDesc(root, PLACEHOLDER_CN);

        if (exact != null) return exact;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            String text = safe(node.getText());
            String hint = safe(node.getHintText());
            String desc = safe(node.getContentDescription());

            String joined = (text + " " + hint + " " + desc)
                    .toLowerCase(Locale.ROOT);

            int score = 0;

            if (joined.contains("发消息")
                    || joined.contains("按住说话")
                    || joined.contains("message")) {
                score += 220;
            }

            if (!r.isEmpty()
                    && r.centerY() > screenHeight * 0.72f) {
                score += 70;
            }

            if (r.width()
                    > getResources().getDisplayMetrics().widthPixels * 0.55f) {
                score += 35;
            }

            if (score > bestScore) {
                best = node;
                bestScore = score;
            }

            addChildren(queue, node);
        }

        return bestScore >= 200 ? best : null;
    }

    private AccessibilityNodeInfo findEditableAfterFocus(
            AccessibilityNodeInfo root
    ) {
        AccessibilityNodeInfo focused =
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);

        if (focused != null
                && (focused.isEditable()
                || hasSetTextAction(focused))) {
            return focused;
        }

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            String cls = safe(node.getClassName())
                    .toLowerCase(Locale.ROOT);

            int score = 0;

            if (node.isEditable()) score += 180;
            if (hasSetTextAction(node)) score += 150;
            if (cls.contains("edittext")) score += 120;
            if (node.isFocused()) score += 80;

            if (!r.isEmpty()
                    && r.centerY() > screenHeight * 0.55f) {
                score += 50;
            }

            if ((node.isEditable()
                    || hasSetTextAction(node)
                    || cls.contains("edittext"))
                    && score > bestScore) {
                best = node;
                bestScore = score;
            }

            addChildren(queue, node);
        }

        return best;
    }

    private boolean writeToEditable(
            AccessibilityNodeInfo node,
            String text
    ) {
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        } catch (Exception ignored) {}

        if (hasSetTextAction(node)) {
            try {
                Bundle args = new Bundle();
                args.putCharSequence(
                        AccessibilityNodeInfo
                                .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                );

                if (node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        args
                )) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        return tryPaste(node);
    }

    private boolean tryPaste(AccessibilityNodeInfo node) {
        if (node == null) return false;

        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        } catch (Exception ignored) {}

        try {
            putTextOnClipboard(activeText);
            return node.performAction(
                    AccessibilityNodeInfo.ACTION_PASTE
            );
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isTextVisibleInComposer(
            AccessibilityNodeInfo root,
            String text
    ) {
        if (text == null || text.isEmpty()) return false;

        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            String nodeText = safe(node.getText()).trim();

            if (text.equals(nodeText)
                    && !r.isEmpty()
                    && r.centerY() > screenHeight * 0.55f) {
                return true;
            }

            addChildren(queue, node);
        }

        return false;
    }

    private void trySendNow(AccessibilityNodeInfo root) {
        if (!automationRunning || !isPending()) return;

        AccessibilityNodeInfo semantic =
                findSemanticSendCandidate(root);

        if (semantic != null
                && clickNodeOrParent(semantic)) {
            handler.postDelayed(this::verifySent, 700L);
            return;
        }

        AccessibilityNodeInfo rightButton =
                findRightmostComposerButton(root);

        if (rightButton != null
                && clickNodeOrParent(rightButton)) {
            handler.postDelayed(this::verifySent, 700L);
        }
    }

    private AccessibilityNodeInfo findSemanticSendCandidate(
            AccessibilityNodeInfo root
    ) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

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
                score += 300;
            }

            if (joined.contains("send")
                    || joined.contains("发送")
                    || joined.contains("submit")) {
                score += 220;
            }

            if (joined.contains("voice")
                    || joined.contains("语音")
                    || joined.contains("麦克风")
                    || joined.contains("上传")
                    || joined.contains("附件")) {
                score -= 500;
            }

            if ((node.isClickable()
                    || hasClickableParent(node))
                    && score > bestScore) {
                best = node;
                bestScore = score;
            }

            addChildren(queue, node);
        }

        return bestScore >= 220 ? best : null;
    }

    private AccessibilityNodeInfo findRightmostComposerButton(
            AccessibilityNodeInfo root
    ) {
        int screenWidth =
                getResources().getDisplayMetrics().widthPixels;
        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            if (node.isVisibleToUser()
                    && (node.isClickable()
                    || hasClickableParent(node))) {

                Rect r = new Rect();
                node.getBoundsInScreen(r);

                if (!r.isEmpty()) {
                    String joined = (
                            safe(node.getText()) + " "
                                    + safe(node.getContentDescription()) + " "
                                    + safe(node.getViewIdResourceName())
                    ).toLowerCase(Locale.ROOT);

                    int score = 0;

                    if (r.centerY() > screenHeight * 0.72f) score += 80;
                    if (r.centerX() > screenWidth * 0.78f) score += 90;

                    if (r.width() < screenWidth * 0.22f
                            && r.height() < screenHeight * 0.14f) {
                        score += 50;
                    }

                    if (joined.contains("上传")
                            || joined.contains("附件")
                            || joined.contains("深度思考")
                            || joined.contains("智能搜索")) {
                        score -= 500;
                    }

                    // When text is already confirmed in composer, DeepSeek
                    // replaces the voice toggle at the far right with send.
                    if (score > bestScore) {
                        best = node;
                        bestScore = score;
                    }
                }
            }

            addChildren(queue, node);
        }

        return bestScore >= 190 ? best : null;
    }

    private void verifySent() {
        if (!automationRunning || !isPending()) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null
                || !isTextVisibleInComposer(root, activeText)) {
            finishSuccess();
        }
    }

    private void finalFailureCheck() {
        if (!automationRunning || !isPending()) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        String diag = buildDiagnostic(root);

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_DIAG, diag)
                .apply();

        showFailureOverlay(
                "问问没有完成自动输入",
                diag
        );
    }

    private AccessibilityNodeInfo findByExactTextOrDesc(
            AccessibilityNodeInfo root,
            String target
    ) {
        if (root == null || target == null) return null;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            if (target.equals(safe(node.getText()).trim())
                    || target.equals(
                    safe(node.getContentDescription()).trim()
            )) {
                return node;
            }

            addChildren(queue, node);
        }

        return null;
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

            AccessibilityNodeInfo parent = node.getParent();

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

            return dispatchGesture(gesture, null, null);

        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean longPressAt(
            float x,
            float y,
            long duration
    ) {
        try {
            Path path = new Path();
            path.moveTo(x, y);

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(
                            path,
                            0L,
                            duration
                    );

            GestureDescription gesture =
                    new GestureDescription.Builder()
                            .addStroke(stroke)
                            .build();

            return dispatchGesture(gesture, null, null);

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
        AccessibilityNodeInfo parent = node.getParent();

        for (int depth = 0;
             parent != null && depth < 3;
             depth++) {

            if (parent.isClickable()) return true;
            parent = parent.getParent();
        }

        return false;
    }

    private boolean isDeepSeekRoot(AccessibilityNodeInfo root) {
        return root != null
                && root.getPackageName() != null
                && TARGET_PACKAGE.contentEquals(root.getPackageName());
    }

    private String buildDiagnostic(AccessibilityNodeInfo root) {
        StringBuilder out = new StringBuilder();

        out.append(
                "========== 问问 v1.5 DeepSeek 诊断 ==========\n"
        );
        out.append("text=").append(activeText).append("\n");
        out.append("inputStage=").append(inputStage).append("\n");

        if (root == null) {
            out.append("root=null\n");
            return out.toString();
        }

        out.append("root_package=")
                .append(safe(root.getPackageName()))
                .append("\n");

        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int index = 0;

        while (!queue.isEmpty() && index < 240) {
            AccessibilityNodeInfo node = queue.removeFirst();

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if (!r.isEmpty()
                    && r.bottom >= screenHeight * 0.30f) {

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
                windowManager.removeView(diagnosticOverlay);
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
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getLong(KEY_UNTIL, 0L);

        return System.currentTimeMillis() <= until;
    }

    private void finishSuccess() {
        automationRunning = false;
        handler.removeCallbacksAndMessages(null);
        hideDiagnosticOverlay();

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_TEXT, "")
                .putLong(KEY_UNTIL, 0L)
                .putBoolean(KEY_AUTOSTART, false)
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
