package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.adb.AppInfo;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Callback;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class LogcatTab {
    private static final int MAX_VISIBLE_LINES = 3_000;
    private static final int MAX_PENDING_LINES = 8_000;
    private static final int MAX_LINES_PER_FLUSH = 60;
    private static final long FLUSH_INTERVAL_MS = 100;
    private static final int SCROLL_EVERY_N_FLUSHES = 2;
    private static final String TERMINAL_STYLE =
            "-fx-control-inner-background: #1e1e1e; -fx-background-color: #1e1e1e; "
                    + "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;";

    private LogcatTab() {
    }

    private record LevelOption(String value, String label) {
        static LevelOption none() {
            return new LevelOption("", "无");
        }

        static LevelOption of(String value, String label) {
            return new LevelOption(value, label);
        }
    }

    public static BorderPane build(AppContext app) {
        ObservableList<String> logLines = FXCollections.observableArrayList();
        ListView<String> logView = new ListView<>(logLines);
        logView.setFixedCellSize(20);
        logView.setStyle(TERMINAL_STYLE);
        logView.setCellFactory(logCellFactory());
        VBox.setVgrow(logView, Priority.ALWAYS);

        ComboBox<LevelOption> levelSelect = createLevelCombo();
        levelSelect.setPromptText("级别");
        ComboBox<AppInfo> targetSelect = new ComboBox<>();
        targetSelect.setPromptText("目标应用");
        targetSelect.setCellFactory(appCellFactory());
        targetSelect.setButtonCell(appCellFactory().call(null));
        UiLayout.fillWidth(targetSelect);
        app.getShared().addAppsListener(apps -> Platform.runLater(() -> syncTargetSelect(targetSelect, apps)));

        TextField tagInput = new TextField();
        tagInput.setPromptText("Log Tag（可选）");
        UiLayout.fillWidth(tagInput);

        AtomicBoolean running = new AtomicBoolean(false);
        AtomicReference<Process> processRef = new AtomicReference<>();
        AtomicInteger flushCount = new AtomicInteger();

        Runnable start = () -> {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            flushCount.set(0);
            LevelOption level = levelSelect.getValue();
            AppInfo target = targetSelect.getValue();
            String tag = tagInput.getText();

            Async.run(() -> {
                Thread stderrThread = null;
                ThrottledLineBuffer buffer = new ThrottledLineBuffer(batch -> {
                    logLines.addAll(batch);
                    trimLines(logLines);
                    if (flushCount.incrementAndGet() % SCROLL_EVERY_N_FLUSHES == 0 && !logLines.isEmpty()) {
                        logView.scrollTo(logLines.size() - 1);
                    }
                }, MAX_PENDING_LINES, MAX_LINES_PER_FLUSH, FLUSH_INTERVAL_MS);
                try {
                    var check = app.getAdb().hasDevice();
                    if (!check.connected()) {
                        Platform.runLater(() -> app.getLogger().log(AppContext.SOURCE_LOGCAT, "未检测到ADB设备"));
                        running.set(false);
                        return;
                    }

                    String uid = null;
                    if (target != null && (tag == null || tag.isBlank())) {
                        try {
                            uid = app.getAdb().fetchPackageUid(target.getPackageName());
                        } catch (Exception e) {
                            Platform.runLater(() -> app.getLogger().log(AppContext.SOURCE_LOGCAT,
                                    "无法获取UID，回退为Tag过滤: %s", e.getMessage()));
                        }
                    }

                    String[] logcatArgs = buildLogcatArgs(level, target, tag, uid);
                    Process process = app.getAdb().logcatStream(logcatArgs);
                    processRef.set(process);
                    stderrThread = startDrainThread(process.getErrorStream());

                    Platform.runLater(() -> app.getLogger().log(AppContext.SOURCE_LOGCAT,
                            "Logcat已开始: %s", formatLogcatCommand(logcatArgs)));

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 256 * 1024)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            buffer.append(line);
                        }
                    } finally {
                        buffer.flushNow();
                        process.destroyForcibly();
                        processRef.set(null);
                        running.set(false);
                        Platform.runLater(() -> {
                            app.getLogger().log(AppContext.SOURCE_LOGCAT, "Logcat已停止");
                            if (!logLines.isEmpty()) {
                                logView.scrollTo(logLines.size() - 1);
                            }
                        });
                    }
                } catch (Exception e) {
                    running.set(false);
                    Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_LOGCAT, e));
                } finally {
                    if (stderrThread != null) {
                        try {
                            stderrThread.join(500);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
        };

        Button startBtn = new Button("开始");
        startBtn.setOnAction(e -> start.run());
        Button stopBtn = new Button("停止");
        stopBtn.setOnAction(e -> {
            Process p = processRef.getAndSet(null);
            if (p != null) {
                p.destroyForcibly();
            }
            running.set(false);
        });
        Button clearBtn = new Button("清空");
        clearBtn.setOnAction(e -> logLines.clear());
        Button exportBtn = new Button("导出");
        exportBtn.setOnAction(e -> exportLog(app, logLines));

        HBox filters = UiLayout.toolbar(
                levelSelect, targetSelect, tagInput,
                startBtn, stopBtn, clearBtn, exportBtn);
        filters.setPadding(UiLayout.COMPACT_PADDING);
        UiLayout.fillWidth(tagInput);

        BorderPane pane = new BorderPane(logView);
        pane.setTop(filters);
        return pane;
    }

    private static Callback<ListView<String>, ListCell<String>> logCellFactory() {
        return lv -> new ListCell<>() {
            @Override
            protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                if (empty || line == null) {
                    setText(null);
                    setTextFill(Color.web("#d4d4d4"));
                    return;
                }
                String display = AnsiColorParser.stripAnsi(line);
                setText(display);
                setTextFill(AnsiColorParser.colorForLogLevel(display));
            }
        };
    }

    private static void trimLines(ObservableList<String> lines) {
        int extra = lines.size() - MAX_VISIBLE_LINES;
        if (extra <= 0) {
            return;
        }
        lines.remove(0, extra);
    }

    private static ComboBox<LevelOption> createLevelCombo() {
        ComboBox<LevelOption> combo = new ComboBox<>(FXCollections.observableArrayList(
                LevelOption.none(),
                LevelOption.of("E", "仅错误"),
                LevelOption.of("W", "警告及以上"),
                LevelOption.of("I", "信息及以上"),
                LevelOption.of("D", "调试及以上"),
                LevelOption.of("V", "详细日志")
        ));
        combo.setPrefWidth(120);
        combo.setButtonCell(levelCellFactory().call(null));
        combo.setCellFactory(levelCellFactory());
        combo.getSelectionModel().selectFirst();
        return combo;
    }

    private static Callback<ListView<LevelOption>, ListCell<LevelOption>> levelCellFactory() {
        return lv -> new ListCell<>() {
            @Override
            protected void updateItem(LevelOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        };
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

    private static void syncTargetSelect(ComboBox<AppInfo> targetSelect, List<AppInfo> apps) {
        String selectedPkg = targetSelect.getValue() != null ? targetSelect.getValue().getPackageName() : null;
        List<AppInfo> sorted = apps.stream()
                .sorted(Comparator.comparing(AppInfo::getAppName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AppInfo::getPackageName))
                .toList();
        targetSelect.getItems().setAll(sorted);
        if (selectedPkg != null) {
            sorted.stream()
                    .filter(a -> selectedPkg.equals(a.getPackageName()))
                    .findFirst()
                    .ifPresent(a -> targetSelect.getSelectionModel().select(a));
        }
    }

    private static Thread startDrainThread(InputStream stream) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // discard stderr to avoid blocking the adb process
                }
            } catch (Exception ignored) {
            }
        }, "logcat-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void exportLog(AppContext app, ObservableList<String> logLines) {
        String content = String.join("\n", logLines);
        if (content.isBlank()) {
            app.getLogger().log(AppContext.SOURCE_LOGCAT, "没有可导出的日志");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出Logcat日志");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("文本文件", "*.txt", "*.log"));
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        chooser.setInitialFileName("logcat_" + timestamp + ".txt");

        File file = chooser.showSaveDialog(app.getStage());
        if (file == null) {
            return;
        }
        String path = file.getAbsolutePath();
        if (!path.contains(".")) {
            path += ".txt";
            file = new File(path);
        }

        File target = file;
        Async.run(() -> {
            try {
                Files.writeString(target.toPath(), content, StandardCharsets.UTF_8);
                Platform.runLater(() -> app.getLogger().log(AppContext.SOURCE_LOGCAT,
                        "已导出 %d字符到 %s", content.length(), target.getAbsolutePath()));
            } catch (Exception ex) {
                Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_LOGCAT, ex));
            }
        });
    }

    static String[] buildLogcatArgs(LevelOption level, AppInfo target, String tagInput, String uid) {
        List<String> args = new java.util.ArrayList<>();
        args.add("logcat");
        args.add("-v");
        args.add("brief");

        String tagVal = tagInput != null ? tagInput.trim() : "";
        String levelVal = level != null ? level.value() : "";

        if (!tagVal.isBlank()) {
            if (!levelVal.isBlank()) {
                args.add(tagVal + ":" + levelVal.trim());
            } else {
                args.add("-s");
                args.add(tagVal);
            }
        } else if (uid != null && !uid.isBlank()) {
            args.add("--uid=" + uid.trim());
            if (!levelVal.isBlank()) {
                args.add("*:" + levelVal.trim());
            }
        } else if (target != null) {
            String pkg = target.getPackageName();
            if (!levelVal.isBlank()) {
                args.add(pkg + ":" + levelVal.trim());
            } else {
                args.add("-s");
                args.add(pkg);
            }
        } else if (!levelVal.isBlank()) {
            args.add("*:" + levelVal.trim());
        }

        args.add("-D");

        return args.toArray(String[]::new);
    }

    private static String formatLogcatCommand(String[] args) {
        return String.join(" ", args);
    }
}
