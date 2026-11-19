package io.github.mylinxi.showerredemption;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static io.github.mylinxi.showerredemption.WaterProtocol.CLOSE;
import static io.github.mylinxi.showerredemption.WaterProtocol.CONFIRM;
import static io.github.mylinxi.showerredemption.WaterProtocol.HANDSHAKE;
import static io.github.mylinxi.showerredemption.WaterProtocol.HANDSHAKE_ALT;
import static io.github.mylinxi.showerredemption.WaterProtocol.OPEN;

// MainActivity checks runtime permissions before entering the BLE flow.
@SuppressLint("MissingPermission")
final class BleController {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long CONNECTION_TIMEOUT = 15_000;
    private static final long WRITE_TIMEOUT = 5_000;

    enum Phase {
        IDLE, SCANNING, CONNECTING, READY, OPENING, VALVE_OPEN, OPEN_UNCONFIRMED,
        CLOSING, CONFIRMING, SETTLEMENT_PENDING
    }

    record Device(String address, String name) {
        String waterNumber() {
            return WaterProtocol.waterNumber(name);
        }
    }

    interface Listener {
        void onStateChanged();
        void onDeviceFound(Device device, int rssi);
        void onConnected(Device device);
        void onValveChanged(Device device);
        void onLog(String level, String message);
        void onReading(String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private Phase phase = Phase.IDLE;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private long scanDeadline;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writer;
    private BluetoothGattCharacteristic notifier;
    private Device device;
    private Device valveDevice;
    private boolean openOnConnect;
    private boolean disposed;
    private Exchange exchange;
    private Integer lastQuantity;

    BleController(Context context, Listener listener, Device valveDevice) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.valveDevice = valveDevice;
        BluetoothManager manager = this.context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    boolean isSupported() {
        return adapter != null;
    }

    boolean isEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    Phase phase() {
        return phase;
    }

    Device device() {
        return device;
    }

    Device valveDevice() {
        return valveDevice;
    }

    int secondsRemaining() {
        return Math.max(0, (int) ((scanDeadline - SystemClock.elapsedRealtime() + 999) / 1000));
    }

    void scan(String targetName) {
        if (phase != Phase.IDLE || valveDevice != null) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("ERROR", "无法启动蓝牙扫描，请检查蓝牙是否开启");
            return;
        }

        Set<String> seen = new HashSet<>();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handler.post(() -> {
                    if (scanCallback != this) return;
                    String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                    if (name == null) name = result.getDevice().getName();
                    if (name == null) return;
                    Device found = new Device(result.getDevice().getAddress(), name);
                    if (targetName != null) {
                        if (targetName.equals(name)) connect(found, false);
                    } else if (name.contains("Wa") && seen.add(found.address())) {
                        listener.onDeviceFound(found, result.getRssi());
                        log("INFO", "发现设备 " + name);
                    }
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                handler.post(() -> {
                    if (scanCallback != this) return;
                    stopScan();
                    log("ERROR", "蓝牙扫描失败（" + errorCode + "）");
                });
            }
        };

        scanDeadline = SystemClock.elapsedRealtime() + (targetName == null ? 60_000 : 15_000);
        changePhase(Phase.SCANNING);
        log("INFO", targetName == null ? "正在搜索附近的控水器" : "正在搜索 " + targetName);
        ScanCallback currentScan = scanCallback;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(null, settings, currentScan);
        } catch (IllegalStateException | SecurityException error) {
            stopScan();
            log("ERROR", "无法启动蓝牙扫描");
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (scanCallback != currentScan) return;
                if (secondsRemaining() == 0) {
                    stopScan();
                    log("INFO", targetName == null ? "蓝牙扫描结束" : "未找到 " + targetName);
                    return;
                }
                listener.onStateChanged();
                handler.postDelayed(this, 1_000);
            }
        }, 1_000);
    }

    void stopScan() {
        if (scanCallback == null) return;
        ScanCallback previous = scanCallback;
        scanCallback = null;
        try {
            scanner.stopScan(previous);
        } catch (IllegalStateException | SecurityException ignored) {
            // The adapter can turn off before the scan is stopped.
        }
        scanner = null;
        changePhase(Phase.IDLE);
    }

    void connect(Device next, boolean autoOpen) {
        if (phase != Phase.IDLE && phase != Phase.SCANNING) return;
        if (valveDevice != null && !valveDevice.address().equals(next.address())) return;
        stopScan();
        device = next;
        openOnConnect = autoOpen && valveDevice == null;
        changePhase(Phase.CONNECTING);
        log("INFO", "正在连接 " + device.name());
        try {
            gatt = adapter.getRemoteDevice(device.address())
                    .connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
        } catch (IllegalArgumentException | SecurityException error) {
            disconnect("无法连接设备");
            return;
        }
        if (gatt == null) {
            disconnect("无法建立蓝牙连接");
            return;
        }
        BluetoothGatt current = gatt;
        handler.postDelayed(() -> {
            if (gatt == current && phase == Phase.CONNECTING) disconnect("设备连接超时");
        }, CONNECTION_TIMEOUT);
    }

    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt source, int status, int newState) {
            handler.post(() -> {
                if (gatt != source) return;
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnect("蓝牙连接已中断（" + status + "）");
                } else if (newState == BluetoothProfile.STATE_CONNECTED && !source.discoverServices()) {
                    disconnect("无法读取设备服务");
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt source, int status) {
            handler.post(() -> {
                if (gatt != source || phase != Phase.CONNECTING) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    disconnect("读取设备服务失败（" + status + "）");
                    return;
                }
                subscribe(source);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> {
                if (gatt != source || phase != Phase.CONNECTING || !CCCD.equals(descriptor.getUuid())
                        || descriptor.getCharacteristic() != notifier) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    disconnect("开启设备通知失败（" + status + "）");
                    return;
                }
                listener.onConnected(device);
                changePhase(valveDevice == null ? Phase.READY : Phase.OPEN_UNCONFIRMED);
                log("SUCCESS", "设备已就绪");
                if (openOnConnect) openValve();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic characteristic,
                                          int status) {
            handler.post(() -> {
                if (gatt != source || writer != characteristic || exchange == null) return;
                Exchange current = exchange;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failExchange(current, "蓝牙写入失败（" + status + "）");
                    return;
                }
                current.written = true;
                if (current.command == CONFIRM || current.reply != null) {
                    completeExchange(current);
                } else {
                    handler.postDelayed(() -> onResponseTimeout(current),
                            current.command == HANDSHAKE ? 5_000 : 10_000);
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            postNotification(source, characteristic, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) postNotification(source, characteristic, value.clone());
        }
    };

    @SuppressWarnings("deprecation")
    private void subscribe(BluetoothGatt source) {
        for (BluetoothGattService service : source.getServices()) {
            if (!service.getUuid().toString().startsWith("0000f1f0")) continue;
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                int properties = characteristic.getProperties();
                if ((properties & (BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) writer = characteristic;
                if ((properties & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notifier = characteristic;
            }
            break;
        }
        if (writer == null || notifier == null) {
            disconnect("未找到控水器的写入或通知特征");
            return;
        }

        BluetoothGattDescriptor descriptor = notifier.getDescriptor(CCCD);
        if (descriptor == null || !source.setCharacteristicNotification(notifier, true)) {
            disconnect("设备无法订阅蓝牙通知");
            return;
        }
        byte[] value = (notifier.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        boolean queued;
        if (Build.VERSION.SDK_INT >= 33) {
            queued = source.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
        } else {
            descriptor.setValue(value);
            queued = source.writeDescriptor(descriptor);
        }
        if (!queued) disconnect("无法发送设备通知设置");
    }

    private void postNotification(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value) {
        handler.post(() -> {
            if (gatt != source || notifier != characteristic || exchange == null) return;
            Exchange current = exchange;
            WaterProtocol.Reply reply = WaterProtocol.parse(value);
            if (reply == null || !current.expects(reply.command())) return;
            current.reply = reply;
            if (current.written) completeExchange(current);
        });
    }

    void openValve() {
        if (phase != Phase.READY || device.waterNumber().isEmpty()) return;
        changePhase(Phase.OPENING);
        send(HANDSHAKE);
    }

    private void sendOpenCommand() {
        setValveDevice(device);
        send(OPEN);
    }

    void closeValve() {
        if (phase == Phase.SETTLEMENT_PENDING) {
            confirmSettlement();
        } else if (phase == Phase.VALVE_OPEN || phase == Phase.OPEN_UNCONFIRMED) {
            changePhase(Phase.CLOSING);
            send(CLOSE);
        }
    }

    private void confirmSettlement() {
        changePhase(Phase.CONFIRMING);
        send(CONFIRM);
    }

    @SuppressWarnings("deprecation")
    private void send(int command) {
        Exchange current = new Exchange(command);
        exchange = current;
        byte[] value = WaterProtocol.command(command, device.waterNumber());
        int writeType = (writer.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        boolean queued;
        if (Build.VERSION.SDK_INT >= 33) {
            queued = gatt.writeCharacteristic(writer, value, writeType) == BluetoothStatusCodes.SUCCESS;
        } else {
            writer.setWriteType(writeType);
            writer.setValue(value);
            queued = gatt.writeCharacteristic(writer);
        }
        if (!queued) {
            failExchange(current, "无法发送蓝牙指令");
            return;
        }
        log("INFO", "已发送 " + Integer.toHexString(command).toUpperCase(java.util.Locale.ROOT));
        handler.postDelayed(() -> {
            if (exchange == current && !current.written) disconnect("蓝牙写入超时");
        }, WRITE_TIMEOUT);
    }

    private void onResponseTimeout(Exchange current) {
        if (exchange != current) return;
        if (current.command == HANDSHAKE) {
            log("WARN", "未收到握手回执，继续开阀");
            sendOpenCommand();
        } else {
            failExchange(current, current.command == OPEN
                    ? "未收到开阀回执，可尝试停止热水阀"
                    : "未收到关阀回执，请重试关阀");
        }
    }

    private void completeExchange(Exchange current) {
        exchange = null;
        switch (current.command) {
            case HANDSHAKE -> sendOpenCommand();
            case OPEN -> {
                changePhase(Phase.VALVE_OPEN);
                log("SUCCESS", "已收到开阀回执");
            }
            case CLOSE -> {
                lastQuantity = current.reply.quantity();
                setValveDevice(null);
                confirmSettlement();
            }
            case CONFIRM -> {
                BluetoothGatt currentGatt = gatt;
                handler.postDelayed(() -> {
                    if (gatt != currentGatt || phase != Phase.CONFIRMING) return;
                    disconnect(null);
                    log("SUCCESS", "关阀流程结束，设备已断开");
                    listener.onReading(lastQuantity == null ? "设备已确认关阀，回执未包含用水量"
                            : WaterProtocol.reading(lastQuantity));
                    lastQuantity = null;
                }, 500);
            }
        }
    }

    private void failExchange(Exchange current, String message) {
        exchange = null;
        log("WARN", message);
        switch (current.command) {
            case HANDSHAKE -> changePhase(Phase.READY);
            case OPEN, CLOSE -> changePhase(Phase.OPEN_UNCONFIRMED);
            case CONFIRM -> changePhase(Phase.SETTLEMENT_PENDING);
        }
    }

    void clearValveRecovery() {
        if (phase == Phase.IDLE && valveDevice != null) {
            setValveDevice(null);
            listener.onStateChanged();
        }
    }

    private void setValveDevice(Device next) {
        valveDevice = next;
        listener.onValveChanged(next);
    }

    void disconnect(String error) {
        BluetoothGatt previous = gatt;
        gatt = null;
        writer = null;
        notifier = null;
        exchange = null;
        device = null;
        if (previous != null) {
            previous.disconnect();
            previous.close();
        }
        changePhase(Phase.IDLE);
        if (error != null) log("WARN", error);
    }

    void dispose() {
        disposed = true;
        stopScan();
        disconnect(null);
        handler.removeCallbacksAndMessages(null);
    }

    private void changePhase(Phase next) {
        phase = next;
        if (!disposed) listener.onStateChanged();
    }

    private void log(String level, String message) {
        if (!disposed) listener.onLog(level, message);
    }

    private static final class Exchange {
        final int command;
        boolean written;
        WaterProtocol.Reply reply;

        Exchange(int command) {
            this.command = command;
        }

        boolean expects(int replyCommand) {
            return command != CONFIRM && (command == replyCommand
                    || command == HANDSHAKE && replyCommand == HANDSHAKE_ALT);
        }
    }
}
