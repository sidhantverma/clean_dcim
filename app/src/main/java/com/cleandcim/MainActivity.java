package com.cleandcim;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.database.sqlite.SQLiteException;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;

public final class MainActivity extends Activity {
    private LinearLayout layout;
    private EditText source;
    private EditText destination;
    private TextView status;
    private Button toggle;
    private boolean wasGranted;
    private LinearLayout logPanel;
    private TextView logText;
    private Button logToggle;
    private Button newerLogs;
    private Button olderLogs;
    private int logOffset;
    private boolean logExpanded;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable logMaintenance = new Runnable() {
        @Override public void run() {
            refreshLog();
            ui.postDelayed(this, 60_000);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
        if ("logRevision".equals(key)) {
            logOffset = 0;
            refreshLog();
        } else {
            refresh();
        }
    };

    static boolean permitted(android.content.Context context) {
        return Environment.isExternalStorageManager();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Notifications.createChannels(this);
        logExpanded = state != null && state.getBoolean("logExpanded");
        showScreen();
        if (state == null && !permitted(this)) {
            requestPermissionsFlow();
        }
    }

    @Override public void onResume() {
        super.onResume();
        if (layout == null || wasGranted != permitted(this)) {
            showScreen();
        }
        Prefs.get(this).registerOnSharedPreferenceChangeListener(listener);
        refresh();
        ui.post(logMaintenance);
    }

    @Override public void onPause() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(listener);
        ui.removeCallbacks(logMaintenance);
        super.onPause();
    }

