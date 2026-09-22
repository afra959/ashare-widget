package com.afra.wenwen;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 1001;
    private static final String PREFS = "wenwen";
    private static final String KEY_TEXT = "pending_text";
    private static final String KEY_UNTIL = "pending_until";
    private static final String KEY_AUTOSTART = "pending_autostart";

    private SpeechRecognizer speechRecognizer;
    private boolean resultHandled = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setDimAmount(0f);
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        FrameLayout invisibleRoot = new FrameLayout(this);
        invisibleRoot.setBackgroundColor(Color.TRANSPARENT);
        setContentView(invisibleRoot);

        beginFlow();
    }

    private void beginFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO
            );
            return;
        }

        continueAfterAudioPermission();
    }

    private void continueAfterAudioPermission() {
        if (!isAccessibilityServiceEnabled(
                this,
                DeepSeekAccessibilityService.class
        )) {
            Toast.makeText(
                    this,
                    "请开启“问问自动输入”无障碍服务",
                    Toast.LENGTH_LONG
            ).show();

            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            finish();
            return;
        }

        startSpeechRecognition();
    }

    private void startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                    this,
                    "系统没有可用的语音识别服务",
                    Toast.LENGTH_LONG
            ).show();
            finish();
            return;
        }

        resultHandled = false;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                Toast.makeText(
                        MainActivity.this,
                        "正在聆听…说 over 结束",
                        Toast.LENGTH_SHORT
                ).show();
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                if (!resultHandled) {
                    Toast.makeText(
                            MainActivity.this,
                            "正在识别…",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            }

            @Override
            public void onError(int error) {
                if (resultHandled) return;

                String message;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        message = "没有听清，点“问问”再试一次";
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        message = "没有麦克风权限";
                        break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                        message = "语音识别网络异常";
                        break;
                    default:
                        message = "语音识别失败（" + error + "）";
                        break;
                }

                Toast.makeText(
                        MainActivity.this,
                        message,
                        Toast.LENGTH_LONG
                ).show();
                finish();
            }

            @Override
            public void onResults(Bundle results) {
                if (resultHandled) return;

                String text = firstNonEmpty(
                        results.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                        )
                );

                if (endsWithOver(text)) {
                    text = stripTrailingOver(text);
                }

                if (text.isEmpty()) {
                    Toast.makeText(
                            MainActivity.this,
                            "没有识别到内容",
                            Toast.LENGTH_LONG
                    ).show();
                    finish();
                    return;
                }

                resultHandled = true;
                queueAutomation(text);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (resultHandled) return;

                String partial = firstNonEmpty(
                        partialResults.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                        )
                );

                if (partial.isEmpty() || !endsWithOver(partial)) {
                    return;
                }

                String cleaned = stripTrailingOver(partial);

                if (cleaned.isEmpty()) {
                    return;
                }

                // 快速路径：检测到 over 后直接使用当前 partial，
                // 不再等待 Recognizer 的最终结果。
                resultHandled = true;

                try {
                    speechRecognizer.cancel();
                } catch (Exception ignored) {}

                queueAutomation(cleaned);
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent =
                new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        // 忘记说 over 时仍能自动结束，但 over 才是快速结束方式。
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1200L
        );
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                800L
        );

        speechRecognizer.startListening(intent);
    }

    private String firstNonEmpty(ArrayList<String> list) {
        if (list == null) return "";

        for (String item : list) {
            if (item != null && !item.trim().isEmpty()) {
                return item.trim();
            }
        }

        return "";
    }

    private boolean endsWithOver(String raw) {
        if (raw == null) return false;

        String value = raw.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[，。！？,.!?]+$", "")
                .trim();

        if (!value.endsWith("over")) return false;

        int start = value.length() - 4;

        if (start == 0) return true;

        char previous = value.charAt(start - 1);

        // 中文后直接接 over 也算结束词；英文单词 turnover 之类不误判。
        return !(
                (previous >= 'a' && previous <= 'z')
                        || (previous >= 'A' && previous <= 'Z')
        );
    }

    private String stripTrailingOver(String raw) {
        if (raw == null) return "";

        String value = raw.trim();
        value = value.replaceAll("[，。！？,.!?]+$", "").trim();

        if (endsWithOver(value)) {
            value = value.substring(0, value.length() - 4).trim();
        }

        value = value.replaceAll("[，。！？,.!?]+$", "").trim();
        return value;
    }

    private void queueAutomation(String text) {
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

            if (cm != null) {
                cm.setPrimaryClip(
                        ClipData.newPlainText("问问识别结果", text)
                );
            }
        } catch (Exception ignored) {}

        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        boolean saved = prefs.edit()
                .putString(KEY_TEXT, text)
                .putLong(KEY_UNTIL, System.currentTimeMillis() + 15000L)
                .putBoolean(KEY_AUTOSTART, true)
                .commit();

        if (!saved) {
            Toast.makeText(
                    this,
                    "无法保存语音任务",
                    Toast.LENGTH_LONG
            ).show();
            finish();
            return;
        }

        tryStartQueuedTask(0);
    }

    private void tryStartQueuedTask(int attempt) {
        DeepSeekAccessibilityService service =
                DeepSeekAccessibilityService.instance;

        if (service != null) {
            service.resumeQueuedTask();
            finish();
            return;
        }

        if (attempt >= 12) {
            Toast.makeText(
                    this,
                    "无障碍服务没有连接，请重新开启“问问自动输入”",
                    Toast.LENGTH_LONG
            ).show();
            finish();
            return;
        }

        handler.postDelayed(
                () -> tryStartQueuedTask(attempt + 1),
                200L
        );
    }

    private static boolean isAccessibilityServiceEnabled(
            Context context,
            Class<?> serviceClass
    ) {
        ComponentName expected =
                new ComponentName(context, serviceClass);

        String enabledServices =
                Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                );

        if (TextUtils.isEmpty(enabledServices)) return false;

        TextUtils.SimpleStringSplitter splitter =
                new TextUtils.SimpleStringSplitter(':');

        splitter.setString(enabledServices);

        while (splitter.hasNext()) {
            ComponentName actual =
                    ComponentName.unflattenFromString(splitter.next());

            if (expected.equals(actual)) return true;
        }

        return false;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode != REQ_AUDIO) return;

        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            continueAfterAudioPermission();
        } else {
            Toast.makeText(
                    this,
                    "需要麦克风权限才能语音输入",
                    Toast.LENGTH_LONG
            ).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
        }

        super.onDestroy();
    }
}
