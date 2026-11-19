package io.github.mylinxi.showerredemption;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

import static io.github.mylinxi.showerredemption.BleController.Phase;

public final class MainActivity extends Activity implements BleController.Listener {
    private static final int REQUEST_PERMISSIONS = 11;
    private static final int REQUEST_ENABLE_BLUETOOTH = 12;
    private static final int REQUEST_ENABLE_LOCATION = 13;
    private static final int BACKGROUND = 0xFF101718;
    private static final int CARD = 0xFF1B2527;
    private static final int SURFACE = 0xFF273638;
    private static final int TEXT = 0xFFF1F7F6;
    private static final int MUTED = 0xFFADC1BF;
    private static final int MINT = 0xFF83DDCE;
    private static final int AMBER = 0xFFFFCE90;

    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private SharedPreferences preferences;
    private BleController bluetooth;
    private Runnable pendingBluetoothAction;
    private TextView status;
    private TextView emptyDevices;
    private TextView logText;
    private TextView devicesHeading;
    private LinearLayout deviceRows;
    private ScrollView logScroll;
    private Button scanButton;
    private Button reconnectButton;
    private Button openButton;
    private Button closeButton;
    private Button disconnectButton;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("water_devices", MODE_PRIVATE);
        bluetooth = new BleController(this, this, readDevice("valve"));
        setContentView(createContent());
        onStateChanged();
        onLog("INFO", bluetooth.isSupported() ? "选择扫码或搜索附近的控水器" : "此设备不支持蓝牙低功耗");
    }

    @SuppressWarnings("deprecation")
    private View createContent() {
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            } else {
                view.setPadding(windowInsets.getSystemWindowInsetLeft(), windowInsets.getSystemWindowInsetTop(),
                        windowInsets.getSystemWindowInsetRight(), windowInsets.getSystemWindowInsetBottom());
            }
            return windowInsets;
        });

        TextView title = label(getString(R.string.app_name), 24, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.addView(title);

        ScrollView body = new ScrollView(this);
        body.setFillViewport(true);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = column();
        content.setPadding(dp(16), 0, dp(16), dp(8));
        body.addView(content);

        LinearLayout stateCard = addCard(content);
        stateCard.addView(heading("蓝牙状态"));
        status = label("", 19, MINT);
        status.setPadding(0, dp(5), 0, 0);
        stateCard.addView(status);
        disconnectButton = button("断开设备", SURFACE, TEXT,
                () -> {
                    if (bluetooth.phase() == Phase.IDLE) clearRecovery();
                    else bluetooth.disconnect(null);
                });
        stateCard.addView(disconnectButton, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout devicesCard = addCard(content);
        devicesHeading = heading(getString(R.string.devices_count, 0));
        devicesCard.addView(devicesHeading);
        emptyDevices = label("暂无设备", 14, MUTED);
        emptyDevices.setPadding(0, dp(8), 0, 0);
        devicesCard.addView(emptyDevices);
        deviceRows = column();
        devicesCard.addView(deviceRows);

        LinearLayout logsCard = addCard(content);
        logsCard.addView(heading("运行日志"));
        logScroll = new ScrollView(this);
        logScroll.setNestedScrollingEnabled(true);
        logsCard.addView(logScroll, new LinearLayout.LayoutParams(-1, dp(132)));
        logText = label("", 13, MUTED);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setPadding(0, dp(6), 0, 0);
        logScroll.addView(logText);

        LinearLayout actions = column();
        actions.setPadding(dp(16), 0, dp(16), dp(10));
        root.addView(actions);
        scanButton = button("扫码 / 搜索设备", MINT, BACKGROUND, this::openScanMenu);
        reconnectButton = button("连接上次的设备", SURFACE, TEXT, this::reconnect);
        openButton = button("启动热水阀", SURFACE, MINT, bluetooth::openValve);
        closeButton = button("停止热水阀", SURFACE, AMBER, bluetooth::closeValve);
        addButtons(actions, scanButton, reconnectButton);
        addButtons(actions, openButton, closeButton);
        return root;
    }

    private void openScanMenu() {
        if (bluetooth.phase() == Phase.SCANNING) {
            bluetooth.stopScan();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("连接设备")
                .setItems(new String[]{"扫描二维码", "搜索附近设备"}, (dialog, option) -> {
                    if (option == 0) {
                        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                            toast("此设备没有可用的摄像头，可使用蓝牙搜索");
                            return;
                        }
                        new IntentIntegrator(this)
                                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                                .setPrompt("扫描控水器机身上的二维码")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                                .initiateScan();
                    } else {
                        withBluetooth(true, () -> startScan(null));
                    }
                }).show();
    }

    private void startScan(String targetName) {
        clearDeviceList();
        bluetooth.scan(targetName);
    }

    private void reconnect() {
        BleController.Device recovery = bluetooth.valveDevice();
        BleController.Device target = recovery == null ? readDevice("last") : recovery;
        if (target != null) withBluetooth(false, () -> bluetooth.connect(target, recovery == null));
    }

    private void clearRecovery() {
        new AlertDialog.Builder(this)
                .setTitle("确认阀门已关闭")
                .setMessage("请在现场确认阀门已关闭，再清除待关阀状态。")
                .setNegativeButton("取消", null)
                .setPositiveButton("已确认", (dialog, which) -> bluetooth.clearValveRecovery())
                .show();
    }

    @SuppressLint("MissingPermission")
    private void withBluetooth(boolean needsScan, Runnable action) {
        if (!bluetooth.isSupported()) {
            toast("此设备不支持蓝牙低功耗");
            return;
        }
        pendingBluetoothAction = () -> withBluetooth(needsScan, action);
        String[] permissions;
        if (Build.VERSION.SDK_INT >= 31) {
            permissions = new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else {
            permissions = needsScan ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION} : new String[0];
        }
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permissions, REQUEST_PERMISSIONS);
                return;
            }
        }
        if (!bluetooth.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
            return;
        }
        if (needsScan && Build.VERSION.SDK_INT < 31 && !locationEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("开启系统定位")
                    .setMessage("当前 Android 版本需要开启系统定位才能搜索蓝牙设备。")
                    .setOnCancelListener(dialog -> pendingBluetoothAction = null)
                    .setNegativeButton("取消", (dialog, which) -> pendingBluetoothAction = null)
                    .setPositiveButton("前往设置", (dialog, which) -> startActivityForResult(
                            new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), REQUEST_ENABLE_LOCATION))
                    .show();
            return;
        }
        pendingBluetoothAction = null;
        action.run();
    }

    @SuppressWarnings("deprecation")
    private boolean locationEnabled() {
        if (Build.VERSION.SDK_INT >= 28) {
            LocationManager manager = getSystemService(LocationManager.class);
            return manager != null && manager.isLocationEnabled();
        }
        return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != REQUEST_PERMISSIONS || pendingBluetoothAction == null) return;
        if (grants.length == 0) {
            pendingBluetoothAction = null;
            return;
        }
        for (int grant : grants) {
            if (grant != PackageManager.PERMISSION_GRANTED) {
                pendingBluetoothAction = null;
                toast("请在系统设置授予所需权限后重试");
                return;
            }
        }
        resumeBluetoothAction();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) resumeBluetoothAction();
            else pendingBluetoothAction = null;
            return;
        }
        if (requestCode == REQUEST_ENABLE_LOCATION) {
            if (locationEnabled()) resumeBluetoothAction();
            else pendingBluetoothAction = null;
            return;
        }
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan == null || scan.getContents() == null) return;
        String target = WaterProtocol.nameFromQr(scan.getContents());
        if (target == null) {
            toast("设备二维码应为 10 位数字");
            return;
        }
        withBluetooth(true, () -> startScan(target));
    }

    private void resumeBluetoothAction() {
        Runnable action = pendingBluetoothAction;
        pendingBluetoothAction = null;
        if (action != null) action.run();
    }

    @Override
    public void onStateChanged() {
        Phase phase = bluetooth.phase();
        BleController.Device recovery = bluetooth.valveDevice();
        BleController.Device connected = bluetooth.device();
        boolean waiting = phase == Phase.IDLE && recovery != null;
        String statusText = switch (phase) {
            case IDLE -> waiting ? "连接已中断 · 待确认关阀" : "未连接";
            case SCANNING -> "正在扫描 · " + bluetooth.secondsRemaining() + " 秒";
            case CONNECTING -> "正在连接 " + connected.name();
            case READY -> connected.waterNumber().isEmpty() ? "已连接 · 设备名无水号"
                    : "已连接 · 水号 " + connected.waterNumber();
            case OPENING -> "正在启动热水阀";
            case VALVE_OPEN -> "热水阀已开启";
            case OPEN_UNCONFIRMED -> "阀门状态未确认";
            case CLOSING -> "正在关闭热水阀";
            case CONFIRMING -> "正在确认结算";
            case SETTLEMENT_PENDING -> "已关阀 · 待确认结算";
        };
        status.setText(statusText);
        status.setTextColor(waiting || phase == Phase.OPEN_UNCONFIRMED || phase == Phase.SETTLEMENT_PENDING
                ? AMBER : MINT);
        scanButton.setText(phase == Phase.SCANNING ? "停止扫描" : "扫码 / 搜索设备");
        scanButton.setEnabled(bluetooth.isSupported() && !waiting
                && (phase == Phase.IDLE || phase == Phase.SCANNING));
        reconnectButton.setText(waiting ? "重连待关阀设备" : "连接上次的设备");
        reconnectButton.setEnabled(bluetooth.isSupported() && (phase == Phase.IDLE || phase == Phase.SCANNING)
                && (recovery != null || readDevice("last") != null));
        openButton.setEnabled(phase == Phase.READY && !connected.waterNumber().isEmpty());
        closeButton.setText(phase == Phase.SETTLEMENT_PENDING ? "重试结算确认" : "停止热水阀");
        closeButton.setEnabled(phase == Phase.VALVE_OPEN || phase == Phase.OPEN_UNCONFIRMED
                || phase == Phase.SETTLEMENT_PENDING);
        disconnectButton.setVisibility(waiting || phase == Phase.CONNECTING || phase == Phase.READY
                || phase == Phase.SETTLEMENT_PENDING ? View.VISIBLE : View.GONE);
        disconnectButton.setText(waiting ? "已手动关阀" : phase == Phase.CONNECTING ? "取消连接" : "断开设备");
        deviceRows.setVisibility(!waiting && (phase == Phase.IDLE || phase == Phase.SCANNING)
                ? View.VISIBLE : View.GONE);
        if (recovery != null && phase != Phase.IDLE) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onDeviceFound(BleController.Device device, int rssi) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(8);
        deviceRows.addView(row, rowParams);

        LinearLayout detail = column();
        detail.addView(label(device.name(), 15, TEXT));
        detail.addView(label(device.address() + " · " + rssi + " dBm", 12, MUTED));
        row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
        Button connect = button("连接", SURFACE, MINT,
                () -> withBluetooth(false, () -> bluetooth.connect(device, false)));
        row.addView(connect, new LinearLayout.LayoutParams(-2, -2));

        emptyDevices.setVisibility(View.GONE);
        devicesHeading.setText(getString(R.string.devices_count, deviceRows.getChildCount()));
    }

    private void clearDeviceList() {
        deviceRows.removeAllViews();
        emptyDevices.setVisibility(View.VISIBLE);
        devicesHeading.setText(getString(R.string.devices_count, 0));
    }

    @Override
    public void onConnected(BleController.Device device) {
        saveDevice("last", device);
        clearDeviceList();
    }

    @Override
    public void onValveChanged(BleController.Device device) {
        saveDevice("valve", device);
    }

    @Override
    public void onLog(String level, String message) {
        if (logs.size() == 150) logs.removeFirst();
        logs.addLast(clock.format(new Date()) + "  " + level + "  " + message);
        logText.setText(TextUtils.join("\n", logs));
        logScroll.post(() -> logScroll.scrollTo(0, Math.max(0, logText.getHeight() - logScroll.getHeight())));
    }

    @Override
    public void onReading(String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("本次用水")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show();
    }

    private BleController.Device readDevice(String key) {
        String address = preferences.getString(key + "_address", null);
        return address == null ? null : new BleController.Device(address, preferences.getString(key + "_name", ""));
    }

    private void saveDevice(String key, BleController.Device device) {
        SharedPreferences.Editor editor = preferences.edit();
        if (device == null) {
            editor.remove(key + "_address").remove(key + "_name");
        } else {
            editor.putString(key + "_address", device.address()).putString(key + "_name", device.name());
        }
        editor.apply();
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout addCard(LinearLayout parent) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(rounded(CARD, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        parent.addView(card, params);
        return card;
    }

    private TextView heading(String value) {
        TextView text = label(value, 14, TEXT);
        text.setTypeface(null, Typeface.BOLD);
        return text;
    }

    private TextView label(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private Button button(String title, int color, int textColor, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{MUTED, textColor}));
        GradientDrawable shape = rounded(Color.WHITE, 10);
        ColorStateList fills = new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{CARD, color});
        shape.setColor(fills);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x33000000), shape, null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void addButtons(LinearLayout parent, Button left, Button right) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(6);
        parent.addView(row, rowParams);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -2, 1);
        leftParams.rightMargin = dp(8);
        row.addView(left, leftParams);
        row.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        bluetooth.dispose();
        super.onDestroy();
    }
}
