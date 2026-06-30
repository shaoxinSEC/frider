package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.adb.AdbDevice;
import javafx.application.Platform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Global ADB device selection and status for the whole application. */
public final class DeviceManager {
    private static final long POLL_INTERVAL_SECONDS = 3;

    private final AppContext app;
    private final DeviceStatusBar statusBar = new DeviceStatusBar();
    private final Map<String, String> modelCache = new ConcurrentHashMap<>();
    private final List<Consumer<String>> deviceSwitchListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> deviceConnectListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "device-manager");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean polling = new AtomicBoolean(false);

    private volatile boolean deviceConnected;
    private volatile String activeSerial = "";

    public DeviceManager(AppContext app) {
        this.app = app;
        statusBar.setOnDeviceSelected(this::onUserSelectedDevice);
    }

    public DeviceStatusBar getStatusBar() {
        return statusBar;
    }

    public String getActiveSerial() {
        return activeSerial;
    }

    public boolean isDeviceConnected() {
        return deviceConnected;
    }

    public void addDeviceSwitchListener(Consumer<String> listener) {
        deviceSwitchListeners.add(listener);
    }

    public void addDeviceConnectListener(Runnable listener) {
        deviceConnectListeners.add(listener);
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        app.getStage().addEventHandler(javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST, e -> stop());
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void onUserSelectedDevice(String serial) {
        scheduler.execute(() -> switchDevice(serial));
    }

    private void switchDevice(String serial) {
        if (serial == null || serial.isBlank() || serial.equals(activeSerial)) {
            return;
        }
        app.getAdb().setSelectedSerial(serial);
        activeSerial = serial;
        deviceConnected = false;
        app.getLogger().log(AppContext.SOURCE_SYSTEM, "已切换设备 %s", serial);
        for (Consumer<String> listener : deviceSwitchListeners) {
            listener.accept(serial);
        }
    }

    private void tick() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!app.getAdb().isAvailable()) {
                deviceConnected = false;
                updateStatus(DeviceStatusBar.Status.ERROR, List.of(), "");
                return;
            }
            List<AdbDevice> devices = enrichModels(app.getAdb().listDevices());
            if (devices.isEmpty()) {
                handleIdle();
                return;
            }

            ensureSelectedDevice(devices);
            AdbDevice current = findDevice(devices, activeSerial);
            if (current == null) {
                handleIdle();
                return;
            }

            if (!current.isReady()) {
                updateStatus(DeviceStatusBar.Status.ERROR, devices, current.serial());
                if (deviceConnected) {
                    deviceConnected = false;
                    Platform.runLater(() ->
                            app.getLogger().log(AppContext.SOURCE_SYSTEM, "设备 %s 连接异常 (%s)",
                                    current.serial(), current.state()));
                }
                return;
            }

            if (!deviceConnected || !current.serial().equals(activeSerial)) {
                deviceConnected = true;
                activeSerial = current.serial();
                app.getAdb().setSelectedSerial(activeSerial);
                updateStatus(DeviceStatusBar.Status.CONNECTED, devices, activeSerial);
                Platform.runLater(() ->
                        app.getLogger().log(AppContext.SOURCE_SYSTEM, "已连接设备 %s", activeSerial));
                for (Runnable listener : deviceConnectListeners) {
                    listener.run();
                }
            } else {
                updateStatus(DeviceStatusBar.Status.CONNECTED, devices, activeSerial);
            }
        } catch (Exception e) {
            deviceConnected = false;
            try {
                List<AdbDevice> devices = enrichModels(app.getAdb().listDevices());
                String serial = activeSerial.isBlank() && !devices.isEmpty()
                        ? devices.get(0).serial()
                        : activeSerial;
                updateStatus(DeviceStatusBar.Status.ERROR, devices, serial);
            } catch (Exception ignored) {
                updateStatus(DeviceStatusBar.Status.ERROR, List.of(), activeSerial);
            }
            Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_SYSTEM, e));
        } finally {
            polling.set(false);
        }
    }

    private void handleIdle() {
        if (deviceConnected || !activeSerial.isBlank()) {
            deviceConnected = false;
            activeSerial = "";
            app.getAdb().setSelectedSerial(null);
            Platform.runLater(() ->
                    app.getLogger().log(AppContext.SOURCE_SYSTEM, "设备已断开"));
        }
        updateStatus(DeviceStatusBar.Status.IDLE, List.of(), "");
    }

    private void ensureSelectedDevice(List<AdbDevice> devices) {
        if (activeSerial != null && !activeSerial.isBlank()) {
            for (AdbDevice device : devices) {
                if (device.serial().equals(activeSerial)) {
                    return;
                }
            }
        }
        AdbDevice ready = devices.stream().filter(AdbDevice::isReady).findFirst().orElse(devices.get(0));
        activeSerial = ready.serial();
        app.getAdb().setSelectedSerial(activeSerial);
    }

    private static AdbDevice findDevice(List<AdbDevice> devices, String serial) {
        for (AdbDevice device : devices) {
            if (device.serial().equals(serial)) {
                return device;
            }
        }
        return null;
    }

    private List<AdbDevice> enrichModels(List<AdbDevice> devices) {
        List<AdbDevice> enriched = new ArrayList<>(devices.size());
        for (AdbDevice device : devices) {
            String model = modelCache.get(device.serial());
            if (model == null && device.isReady()) {
                try {
                    model = app.getAdb().fetchDeviceModel(device.serial());
                    modelCache.put(device.serial(), model);
                } catch (IOException | InterruptedException e) {
                    model = device.serial();
                }
            }
            if (model == null) {
                model = device.serial();
            }
            enriched.add(new AdbDevice(device.serial(), device.state(), model));
        }
        return enriched;
    }

    private void updateStatus(DeviceStatusBar.Status status, List<AdbDevice> devices, String serial) {
        statusBar.update(status, devices, serial);
    }
}
