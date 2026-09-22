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

public class DeepSeekAccessibilityService extends AccessibilityService {

    public static volatile DeepSeekAccessibilityService instance;

    private static final String TARGET_PACKAGE = "com.deepseek.chat";
    private static final String PREFS = "wenwen";
    private static final String KEY_TEXT = "pending_text";
    private static final String KEY_UNTIL = "pending_until";
    private static final String KEY_AUTOSTART = "pending_autostart";
    private static final String KEY_LAST_DIAG = "last_diag";

    private static final String PLACEHOLDER = "发消息或按住说话";

    private static final int STATE_WAIT_ROOT = 0;
    private static final int STATE_WAIT_EDIT = 1;
    private static final int STATE_WAIT_SEND = 2;
    private static final int STATE_VERIFY = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String activeText = "";
    private boolean automationRunning = false;
    private int state = STATE_WAIT_ROOT;
    private int sendAttempts = 0;
    private long startedAt = 0L;

    private WindowManager windowManager;
    private android.view.View diagnosticOverlay;

    private final Runnable stepRunnable = this::step;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler.postDelayed(this::resumeQueuedTask, 80L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不再由事件驱动整个流程，避免键盘/输入变化导致重复执行。
    }

    @Override
    public void onInterrupt() {
        stopAutomation();
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
        state = STATE_WAIT_ROOT;
        sendAttempts = 0;
        startedAt = System.currentTimeMillis();

        launchDeepSeekNormally();
        handler.postDelayed(stepRunnable, 180L);
    }

