package com.afra.wenwen;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 1001;
    private static final String TARGET_PACKAGE = "com.deepseek.chat";
    private static final String PREFS = "wenwen";
    private static final String KEY_TEXT = "pending_text";
    private static final String KEY_UNTIL = "pending_until";

    private SpeechRecognizer speechRecognizer;
    private TextView statusView;
    private boolean resultHandled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildListeningUi();
        beginFlow();
    }

    private void buildListeningUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.rgb(32, 33, 36));

        TextView mic = new TextView(this);
        mic.setText("●");
        mic.setTextColor(Color.rgb(246, 242, 234));
        mic.setTextSize(56f);
        mic.setGravity(Gravity.CENTER);
        root.addView(mic);

        statusView = new TextView(this);
        statusView.setText("准备语音输入…");
        statusView.setTextColor(Color.rgb(246, 242, 234));
        statusView.setTextSize(20f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 28, 0, 0);
        root.addView(statusView);

        setContentView(root);
    }

    private void beginFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            statusView.setText("需要麦克风权限");
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
                    "首次使用：请开启“问问自动输入”无障碍服务",
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
            statusView.setText("系统没有可用的语音识别服务");
            Toast.makeText(
                    this,
                    "系统没有可用的语音识别服务",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        resultHandled = false;

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                statusView.setText("正在聆听…");
            }

            @Override
            public void onBeginningOfSpeech() {
                statusView.setText("正在聆听…");
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                statusView.setText("正在识别…");
            }

            @Override
            public void onError(int error) {
                if (resultHandled) {
                    return;
                }

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

                statusView.setText(message);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onResults(Bundle results) {
                if (resultHandled) {
                    return;
                }

                ArrayList<String> list =
                        results.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                        );

                if (list == null || list.isEmpty()) {
                    statusView.setText("没有识别到内容");
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
                    statusView.setText("没有识别到内容");
                    return;
                }

                resultHandled = true;
                sendToDeepSeek(text);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1100L
        );
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                750L
        );

        statusView.setText("正在启动麦克风…");
        speechRecognizer.startListening(intent);
    }

    private void sendToDeepSeek(String text) {
        statusView.setText("已识别：\n" + text + "\n\n正在发送到 DeepSeek…");

        // 无论自动发送是否成功，都先保留一份到剪贴板。
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

            if (cm != null) {
                cm.setPrimaryClip(
                        ClipData.newPlainText("问问识别结果", text)
                );
            }
        } catch (Exception ignored) {
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_TEXT, text)
                .putLong(KEY_UNTIL, System.currentTimeMillis() + 20000L)
                .apply();

        Intent launchIntent =
                getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);

        if (launchIntent == null) {
            Toast.makeText(
                    this,
                    "未找到 DeepSeek，识别结果已复制",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        );

        startActivity(launchIntent);
        finish();
    }

    private static boolean isAccessibilityServiceEnabled(
            Context context,
            Class<?> serviceClass
    ) {
        ComponentName expected = new ComponentName(context, serviceClass);

        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (TextUtils.isEmpty(enabledServices)) {
            return false;
        }

        TextUtils.SimpleStringSplitter splitter =
                new TextUtils.SimpleStringSplitter(':');

        splitter.setString(enabledServices);

        while (splitter.hasNext()) {
            ComponentName actual =
                    ComponentName.unflattenFromString(splitter.next());

            if (expected.equals(actual)) {
                return true;
            }
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

        if (requestCode != REQ_AUDIO) {
            return;
        }

        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            continueAfterAudioPermission();
        } else {
            statusView.setText("麦克风权限被拒绝");
            Toast.makeText(
                    this,
                    "需要麦克风权限才能语音输入",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception ignored) {
            }
        }

        super.onDestroy();
    }
}