    private void showScreen() {
        wasGranted = permitted(this);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            view.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom);
            return insets;
        });
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        setContentView(scroll);
        source = null;
        destination = null;
        status = null;
        toggle = null;
        logPanel = null;
        if (!wasGranted) {
            layout.setGravity(Gravity.CENTER);
            text(R.string.permissions_required, 22);
            button(R.string.grant_permissions, view -> requestPermissionsFlow());
            if (Prefs.get(this).getBoolean("enabled", false)) {
                Prefs.get(this).edit().putBoolean("enabled", false).apply();
                stopService(new Intent(this, WatchService.class));
            }
            return;
        }
        text(R.string.app_name, 28);
        text(R.string.description, 15);
        text(R.string.source_path, 14);
        source = input(Prefs.source(this));
        text(R.string.destination_path, 14);
        destination = input(Prefs.destination(this));
        toggle = button(R.string.enable_watch, view -> toggleWatch());
        status = text(R.string.idle, 14);
        text(R.string.notifications_help, 14);
        button(R.string.error_notifications, view -> notificationSettings());
        text(R.string.battery_help, 14);
        button(R.string.battery_settings, view -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException | SecurityException error) {
                error(getString(R.string.open_settings_error, error.getMessage()));
            }
        });
        logToggle = button(logExpanded ? R.string.hide_log : R.string.show_log, view -> {
            logExpanded = !logExpanded;
            logPanel.setVisibility(logExpanded ? View.VISIBLE : View.GONE);
            logToggle.setText(logExpanded ? R.string.hide_log : R.string.show_log);
            refreshLog();
        });
        logPanel = new LinearLayout(this);
        logPanel.setOrientation(LinearLayout.VERTICAL);
        layout.addView(logPanel);
        button(logPanel, R.string.clear_log, view -> {
            try {
                EventLog.get(this).clear();
                logOffset = 0;
                refreshLog();
            } catch (SQLiteException failure) {
                error(getString(R.string.log_error, failure.getMessage()));
            }
        });
        logText = new TextView(this);
        logText.setTextSize(13);
        logText.setTextIsSelectable(true);
        logPanel.addView(logText);
        newerLogs = new Button(this);
        newerLogs.setText(R.string.newer_logs);
        newerLogs.setOnClickListener(view -> {
            logOffset = Math.max(0, logOffset - EventLog.PAGE_SIZE);
            refreshLog();
        });
        logPanel.addView(newerLogs);
        olderLogs = new Button(this);
        olderLogs.setText(R.string.older_logs);
        olderLogs.setOnClickListener(view -> {
            logOffset += EventLog.PAGE_SIZE;
            refreshLog();
        });
        logPanel.addView(olderLogs);
        logPanel.setVisibility(logExpanded ? View.VISIBLE : View.GONE);
        refreshLog();
        refresh();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean("logExpanded", logExpanded);
        super.onSaveInstanceState(state);
    }

    private void refreshLog() {
        if (logPanel == null) {
            return;
        }
        try {
            EventLog.get(this).prune();
            if (!logExpanded) {
                return;
            }
            List<String> entries = EventLog.get(this).read(logOffset);
            if (entries.isEmpty() && logOffset > 0) {
                logOffset = 0;
                entries = EventLog.get(this).read(0);
            }
            logText.setText(entries.isEmpty() ? getString(R.string.log_empty)
                    : android.text.TextUtils.join("\n\n", entries));
            newerLogs.setEnabled(logOffset > 0);
            olderLogs.setEnabled(entries.size() == EventLog.PAGE_SIZE);
        } catch (SQLiteException failure) {
            logText.setText(getString(R.string.log_error, failure.getMessage()));
        }
    }

    private TextView text(int resource, int size) {
        TextView view = new TextView(this);
        view.setText(resource);
        view.setTextSize(size);
        view.setPadding(0, 12, 0, 12);
        layout.addView(view);
        return view;
    }

    private EditText input(String value) {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setText(value);
        layout.addView(view);
        return view;
    }

    private Button button(int resource, View.OnClickListener action) {
        return button(layout, resource, action);
    }

    private Button button(LinearLayout parent, int resource, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(resource);
        button.setOnClickListener(action);
        parent.addView(button);
        return button;
    }

    private void refresh() {
        if (toggle == null) {
            return;
        }
        boolean enabled = Prefs.get(this).getBoolean("enabled", false);
        toggle.setText(enabled ? R.string.disable_watch : R.string.enable_watch);
        source.setEnabled(!enabled);
        destination.setEnabled(!enabled);
        status.setText(Prefs.get(this).getString("status", getString(R.string.idle)));
    }

    private void requestPermissionsFlow() {
        try {
            if (!Environment.isExternalStorageManager()) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())), 100);
            } else {
                showScreen();
            }
        } catch (ActivityNotFoundException | SecurityException error) {
            error(getString(R.string.permission_launch_error, error.getMessage()));
        }
    }

    private void notificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && (!Prefs.get(this).getBoolean("notificationAsked", false)
                    || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
                Prefs.get(this).edit().putBoolean("notificationAsked", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            } else {
                startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.ERRORS));
            }
        } catch (ActivityNotFoundException | SecurityException failure) {
            error(getString(R.string.permission_launch_error, failure.getMessage()));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && Environment.isExternalStorageManager()) {
            requestPermissionsFlow();
        }
        showScreen();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 101 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            notificationSettings();
        }
        refresh();
    }

    private void toggleWatch() {
        if (!permitted(this)) {
            showScreen();
            return;
        }
        if (Prefs.get(this).getBoolean("enabled", false)) {
            Prefs.get(this).edit().putBoolean("enabled", false).apply();
            Prefs.status(this, getString(R.string.stopping));
            startService(new Intent(this, WatchService.class).setAction(WatchService.STOP));
            return;
        }
        try {
            MoveEngine engine = new MoveEngine(Environment.getExternalStorageDirectory(),
                    new File(source.getText().toString().trim()),
                    new File(destination.getText().toString().trim()));
            Prefs.get(this).edit().putString("source", engine.source.toString())
                    .putString("destination", engine.destination.toString())
                    .putBoolean("enabled", true).apply();
            Prefs.status(this, getString(R.string.starting));
            startForegroundService(new Intent(this, WatchService.class));
        } catch (IOException | RuntimeException failure) {
            Prefs.get(this).edit().putBoolean("enabled", false).apply();
            Prefs.status(this, getString(R.string.error_status, failure.getMessage()));
            error(failure.getMessage());
        }
        refresh();
    }

    private void error(String message) {
        new AlertDialog.Builder(this).setTitle(R.string.app_name)
                .setMessage(message).setPositiveButton(android.R.string.ok, null).show();
    }
}
