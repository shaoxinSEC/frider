package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.config.Config;
import com.androidrev.guistudio.frida.FridaConnection;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 设置页与Frida管理页共用的连接方式控件；两处UI保持同步。
 * 布局：USB连接 / 端口连接 + HOST + PORT（与设置页示意图一致）。
 */
public final class FridaConnectionPanel {
    private static final String TEXT_STYLE = "-fx-text-fill: #000000;";
    private static final CopyOnWriteArrayList<FridaConnectionPanel> REGISTRY = new CopyOnWriteArrayList<>();

    private final AppContext app;
    private final boolean persistOnChange;
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioButton usbRadio = new RadioButton("USB连接");
    private final RadioButton remoteRadio = new RadioButton("端口连接");
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final VBox optionsBox;
    private boolean suppressEvents;

    public FridaConnectionPanel(AppContext app, boolean persistOnChange) {
        this.app = app;
        this.persistOnChange = persistOnChange;
        REGISTRY.add(this);

        styleControl(usbRadio);
        styleControl(remoteRadio);
        styleControl(hostField);
        styleControl(portField);

        usbRadio.setToggleGroup(modeGroup);
        remoteRadio.setToggleGroup(modeGroup);
        hostField.setPromptText("HOST");
        portField.setPromptText("PORT");
        hostField.setPrefColumnCount(14);
        portField.setPrefColumnCount(6);
        hostField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(hostField, Priority.ALWAYS);

        HBox usbRow = new HBox(usbRadio);
        usbRow.setAlignment(Pos.CENTER_LEFT);

        HBox portRow = new HBox(UiLayout.GAP, remoteRadio, hostField, portField);
        portRow.setAlignment(Pos.CENTER_LEFT);

        optionsBox = new VBox(6, usbRow, portRow);
        optionsBox.getStyleClass().add("frida-connection-panel");

        Runnable onUserChange = () -> {
            if (suppressEvents) {
                return;
            }
            updateRemoteFieldsEnabled();
            broadcastFrom(this);
            if (persistOnChange) {
                persistQuietly();
            }
        };

        modeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> onUserChange.run());
        hostField.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (Boolean.FALSE.equals(focused) && remoteRadio.isSelected()) {
                onUserChange.run();
            }
        });
        portField.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (Boolean.FALSE.equals(focused) && remoteRadio.isSelected()) {
                onUserChange.run();
            }
        });

        applyFromConfig(app.getConfig());
        app.addConfigListener(cfg -> Platform.runLater(() -> applyFromConfig(cfg)));
    }

    /** 设置页：左侧已有「连接方式」标签，仅返回选项区。 */
    public VBox getRoot() {
        return optionsBox;
    }

    /** Frida管理页：带左侧「连接方式」标题的行。 */
    public GridPane asFormRow() {
        GridPane row = new GridPane();
        row.setHgap(10);
        row.setVgap(6);
        Label heading = new Label("连接方式");
        heading.setStyle(TEXT_STYLE);
        heading.getStyleClass().add("form-label");
        row.add(heading, 0, 0);
        row.add(optionsBox, 1, 0);
        GridPane.setHgrow(optionsBox, Priority.ALWAYS);
        return row;
    }

    public void applyFromConfig(Config cfg) {
        suppressEvents = true;
        if (FridaConnection.MODE_REMOTE.equals(FridaConnection.normalizeMode(cfg.getFridaConnection()))) {
            remoteRadio.setSelected(true);
            hostField.setText(nullToEmpty(cfg.getFridaRemoteHost()));
            portField.setText(portText(cfg));
        } else {
            usbRadio.setSelected(true);
            hostField.setText(nullToEmpty(cfg.getFridaRemoteHost()));
            portField.setText(portText(cfg));
        }
        if (modeGroup.getSelectedToggle() == null) {
            usbRadio.setSelected(true);
        }
        suppressEvents = false;
        updateRemoteFieldsEnabled();
    }

    public void applyToConfig(Config cfg) {
        if (remoteRadio.isSelected()) {
            cfg.setFridaConnection(FridaConnection.MODE_REMOTE);
            cfg.setFridaRemoteHost(hostField.getText().trim());
            cfg.setFridaRemotePort(portField.getText().trim());
        } else {
            cfg.setFridaConnection(FridaConnection.MODE_USB);
            cfg.setFridaRemoteHost(hostField.getText().trim());
            cfg.setFridaRemotePort(portField.getText().trim());
        }
    }

    private void broadcastFrom(FridaConnectionPanel source) {
        for (FridaConnectionPanel panel : REGISTRY) {
            if (panel != source) {
                panel.copyFrom(source);
            }
        }
    }

    private void copyFrom(FridaConnectionPanel source) {
        suppressEvents = true;
        if (source.remoteRadio.isSelected()) {
            remoteRadio.setSelected(true);
        } else {
            usbRadio.setSelected(true);
        }
        hostField.setText(source.hostField.getText());
        portField.setText(source.portField.getText());
        suppressEvents = false;
        updateRemoteFieldsEnabled();
    }

    private void updateRemoteFieldsEnabled() {
        boolean remote = remoteRadio.isSelected();
        hostField.setDisable(!remote);
        portField.setDisable(!remote);
        styleControl(hostField);
        styleControl(portField);
    }

    private void persistQuietly() {
        Async.run(() -> {
            try {
                Config cfg = app.getConfig().copy();
                applyToConfig(cfg);
                FridaConnection.validate(cfg);
                app.saveConfig(cfg);
            } catch (Exception e) {
                Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_FRIDA, e));
            }
        });
    }

    private static void styleControl(javafx.scene.control.Control control) {
        control.setStyle(TEXT_STYLE);
    }

    private static String portText(Config cfg) {
        String port = cfg.getFridaRemotePort();
        if (port == null || port.isBlank()) {
            return FridaConnection.defaultPort();
        }
        return port.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