    private void launchDeepSeekNormally() {
        try {
            Intent launch =
                    getPackageManager()
                            .getLaunchIntentForPackage(TARGET_PACKAGE);

            if (launch == null) {
                fail("未找到 DeepSeek");
                return;
            }

            launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
            );

            startActivity(launch);

        } catch (Exception e) {
            fail("无法打开 DeepSeek：" + e);
        }
    }

    private void step() {
        if (!automationRunning || !isPending()) return;

        if (System.currentTimeMillis() - startedAt > 5200L) {
            failWithDiagnostic();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (!isDeepSeekRoot(root)) {
            scheduleNext(120L);
            return;
        }

        if (state == STATE_WAIT_ROOT) {
            AccessibilityNodeInfo edit = findComposerEditText(root);

            if (edit != null) {
                state = STATE_WAIT_SEND;
                writeText(edit);
                scheduleNext(90L);
                return;
            }

            AccessibilityNodeInfo placeholder =
                    findExactText(root, PLACEHOLDER);

            if (placeholder != null) {
                Rect r = new Rect();
                placeholder.getBoundsInScreen(r);

                if (!r.isEmpty()) {
                    clickAt(
                            r.exactCenterX(),
                            r.exactCenterY()
                    );

                    state = STATE_WAIT_EDIT;
                    scheduleNext(120L);
                    return;
                }
            }

            scheduleNext(100L);
            return;
        }

        if (state == STATE_WAIT_EDIT) {
            AccessibilityNodeInfo edit = findComposerEditText(root);

            if (edit == null) {
                scheduleNext(100L);
                return;
            }

            if (!writeText(edit)) {
                scheduleNext(120L);
                return;
            }

            state = STATE_WAIT_SEND;
            scheduleNext(90L);
            return;
        }

        if (state == STATE_WAIT_SEND) {
            AccessibilityNodeInfo edit = findComposerEditText(root);

            if (edit == null) {
                scheduleNext(100L);
                return;
            }

            String current = safe(edit.getText()).trim();

            if (!activeText.equals(current)) {
                if (!writeText(edit)) {
                    scheduleNext(120L);
                    return;
                }

                scheduleNext(90L);
                return;
            }

            AccessibilityNodeInfo send =
                    findExactDesc(root, "发送");

            if (send == null) {
                scheduleNext(100L);
                return;
            }

            Rect r = new Rect();
            send.getBoundsInScreen(r);

            if (r.isEmpty()) {
                scheduleNext(100L);
                return;
            }

            // 真实日志已经确认这个 bounds 就是蓝色上箭头本身。
            // 不再先点外层 Compose 容器。
            clickAt(
                    r.exactCenterX(),
                    r.exactCenterY()
            );

            sendAttempts++;
            state = STATE_VERIFY;
            scheduleNext(360L);
            return;
        }

        if (state == STATE_VERIFY) {
            AccessibilityNodeInfo edit = findComposerEditText(root);

            if (edit == null
                    || !activeText.equals(
                    safe(edit.getText()).trim()
            )) {
                finishSuccess();
                return;
            }

            if (sendAttempts < 2) {
                state = STATE_WAIT_SEND;
                scheduleNext(80L);
                return;
            }

            failWithDiagnostic();
        }
    }

    private boolean writeText(AccessibilityNodeInfo edit) {
        if (edit == null) return false;

        String current = safe(edit.getText()).trim();

        if (activeText.equals(current)) return true;

        try {
            edit.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS
            );
        } catch (Exception ignored) {}

        try {
            Bundle args = new Bundle();

            args.putCharSequence(
                    AccessibilityNodeInfo
                            .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    activeText
            );

            return edit.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args
            );

        } catch (Exception ignored) {
            return false;
        }
    }

    private AccessibilityNodeInfo findComposerEditText(
            AccessibilityNodeInfo root
    ) {
        if (root == null) return null;

        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            String cls = safe(node.getClassName());
            String hint = safe(node.getHintText());

            int score = 0;

            if ("android.widget.EditText".equals(cls)) score += 200;
            if (node.isEditable()) score += 150;
            if ("发消息".equals(hint)) score += 180;
            if (node.isFocused()) score += 40;

            if (!r.isEmpty()
                    && r.centerY() > screenHeight * 0.45f) {
                score += 50;
            }

            if (score > bestScore
                    && ("android.widget.EditText".equals(cls)
                    || node.isEditable())) {
                best = node;
                bestScore = score;
            }

            addChildren(queue, node);
        }

        return best;
    }

    private AccessibilityNodeInfo findExactText(
            AccessibilityNodeInfo root,
            String target
    ) {
        if (root == null) return null;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            if (target.equals(safe(node.getText()).trim())) {
                return node;
            }

            addChildren(queue, node);
        }

        return null;
    }

    private AccessibilityNodeInfo findExactDesc(
            AccessibilityNodeInfo root,
            String target
    ) {
        if (root == null) return null;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            if (target.equals(
                    safe(node.getContentDescription()).trim()
            )) {
                return node;
            }

            addChildren(queue, node);
        }

        return null;
    }

    private boolean clickAt(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);

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

    private void scheduleNext(long delay) {
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(stepRunnable, delay);
    }

    private boolean isDeepSeekRoot(AccessibilityNodeInfo root) {
        return root != null
                && root.getPackageName() != null
                && TARGET_PACKAGE.contentEquals(root.getPackageName());
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

    private void stopAutomation() {
        automationRunning = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void fail(String message) {
        stopAutomation();
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
        ).show();
    }

    private void failWithDiagnostic() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String diag = buildDiagnostic(root);

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_DIAG, diag)
                .apply();

        automationRunning = false;
        handler.removeCallbacksAndMessages(null);

        showFailureOverlay(
                "问问没有完成自动发送",
                diag
        );
    }

    private String buildDiagnostic(AccessibilityNodeInfo root) {
        StringBuilder out = new StringBuilder();

        out.append(
                "========== 问问 v1.7 DeepSeek 诊断 ==========\n"
        );
        out.append("text=").append(activeText).append("\n");
        out.append("state=").append(state).append("\n");
        out.append("sendAttempts=").append(sendAttempts).append("\n");

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

        while (!queue.isEmpty() && index < 180) {
            AccessibilityNodeInfo node = queue.removeFirst();

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if (!r.isEmpty()
                    && r.bottom >= screenHeight * 0.35f) {

                out.append("#")
                        .append(index++)
                        .append(" cls=")
                        .append(safe(node.getClassName()))
                        .append(" text=")
                        .append(safe(node.getText()))
                        .append(" hint=")
                        .append(safe(node.getHintText()))
                        .append(" desc=")
                        .append(safe(node.getContentDescription()))
                        .append(" editable=")
                        .append(node.isEditable())
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
