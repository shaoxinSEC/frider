package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.adb.AppInfo;
import com.androidrev.guistudio.frida.FridaScript;
import com.androidrev.guistudio.frida.FridaTools;
import com.androidrev.guistudio.frida.RunOptions;
import com.androidrev.guistudio.exec.ProcessUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FridaTab {
    private FridaTab() {
    }

    public static BorderPane build(AppContext app) {
        ObservableList<FridaScript> scripts = FXCollections.observableArrayList();
        FridaInjectionTracker injections = new FridaInjectionTracker();
        ListView<FridaScript> list = new ListView<>(scripts);
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(FridaScript item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String desc = item.getDescription().isBlank() ? "(无描述)" : item.getDescription();
                    String text = item.getName() + " — " + desc;
                    if (injections.isRunning(item.getName())) {
                        int count = injections.countForScript(item.getName());
                        text += count > 1 ? "  ● 运行中 ×" + count : "  ● 运行中";
                    }
                    setText(text);
                }
            }
        });

        ComboBox<AppInfo> appSelect = new ComboBox<>();
        appSelect.setPromptText("目标应用");
        appSelect.setCellFactory(appCellFactory());
        appSelect.setButtonCell(appCellFactory().call(null));
        UiLayout.fillWidth(appSelect);
        app.getShared().addAppsListener(apps -> Platform.runLater(() -> syncAppSelect(appSelect, apps)));

        ComboBox<String> modeSelect = new ComboBox<>(FXCollections.observableArrayList("Attach", "Spawn"));
        modeSelect.setValue("Attach");
        modeSelect.setMaxWidth(Double.MAX_VALUE);

        FridaConnectionPanel connectionPanel = new FridaConnectionPanel(app, true);

        Button runSelectedBtn = new Button("执行选中");
        Button stopBtn = new Button("停止注入");
        UiLayout.fillWidth(modeSelect);

        Label runningLabel = new Label("运行中: 0");
        runningLabel.setStyle("-fx-text-fill: #666;");

        ListView<FridaInjectionTracker.Session> runningList = new ListView<>();
        runningList.setPrefHeight(120);
        runningList.setPlaceholder(new Label("暂无运行中的脚本"));
        runningList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(FridaInjectionTracker.Session item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.label() + "  #" + item.id().substring(0, 8));
                }
            }
        });
        MenuItem stopSessionItem = new MenuItem("停止此会话");
        stopSessionItem.setOnAction(e -> {
            FridaInjectionTracker.Session session = runningList.getSelectionModel().getSelectedItem();
            if (session == null) {
                return;
            }
            injections.stopSessionById(session.id());
            app.getLogger().log(AppContext.SOURCE_FRIDA, "已停止: %s", session.label());
        });
        runningList.setContextMenu(new ContextMenu(stopSessionItem));
        runningList.setOnContextMenuRequested(e ->
                stopSessionItem.setDisable(runningList.getSelectionModel().getSelectedItem() == null));

        injections.setOnChange(() -> Platform.runLater(() -> {
            list.refresh();
            runningList.getItems().setAll(injections.sessions());
            runningLabel.setText("运行中: " + injections.totalCount());
        }));

        Runnable refreshScripts = () -> Async.run(() -> {
            try {
                Path dir = app.scriptsDirAbs();
                var found = com.androidrev.guistudio.frida.FridaClient.listScripts(dir);
                Platform.runLater(() -> {
                    List<String> selectedNames = list.getSelectionModel().getSelectedItems().stream()
                            .map(FridaScript::getName)
                            .toList();
                    scripts.setAll(found);
                    if (!selectedNames.isEmpty()) {
                        list.getSelectionModel().clearSelection();
                        for (FridaScript script : found) {
                            if (selectedNames.contains(script.getName())) {
                                list.getSelectionModel().select(script);
                            }
                        }
                    }
                    app.getLogger().log(AppContext.SOURCE_FRIDA, "已刷新 %d个脚本", found.size());
                });
            } catch (Exception e) {
                Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_FRIDA, e));
            }
        });

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refreshScripts.run());

        MenuItem editItem = new MenuItem("编辑脚本");
        editItem.setOnAction(e -> editScript(app, list.getSelectionModel().getSelectedItem(), refreshScripts));

        MenuItem newItem = new MenuItem("新建脚本");
        newItem.setOnAction(e -> createScript(app, refreshScripts));

        MenuItem deleteItem = new MenuItem("删除脚本");
        deleteItem.setOnAction(e -> deleteScript(app, list.getSelectionModel().getSelectedItem(), refreshScripts));

        MenuItem runItem = new MenuItem("执行选中");
        runItem.setOnAction(e -> runSelectedScripts(app, list, appSelect, modeSelect, injections));

        list.setContextMenu(new ContextMenu(refreshItem, editItem, newItem, deleteItem, runItem));
        list.setOnContextMenuRequested(e -> {
            List<FridaScript> selected = new ArrayList<>(list.getSelectionModel().getSelectedItems());
            boolean hasScript = !selected.isEmpty();
            boolean singleScript = selected.size() == 1;
            editItem.setDisable(!singleScript);
            deleteItem.setDisable(!singleScript);
            runItem.setDisable(!hasScript);
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                runSelectedScripts(app, list, appSelect, modeSelect, injections);
            }
        });

        runSelectedBtn.setOnAction(e -> runSelectedScripts(app, list, appSelect, modeSelect, injections));

        stopBtn.setOnAction(e -> {
            int count = injections.totalCount();
            if (count == 0) {
                app.getLogger().log(AppContext.SOURCE_FRIDA, "当前没有运行中的注入");
                return;
            }
            injections.stopAll();
            app.getLogger().log(AppContext.SOURCE_FRIDA, "已停止注入");
        });

        VBox right = UiLayout.panel(
                connectionPanel.asFormRow(),
                appSelect,
                modeSelect,
                UiLayout.toolbar(runningLabel, runSelectedBtn, stopBtn),
                new Label("运行会话"),
                runningList,
                buildToolPanel(app, appSelect)
        );
        right.setMinWidth(240);
        right.setPrefWidth(300);

        ScrollPane rightScroll = UiLayout.scroll(right);
        rightScroll.setMinWidth(240);
        rightScroll.setPrefWidth(300);

        list.setMinWidth(200);
        BorderPane listPane = new BorderPane(list);
        BorderPane.setMargin(list, UiLayout.COMPACT_PADDING);

        SplitPane split = new SplitPane(listPane, rightScroll);
        split.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        SplitPane.setResizableWithParent(listPane, true);
        SplitPane.setResizableWithParent(rightScroll, true);

        BorderPane content = new BorderPane(split);
        Platform.runLater(() -> split.setDividerPositions(0.62));

        refreshScripts.run();
        return content;
    }

    private static boolean isSpawnMode(ComboBox<String> modeSelect) {
        return "Spawn".equalsIgnoreCase(modeSelect.getValue());
    }

    private static javafx.scene.layout.FlowPane buildToolPanel(AppContext app, ComboBox<AppInfo> appSelect) {
        javafx.scene.layout.FlowPane flow = new javafx.scene.layout.FlowPane(UiLayout.GAP, UiLayout.GAP);
        flow.setPrefWrapLength(280);
        for (FridaTools.Kind tool : FridaTools.devicePanelTools()) {
            Button btn = new Button(tool.getLabel());
            btn.setOnAction(e -> runFridaTool(app, appSelect, tool));
            flow.getChildren().add(btn);
        }
        Button clearDebugBtn = new Button("解除waiting for debug");
        clearDebugBtn.setOnAction(e -> clearWaitingForDebug(app));
        flow.getChildren().add(clearDebugBtn);
        return flow;
    }

    private static void clearWaitingForDebug(AppContext app) {
        Async.run(() -> {
            try {
                var check = app.getAdb().hasDevice();
                if (!check.connected()) {
                    Platform.runLater(() -> app.getLogger().log(AppContext.SOURCE_FRIDA, "未检测到ADB设备"));
                    return;
                }
                app.getAdb().clearDebugApp();
                Platform.runLater(() ->
                        app.getLogger().log(AppContext.SOURCE_FRIDA, "已执行: adb shell am clear-debug-app"));
            } catch (Exception e) {
                Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_FRIDA, e));
            }
        });
    }

    private static void runFridaTool(AppContext app, ComboBox<AppInfo> appSelect, FridaTools.Kind tool) {
        AppInfo targetApp = appSelect.getValue();
        String pkg = targetApp != null ? targetApp.getPackageName() : "";
        String pid = targetApp != null && targetApp.getPid() != null ? targetApp.getPid() : "";
        Async.run(() -> {
            try {
                var check = app.getAdb().hasDevice();
                if (!check.connected()) {
                    Platform.runLater(() -> app.getLogger().log(AppContext.SOURCE_FRIDA, "未检测到ADB设备"));
                    return;
                }
                Platform.runLater(() -> app.getLogger().log(AppContext.SOURCE_FRIDA,
                        "运行 %s: %s", tool.getLabel(), tool.getDescription()));
                Process process = app.getFrida().runTool(tool, pkg, pid);
                watchToolProcess(app, tool.getLabel(), process);
            } catch (Exception e) {
                Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_FRIDA, e));
            }
        });
    }

    private static void watchToolProcess(AppContext app, String toolName, Process process) {
        String tag = toolName + "|";
        Thread stdoutThread = new Thread(
                () -> streamToLog(process.getInputStream(), app, tag + "OUT"), "frida-tool-stdout");
        Thread stderrThread = new Thread(
                () -> streamToLog(process.getErrorStream(), app, tag + "ERR"), "frida-tool-stderr");
        stdoutThread.start();
        stderrThread.start();
        Async.run(() -> {
            try {
                int code = process.waitFor();
                stdoutThread.join();
                stderrThread.join();
                Platform.runLater(() ->
                        app.getLogger().log(AppContext.SOURCE_FRIDA, "%s已结束 (exit %d)", toolName, code));
            } catch (Exception ignored) {
            }
        });
    }

    private static void syncAppSelect(ComboBox<AppInfo> appSelect, List<AppInfo> apps) {
        String selectedPkg = appSelect.getValue() != null ? appSelect.getValue().getPackageName() : null;
        List<AppInfo> sorted = apps.stream()
                .sorted(Comparator.comparing(AppInfo::getAppName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AppInfo::getPackageName))
                .toList();
        appSelect.getItems().setAll(sorted);
        if (selectedPkg != null) {
            sorted.stream()
                    .filter(a -> selectedPkg.equals(a.getPackageName()))
                    .findFirst()
                    .ifPresent(a -> appSelect.getSelectionModel().select(a));
        }
    }

    private static Callback<ListView<AppInfo>, ListCell<AppInfo>> appCellFactory() {
        return lv -> new ListCell<>() {
            @Override
            protected void updateItem(AppInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String name = item.getAppName();
                    if (name == null || name.isBlank() || "N/A".equals(name)) {
                        setText(item.getPackageName());
                    } else {
                        setText(name + " (" + item.getPackageName() + ")");
                    }
                }
            }
        };
    }

    private static void createScript(AppContext app, Runnable rescan) {
        var result = Dialogs.showNewScriptDialog(app.getStage());
        if (!result.ok()) {
            return;
        }
        String name = result.name().trim();
        if (name.isEmpty()) {
            app.getLogger().log(AppContext.SOURCE_FRIDA, "请输入脚本文件名");
            return;
        }
        if (!name.toLowerCase().endsWith(".js")) {
            name += ".js";
        }
        try {
            Path path = app.scriptsDirAbs().resolve(name);
            Files.writeString(path, result.content());
            app.getLogger().log(AppContext.SOURCE_FRIDA, "已保存脚本: %s", name);
            rescan.run();
        } catch (Exception e) {
            Logger.showError(app, AppContext.SOURCE_FRIDA, e);
        }
    }

    private static void editScript(AppContext app, FridaScript script, Runnable rescan) {
        if (script == null) {
            app.getLogger().log(AppContext.SOURCE_FRIDA, "请先选择要编辑的脚本");
            return;
        }
        try {
            String content = Files.readString(Path.of(script.getPath()));
            var result = Dialogs.showEditScriptDialog(app.getStage(), script.getName(), content);
            if (!result.ok()) {
                return;
            }
            Files.writeString(Path.of(script.getPath()), result.content());
            app.getLogger().log(AppContext.SOURCE_FRIDA, "已更新脚本: %s", script.getName());
            rescan.run();
        } catch (Exception e) {
            Logger.showError(app, AppContext.SOURCE_FRIDA, e);
        }
    }

    private static void deleteScript(AppContext app, FridaScript script, Runnable rescan) {
        if (script == null) {
            app.getLogger().log(AppContext.SOURCE_FRIDA, "请先选择要删除的脚本");
            return;
        }
        if (!Dialogs.showConfirm(app.getStage(), "删除脚本", "确定删除 " + script.getName() + " ?")) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(script.getPath()));
            app.getLogger().log(AppContext.SOURCE_FRIDA, "已删除: %s", script.getName());
            rescan.run();
        } catch (Exception e) {
            Logger.showError(app, AppContext.SOURCE_FRIDA, e);
        }
    }

    private static void runSelectedScripts(AppContext app, ListView<FridaScript> list,
                                           ComboBox<AppInfo> appSelect, ComboBox<String> modeSelect,
                                           FridaInjectionTracker injections) {
        List<FridaScript> selected = new ArrayList<>(list.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            app.getLogger().log(AppContext.SOURCE_FRIDA, "请先选择脚本");
            return;
        }
        runScripts(app, selected, appSelect.getValue(), isSpawnMode(modeSelect), injections);
    }

    private static void runScripts(AppContext app, List<FridaScript> scripts, AppInfo targetApp,
                                     boolean spawnMode, FridaInjectionTracker injections) {
        if (scripts == null || scripts.isEmpty()) {
            app.getLogger().log(AppContext.SOURCE_FRIDA, "请先选择脚本");
            return;
        }
        String pkg = targetApp != null ? targetApp.getPackageName() : "";
        String pid = "";
        if (!spawnMode && targetApp != null && targetApp.getPid() != null && !"N/A".equals(targetApp.getPid())) {
            pid = targetApp.getPid();
        }

        final String packageName = pkg;
        final String pidVal = pid;
        final List<String> scriptPaths = scripts.stream().map(FridaScript::getPath).toList();
        final List<String> scriptNames = scripts.stream().map(FridaScript::getName).toList();
        final String logLabel = scriptNames.size() == 1
                ? scriptNames.get(0)
                : String.join(" + ", scriptNames);

        Async.run(() -> {
            try {
                var check = app.getAdb().hasDevice();
                if (!check.connected()) {
                    Platform.runLater(() -> app.getLogger().log(AppContext.SOURCE_FRIDA, "未检测到ADB设备"));
                    return;
                }

                RunOptions opt = new RunOptions();
                opt.setScriptPaths(scriptPaths);
                opt.setPackageName(packageName);
                opt.setPid(pidVal);
                opt.setSpawn(spawnMode);

                Platform.runLater(() -> {
                    if (scriptNames.size() > 1) {
                        app.getLogger().log(AppContext.SOURCE_FRIDA,
                                "执行 %d个脚本 (%s) [运行中: %d]",
                                scriptNames.size(), spawnMode ? "Spawn" : "Attach", injections.totalCount());
                    } else {
                        app.getLogger().log(AppContext.SOURCE_FRIDA,
                                "执行: %s (%s) [运行中: %d]",
                                logLabel, spawnMode ? "Spawn" : "Attach", injections.totalCount());
                    }
                });

                Process process = app.getFrida().runScript(opt);
                FridaInjectionTracker.Session session = injections.register(scriptNames, process);
                watchProcess(app, session, injections);
            } catch (Exception e) {
                Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_FRIDA, e));
            }
        });
    }

    private static void watchProcess(AppContext app, FridaInjectionTracker.Session session,
                                     FridaInjectionTracker injections) {
        Process process = session.process();
        String logLabel = session.label();
        String logPrefix = logLabel + "|";

        Thread stdoutThread = new Thread(
                () -> streamToLog(process.getInputStream(), app, logPrefix + "OUT"), "frida-stdout-" + session.id());
        Thread stderrThread = new Thread(
                () -> streamToLog(process.getErrorStream(), app, logPrefix + "ERR"), "frida-stderr-" + session.id());
        stdoutThread.start();
        stderrThread.start();

        Async.run(() -> {
            try {
                int code = process.waitFor();
                stdoutThread.join();
                stderrThread.join();
                injections.unregister(session);
                Platform.runLater(() ->
                        app.getLogger().log(AppContext.SOURCE_FRIDA, "%s已结束 (exit %d)", logLabel, code));
            } catch (Exception ignored) {
                injections.unregister(session);
            }
        });
    }

    private static void streamToLog(java.io.InputStream stream, AppContext app, String tag) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, ProcessUtil.consoleCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                app.getLogger().log(AppContext.SOURCE_FRIDA, "[%s] %s", tag, line);
            }
        } catch (Exception ignored) {
        }
    }
}
