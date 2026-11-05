package com.spikers.live;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.pedro.common.ConnectChecker;
import com.pedro.common.StreamingStatsReport;
import com.pedro.library.rtmp.RtmpStream;

import java.util.Locale;

public class MainActivity extends Activity
        implements ConnectChecker, SurfaceHolder.Callback {

    private static final int PERMISSION_REQUEST = 100;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 30;
    private static final int VIDEO_BITRATE = 4_000_000;

    private RtmpStream stream;
    private SurfaceView cameraPreview;
    private EditText serverInput;
    private EditText keyInput;
    private TextView statusText;
    private TextView bitrateText;
    private Button startButton;
    private Button stopButton;

    private boolean prepared = false;
    private boolean surfaceReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        cameraPreview = findViewById(R.id.cameraPreview);
        serverInput = findViewById(R.id.serverInput);
        keyInput = findViewById(R.id.keyInput);
        statusText = findViewById(R.id.statusText);
        bitrateText = findViewById(R.id.bitrateText);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        startButton.setEnabled(false);
        stopButton.setEnabled(false);

        cameraPreview.getHolder().addCallback(this);

        startButton.setOnClickListener(v -> startLive());
        stopButton.setOnClickListener(v -> stopLive());

        if (hasPermissions()) {
            initializeStream();
        } else {
            requestPermissions(
                    new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                    },
                    PERMISSION_REQUEST
            );
        }
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults
        );

        if (requestCode == PERMISSION_REQUEST) {
            if (hasPermissions()) {
                initializeStream();
            } else {
                statusText.setText(
                        "Camera and microphone permissions required."
                );
            }
        }
    }

    private void initializeStream() {
        if (prepared) return;

        try {
            stream = new RtmpStream(this, this);

            boolean videoReady = stream.prepareVideo(
                    WIDTH,
                    HEIGHT,
                    VIDEO_BITRATE,
                    FPS,
                    2,
                    0
            );

            boolean audioReady = stream.prepareAudio(
                    44100,
                    true,
                    128_000
            );

            prepared = videoReady && audioReady;

            if (!prepared) {
                statusText.setText("Encoder initialization failed.");
                stream.release();
                stream = null;
                return;
            }

            statusText.setText("Camera ready.");
            startButton.setEnabled(true);
            startPreviewIfReady();

        } catch (Exception e) {
            prepared = false;
            statusText.setText(
                    "Initialization failed: " + e.getClass().getSimpleName()
            );
            if (stream != null) {
                stream.release();
                stream = null;
            }
        }
    }

    private void startPreviewIfReady() {
        if (prepared && surfaceReady && !stream.isOnPreview()) {
            stream.startPreview(cameraPreview);
        }
    }

    private void startLive() {
        if (!prepared || !surfaceReady || !hasPermissions()) {
            statusText.setText("Camera is not ready.");
            return;
        }

        String server = serverInput.getText().toString().trim();
        String key = keyInput.getText().toString().trim();

        if (!server.startsWith("rtmps://") || key.isEmpty()) {
            statusText.setText(
                    "Enter a valid RTMPS URL and stream key."
            );
            return;
        }

        if (stream.isStreaming()) return;

        String endpoint = server.replaceAll("/+$", "") + "/" + key;

        try {
            stream.startStream(endpoint);
            statusText.setText("Connecting to YouTube...");
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
        } catch (Exception e) {
            statusText.setText("Unable to start stream.");
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        }
    }

    private void stopLive() {
        if (stream != null && stream.isStreaming()) {
            stream.stopStream();
        }

        startButton.setEnabled(prepared);
        stopButton.setEnabled(false);
        bitrateText.setText("Upload bitrate: --");
        statusText.setText("Stream stopped.");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        startPreviewIfReady();
    }

    @Override
    public void surfaceChanged(
            SurfaceHolder holder,
            int format,
            int width,
            int height) {

        if (prepared) {
            stream.getGlInterface().setPreviewResolution(
                    width, height
            );
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;

        if (stream != null) {
            if (stream.isStreaming()) {
                stream.stopStream();
            }
            if (stream.isOnPreview()) {
                stream.stopPreview();
            }
        }
    }

    @Override
    public void onConnectionStarted(String url) {
        runOnUiThread(() ->
                statusText.setText("Connecting...")
        );
    }

    @Override
    public void onConnectionSuccess() {
        runOnUiThread(() ->
                statusText.setText("Connected to YouTube.")
        );
    }

    @Override
    public void onConnectionFailed(String reason) {
        runOnUiThread(() -> {
            if (stream != null && stream.isStreaming()) {
                stream.stopStream();
            }

            String key = keyInput.getText().toString().trim();
            String safeReason = reason == null ? "Unknown error" : reason;
            if (!key.isEmpty()) {
                safeReason = safeReason.replace(key, "[hidden]");
            }

            statusText.setText("Connection failed: " + safeReason);
            startButton.setEnabled(prepared);
            stopButton.setEnabled(false);
        });
    }

    @Override
    public void onDisconnect() {
        runOnUiThread(() -> {
            statusText.setText("Disconnected.");
            startButton.setEnabled(prepared);
            stopButton.setEnabled(false);
        });
    }

    @Override
    public void onAuthError() {
        runOnUiThread(() -> {
            statusText.setText("Authentication error.");
            stopLive();
        });
    }

    @Override
    public void onAuthSuccess() {
        // No action required for this first version.
    }

    @Override
    public void onNewBitrate(long bitrate) {
        runOnUiThread(() ->
                bitrateText.setText(String.format(
                        Locale.US,
                        "Upload bitrate: %.2f Mbps",
                        bitrate / 1_000_000.0
                ))
        );
    }

    @Override
    public void onStreamingStats(StreamingStatsReport report) {
        // Advanced diagnostics will be added in Stage 3.
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPreviewIfReady();
    }

    @Override
    protected void onStop() {
        if (stream != null) {
            if (stream.isStreaming()) stream.stopStream();
            if (stream.isOnPreview()) stream.stopPreview();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (stream != null) {
            stream.release();
            stream = null;
        }
        super.onDestroy();
    }
}
