package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.config.Config;
import com.androidrev.guistudio.config.RedirectRules;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class SettingsTab {
    private SettingsTab() {
    }

    public static javafx.scene.control.ScrollPane build(AppContext app) {
        TextField adbPathField = new TextField();
        TextField scrcpyPathField = new TextField();
        TextField rootCommandField = new TextField();
        TextField fridaClientField = new TextField();
        TextField fridaToolsDirField = new TextField();
        TextField fridaServerPathField = new TextField();
        TextField fridaServerStartField = new TextField();
        TextField fridaPsArgsField = new TextField();
        TextField scriptsDirField = new TextField();
        TextField defaultProxyField = new TextField();

        fridaPsArgsField.setPromptText("-U -a -i");
        fridaToolsDirField.setPromptText("frida-ps、frida-trace 等");
        defaultProxyField.setPromptText("http://192.168.1.1:8080");

        boolean[] loading = {false};
        boolean[] dirty = {false};
        Runnable markDirty = () -> {
            if (!loading[0]) {
                dirty[0] = true;
            }
        };

        ChangeListener<String> dirtyListener = (obs, oldVal, newVal) -> markDirty.run();
        List<TextField> textFields = List.of(
                adbPathField, scrcpyPathField, rootCommandField, fridaClientField, fridaToolsDirField,
                fridaServerPathField, fridaServerStartField, fridaPsArgsField,
                scriptsDirField, defaultProxyField);
        for (TextField field : textFields) {
            field.textProperty().addListener(dirtyListener);
        }

        Runnable loadForm = () -> {
            loading[0] = true;
            Config cfg = app.getConfig();
            adbPathField.setText(nullToEmpty(cfg.getAdbPath()));
            scrcpyPathField.setText(nullToEmpty(cfg.getScrcpyPath()));
            rootCommandField.setText(nullToEmpty(cfg.getRootCommand()));
            fridaClientField.setText(nullToEmpty(cfg.getFridaClientPath()));
            fridaToolsDirField.setText(nullToEmpty(cfg.getFridaToolsDir()));
            fridaServerPathField.setText(nullToEmpty(cfg.getFridaServerPath()));
            fridaServerStartField.setText(nullToEmpty(cfg.getFridaServerStartCommand()));
            fridaPsArgsField.setText(String.join(" ", cfg.getFridaPsArgs()));
            scriptsDirField.setText(nullToEmpty(cfg.getScriptsDir()));
            defaultProxyField.setText(nullToEmpty(cfg.getDefaultProxy()));
            loading[0] = false;
            dirty[0] = false;
        };

        Button saveBtn = new Button("保存");
        saveBtn.setDefaultButton(true);
        saveBtn.setOnAction(e -> Async.run(() -> {
            try {
                app.saveConfig(buildConfig(
                        adbPathField, scrcpyPathField, rootCommandField, fridaClientField, fridaToolsDirField,
                        fridaServerPathField, fridaServerStartField, fridaPsArgsField,
                        scriptsDirField, defaultProxyField));
                Platform.runLater(loadForm);
            } catch (Exception ex) {
                Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_SETTINGS, ex));
            }
        }));

        Button defaultsBtn = new Button("恢复默认");
        defaultsBtn.setOnAction(e -> {
            if (!Dialogs.showConfirm(app.getStage(), "恢复默认", "将表单恢复为默认值（不会立即写入文件），是否继续？")) {
                return;
            }
            loading[0] = true;
            Config defaults = Config.defaultConfig();
            adbPathField.setText(defaults.getAdbPath());
            scrcpyPathField.setText(defaults.getScrcpyPath());
            rootCommandField.setText(defaults.getRootCommand());
            fridaClientField.setText(defaults.getFridaClientPath());
            fridaToolsDirField.setText(defaults.getFridaToolsDir());
            fridaServerPathField.setText(defaults.getFridaServerPath());
            fridaServerStartField.setText(defaults.getFridaServerStartCommand());
            fridaPsArgsField.setText(String.join(" ", defaults.getFridaPsArgs()));
            scriptsDirField.setText(defaults.getScriptsDir());
            defaultProxyField.setText(defaults.getDefaultProxy());
            loading[0] = false;
            markDirty.run();
        });

        GridPane grid = UiLayout.formGrid();
        int row = 0;
        row = UiLayout.addSection(grid, row, "ADB");
        row = UiLayout.addRow(grid, row, "ADB 路径", UiLayout.pathField(app, adbPathField, true));
        row = UiLayout.addRow(grid, row, "scrcpy 路径", UiLayout.pathField(app, scrcpyPathField, true));
        row = UiLayout.addRow(grid, row, "Root 命令", rootCommandField);
        row = UiLayout.addSection(grid, row, "Frida");
        row = UiLayout.addRow(grid, row, "本地 frida", UiLayout.pathField(app, fridaClientField, true));
        row = UiLayout.addRow(grid, row, "frida-tools", UiLayout.pathField(app, fridaToolsDirField, false));
        row = UiLayout.addRow(grid, row, "设备上的 frida", fridaServerPathField);
        row = UiLayout.addRow(grid, row, "启动命令", fridaServerStartField);
        row = UiLayout.addRow(grid, row, "frida-ps 参数", fridaPsArgsField);
        row = UiLayout.addSection(grid, row, "其他");
        row = UiLayout.addRow(grid, row, "脚本目录", UiLayout.pathField(app, scriptsDirField, false));
        UiLayout.addRow(grid, row, "默认代理", defaultProxyField);

        var content = UiLayout.panel(grid, UiLayout.toolbar(saveBtn, defaultsBtn));
        loadForm.run();
        app.addConfigListener(cfg -> Platform.runLater(() -> {
            if (!dirty[0]) {
                loadForm.run();
            }
        }));
        return UiLayout.scroll(content);
    }

    private static Config buildConfig(
            TextField adbPathField,
            TextField scrcpyPathField,
            TextField rootCommandField,
            TextField fridaClientField,
            TextField fridaToolsDirField,
            TextField fridaServerPathField,
            TextField fridaServerStartField,
            TextField fridaPsArgsField,
            TextField scriptsDirField,
            TextField defaultProxyField) {
        Config cfg = new Config();
        cfg.setAdbPath(adbPathField.getText().trim());
        cfg.setScrcpyPath(scrcpyPathField.getText().trim());
        cfg.setRootCommand(rootCommandField.getText().trim());
        cfg.setFridaClientPath(fridaClientField.getText().trim());
        cfg.setFridaToolsDir(fridaToolsDirField.getText().trim());
        cfg.setFridaServerPath(fridaServerPathField.getText().trim());
        cfg.setFridaServerStartCommand(fridaServerStartField.getText().trim());
        cfg.setFridaPsArgs(parseArgs(fridaPsArgsField.getText()));
        cfg.setScriptsDir(scriptsDirField.getText().trim());
        cfg.setDefaultProxy(defaultProxyField.getText().trim());
        cfg.setIptablesRedirectRules(RedirectRules.allTcpRules());
        cfg.validate();
        return cfg;
    }

    private static List<String> parseArgs(String text) {
        if (text == null || text.isBlank()) {
            return List.of("-U", "-a", "-i");
        }
        return Arrays.stream(text.trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
