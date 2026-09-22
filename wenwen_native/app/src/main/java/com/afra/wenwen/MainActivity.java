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
                        "正在聆听…",
                        Toast.LENGTH_SHORT
                ).show();
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                Toast.makeText(
                        MainActivity.this,
                        "正在识别…",
                        Toast.LENGTH_SHORT
                ).show();
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

                ArrayList<String> list =
                        results.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                        );

                if (list == null || list.isEmpty()) {
                    Toast.makeText(
                            MainActivity.this,
                            "没有识别到内容",
                            Toast.LENGTH_LONG
                    ).show();
                    finish();
                    return;
                }

                String text = "";
                for (String item : list) {
                    if (item != null && !item.trim().isEmpty()) {
                        text = item.trim();
                        break;
                    }
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

            @Override public void onPartialResults(Bundle partialResults) {}
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
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1050L
        );
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                700L
        );

        speechRecognizer.startListening(intent);
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

        // 关键：同步落盘。即使 Activity 马上结束、Service 稍后才重连，
        // 任务也不会丢失。
        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        boolean saved = prefs.edit()
                .putString(KEY_TEXT, text)
                .putLong(KEY_UNTIL, System.currentTimeMillis() + 30000L)
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

        if (attempt >= 20) {
            Toast.makeText(
                    this,
                    "无障碍服务没有连接。请把“问问自动输入”关闭后重新开启一次。",
                    Toast.LENGTH_LONG
            ).show();
            finish();
            return;
        }

        handler.postDelayed(
                () -> tryStartQueuedTask(attempt + 1),
                250L
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
