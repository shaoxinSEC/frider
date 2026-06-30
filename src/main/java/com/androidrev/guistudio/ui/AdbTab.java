package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.adb.AdbClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public final class AdbTab {
    private static final DateTimeFormatter FILE_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @FunctionalInterface
    private interface AdbTask {
        void run() throws Exception;
    }

    private AdbTab() {
    }

    public static BorderPane build(AppContext app) {
        TextArea output = new TextArea();
        output.setEditable(false);
        output.setWrapText(true);
        output.setPrefRowCount(12);
        output.setStyle("-fx-font-family: Consolas, 'Courier New', monospace;");

        Runnable clearOutput = () -> Platform.runLater(output::clear);

        // Connection section
        TextField connectField = new TextField();
        connectField.setPromptText("192.168.1.100:5555");

        TextField tcpipField = new TextField("5555");
        tcpipField.setPromptText("TCP 端口");
        tcpipField.setPrefColumnCount(6);
        tcpipField.setMaxWidth(Region.USE_PREF_SIZE);

        Button connectBtn = new Button("连接");
        Button disconnectBtn = new Button("断开");
        Button tcpipBtn = new Button("TCP/IP");

        // App section
        TextField packageField = new TextField();
        packageField.setPromptText("com.example.app");
        CheckBox replaceCheck = new CheckBox("覆盖安装 (-r)");

        // Port forward
        TextField forwardLocalField = new TextField();
        forwardLocalField.setPromptText("本地端口");
        forwardLocalField.setPrefColumnCount(8);
        forwardLocalField.setMaxWidth(Region.USE_PREF_SIZE);
        TextField forwardRemoteField = new TextField();
        forwardRemoteField.setPromptText("远程端口");
        forwardRemoteField.setPrefColumnCount(8);
        forwardRemoteField.setMaxWidth(Region.USE_PREF_SIZE);

        // Shell / custom command
        TextField shellField = new TextField();
        shellField.setPromptText("输入 shell 命令，如 pm list packages -3");

        TextField customField = new TextField();
        customField.setPromptText("自定义 adb 命令，如 devices / version（不含 adb 前缀）");

        Consumer<String> append = text -> Platform.runLater(() -> {
            if (output.getText().isEmpty()) {
                output.setText(text);
            } else {
                output.appendText("\n" + text);
            }
            output.setScrollTop(Double.MAX_VALUE);
        });

        Consumer<AdbTask> runOnDevice = task -> Async.run(() -> {
            try {
                var check = app.getAdb().hasDevice();
                if (!check.connected()) {
                    Platform.runLater(() -> {
                        app.getLogger().log(AppContext.SOURCE_ADB, "未检测到可用设备");
                        append.accept("错误: 未检测到可用设备");
                    });
                    return;
                }
                task.run();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Logger.showError(app, AppContext.SOURCE_ADB, e);
                    append.accept("错误: " + e.getMessage());
                });
            }
        });

        Consumer<AdbTask> runHost = task -> Async.run(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Logger.showError(app, AppContext.SOURCE_ADB, e);
                    append.accept("错误: " + e.getMessage());
                });
            }
        });

        DeviceFileBrowser fileBrowser = DeviceFileBrowser.create(app, task -> runOnDevice.accept(task::run));
        fileBrowser.setActionLog(append::accept);
        fileBrowser.refresh();

        connectBtn.setOnAction(e -> {
            String host = connectField.getText().trim();
            if (host.isEmpty()) {
                app.getLogger().log(AppContext.SOURCE_ADB, "请输入 host:port");
                return;
            }
            runHost.accept(() -> {
                try {
                    app.getAdb().connect(host);
                    Platform.runLater(() -> {
                        app.getLogger().log(AppContext.SOURCE_ADB, "已连接 %s", host);
                        append.accept("$ adb connect " + host + "\n已连接 " + host);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        Logger.showError(app, AppContext.SOURCE_ADB, ex);
                        append.accept("错误: " + ex.getMessage());
                    });
                }
            });
        });

        disconnectBtn.setOnAction(e -> {
            String host = connectField.getText().trim();
            runHost.accept(() -> {
                try {
                    if (host.isEmpty()) {
                        app.getAdb().disconnect();
                    } else {
                        app.getAdb().disconnect(host);
                    }
                    Platform.runLater(() -> {
                        String msg = host.isEmpty() ? "已断开所有连接" : "已断开 " + host;
                        app.getLogger().log(AppContext.SOURCE_ADB, msg);
                        append.accept("$ adb disconnect" + (host.isEmpty() ? "" : " " + host) + "\n" + msg);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        Logger.showError(app, AppContext.SOURCE_ADB, ex);
                        append.accept("错误: " + ex.getMessage());
                    });
                }
            });
        });

        tcpipBtn.setOnAction(e -> runOnDevice.accept(() -> {
            try {
                int port = parsePort(tcpipField.getText(), 5555);
                app.getAdb().enableTcpIp(port);
                Platform.runLater(() -> {
                    app.getLogger().log(AppContext.SOURCE_ADB, "已开启 TCP/IP 模式，端口 %d", port);
                    append.accept("$ adb tcpip " + port + "\n设备已切换到 TCP/IP 模式，端口 " + port);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    Logger.showError(app, AppContext.SOURCE_ADB, ex);
                    append.accept("错误: " + ex.getMessage());
                });
            }
        }));

        // Quick action buttons
        Button rebootBtn = actionButton("重启设备", () -> runOnDevice.accept(() -> {
            app.getAdb().reboot();
            Platform.runLater(() -> {
                app.getLogger().log(AppContext.SOURCE_ADB, "设备重启命令已发送");
                append.accept("$ adb reboot\n设备重启命令已发送");
            });
        }));

        Button bootloaderBtn = actionButton("Bootloader", () -> runOnDevice.accept(() -> {
            app.getAdb().rebootBootloader();
            Platform.runLater(() -> {
                app.getLogger().log(AppContext.SOURCE_ADB, "已重启到 Bootloader");
                append.accept("$ adb reboot bootloader\n已重启到 Bootloader");
            });
        }));

        Button recoveryBtn = actionButton("Recovery", () -> runOnDevice.accept(() -> {
            app.getAdb().rebootRecovery();
            Platform.runLater(() -> {
                app.getLogger().log(AppContext.SOURCE_ADB, "已重启到 Recovery");
                append.accept("$ adb reboot recovery\n已重启到 Recovery");
            });
        }));

        Button screencapBtn = actionButton("截图", () -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("保存截图");
            chooser.setInitialFileName("screenshot_" + LocalDateTime.now().format(FILE_TIME_FMT) + ".png");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG 图片", "*.png"));
            File file = chooser.showSaveDialog(app.getStage());
            if (file == null) {
                return;
            }
            Path path = file.toPath();
            runOnDevice.accept(() -> {
                app.getAdb().screencap(path);
                Platform.runLater(() -> {
                    app.getLogger().log(AppContext.SOURCE_ADB, "截图已保存: %s", path);
                    append.accept("$ adb shell screencap + pull\n截图已保存: " + path.toAbsolutePath());
                });
            });
        });

        Button scrcpyBtn = actionButton("投屏", () -> Async.run(() -> {
            try {
                var check = app.getAdb().hasDevice();
                if (!check.connected()) {
                    Platform.runLater(() -> {
                        app.getLogger().log(AppContext.SOURCE_ADB, "未检测到可用设备");
                        append.accept("错误: 未检测到可用设备");
                    });
                    return;
                }
                String serial = app.getAdb().getSelectedSerial();
                app.getScrcpy().launch(serial);
                String cmdLine = app.getScrcpy().resolvePath()
                        + (serial != null && !serial.isBlank() ? " -s " + serial : "");
                Platform.runLater(() -> {
                    app.getLogger().log(AppContext.SOURCE_ADB, "已启动 scrcpy");
                    append.accept("$ " + cmdLine + "\nscrcpy 已启动");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    Logger.showError(app, AppContext.SOURCE_ADB, ex);
                    append.accept("错误: " + ex.getMessage());
                });
            }
        }));

        Button deviceInfoBtn = actionButton("设备信息", () -> runOnDevice.accept(() -> {
            String info = app.getAdb().getDeviceInfo();
            Platform.runLater(() -> {
                app.getLogger().log(AppContext.SOURCE_ADB, "已获取设备信息");
                append.accept("$ getprop\n" + info);
            });
        }));

        Button versionBtn = actionButton("ADB 版本", () -> runHost.accept(() -> {
            String version = app.getAdb().getVersion();
            Platform.runLater(() -> {
                app.getLogger().log(AppContext.SOURCE_ADB, "ADB 版本查询完成");
                append.accept("$ adb version\n" + version);
            });
        }));

        Button restartAdbBtn = actionButton("重启 ADB 服务", () -> runHost.accept(() -> {
            app.getAdb().killServer();
            app.getAdb().startServer();
            Platform.runLater(() -> {
                app.getLogger().log(AppContext.SOURCE_ADB, "ADB 服务已重启");
                append.accept("$ adb kill-server && adb start-server\nADB 服务已重启");
            });
        }));

        Button installBtn = new Button("安装 APK");
        installBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 APK 文件");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("APK 文件", "*.apk"));
            File file = chooser.showOpenDialog(app.getStage());
            if (file == null) {
                return;
            }
            boolean replace = replaceCheck.isSelected();
            runOnDevice.accept(() -> {
                app.getAdb().install(file.getAbsolutePath(), replace);
                Platform.runLater(() -> {
                    app.getLogger().log(AppContext.SOURCE_ADB, "APK 安装成功: %s", file.getName());
                    append.accept("$ adb install" + (replace ? " -r" : "") + " " + file.getName() + "\n安装成功");
                });
            });
        });

        Button uninstallBtn = new Button("卸载应用");
        uninstallBtn.setOnAction(e -> {
            String pkg = packageField.getText().trim();
            if (pkg.isEmpty()) {
                app.getLogger().log(AppContext.SOURCE_ADB, "请输入包名");
                return;
            }
            runOnDevice.accept(() -> {
                app.getAdb().uninstall(pkg);
                Platform.runLater(() -> {
                    app.getLogger().log(AppContext.SOURCE_ADB, "已卸载: %s", pkg);
                    append.accept("$ adb uninstall " + pkg + "\n卸载成功");
                });
            });
        });

        Button listForwardBtn = new Button("列出转发");
        listForwardBtn.setOnAction(e -> runOnDevice.accept(() -> {
            String list = app.getAdb().listForwards();
            Platform.runLater(() -> {
                app.getLogger().log(AppContext.SOURCE_ADB, "端口转发列表已刷新");
                append.accept("$ adb forward --list\n" + (list.isBlank() ? "(无转发规则)" : list));
            });
        }));

        Button addForwardBtn = new Button("添加转发");
        addForwardBtn.setOnAction(e -> {
            try {
                int local = parsePort(forwardLocalField.getText(), -1);
                int remote = parsePort(forwardRemoteField.getText(), -1);
                if (local <= 0 || remote <= 0) {
                    app.getLogger().log(AppContext.SOURCE_ADB, "请输入有效的端口号");
                    return;
                }
                int finalLocal = local;
                int finalRemote = remote;
                runOnDevice.accept(() -> {
                    app.getAdb().forwardTcp(finalLocal, finalRemote);
                    Platform.runLater(() -> {
                        app.getLogger().log(AppContext.SOURCE_ADB, "已添加转发 tcp:%d -> tcp:%d", finalLocal, finalRemote);
                        append.accept("$ adb forward tcp:" + finalLocal + " tcp:" + finalRemote + "\n转发已添加");
                    });
                });
            } catch (Exception ex) {
                app.getLogger().log(AppContext.SOURCE_ADB, "端口无效: %s", ex.getMessage());
            }
        });

        Button removeForwardBtn = new Button("移除转发");
        removeForwardBtn.setOnAction(e -> {
            try {
                int local = parsePort(forwardLocalField.getText(), -1);
                if (local <= 0) {
                    app.getLogger().log(AppContext.SOURCE_ADB, "请输入要移除的本地端口");
                    return;
                }
                int finalLocal = local;
                runOnDevice.accept(() -> {
                    app.getAdb().forwardRemove(finalLocal);
                    Platform.runLater(() -> {
                        app.getLogger().log(AppContext.SOURCE_ADB, "已移除转发 tcp:%d", finalLocal);
                        append.accept("$ adb forward --remove tcp:" + finalLocal + "\n转发已移除");
                    });
                });
            } catch (Exception ex) {
                app.getLogger().log(AppContext.SOURCE_ADB, "端口无效: %s", ex.getMessage());
            }
        });

        Button shellBtn = new Button("执行 Shell");
        shellBtn.setOnAction(e -> {
            String cmd = shellField.getText().trim();
            if (cmd.isEmpty()) {
                app.getLogger().log(AppContext.SOURCE_ADB, "请输入 shell 命令");
                return;
            }
            runOnDevice.accept(() -> {
                String result = app.getAdb().shellInteractive(cmd);
                Platform.runLater(() -> {
                    app.getLogger().log(AppContext.SOURCE_ADB, "Shell 命令已执行");
                    append.accept("$ adb shell " + cmd + "\n" + (result.isBlank() ? "(无输出)" : result));
                });
            });
        });

        Button customBtn = new Button("执行");
        customBtn.setOnAction(e -> {
            String cmd = customField.getText().trim();
            if (cmd.isEmpty()) {
                app.getLogger().log(AppContext.SOURCE_ADB, "请输入 adb 命令");
                return;
            }
            String[] args = cmd.split("\\s+");
            runHost.accept(() -> {
                AdbClient.CommandResult result = app.getAdb().runResult(args);
                Platform.runLater(() -> {
                    app.getLogger().log(AppContext.SOURCE_ADB, "命令已执行 (exit %d)", result.exitCode());
                    String out = result.combinedOutput();
                    append.accept("$ adb " + cmd + "\n" + (out.isBlank() ? "(exit " + result.exitCode() + ")" : out));
                });
            });
        });

        Button clearBtn = new Button("清除输出");
        clearBtn.setOnAction(e -> clearOutput.run());

        // Layout: left panel with grouped actions
        GridPane deviceGrid = buttonGrid(2, rebootBtn, bootloaderBtn, recoveryBtn, screencapBtn, scrcpyBtn, deviceInfoBtn);
        GridPane serviceGrid = buttonGrid(2, versionBtn, restartAdbBtn);

        GridPane connectGrid = new GridPane();
        connectGrid.setHgap(8);
        connectGrid.setVgap(8);
        connectGrid.add(connectField, 0, 0, 2, 1);
        GridPane.setHgrow(connectField, Priority.ALWAYS);
        connectField.setMaxWidth(Double.MAX_VALUE);
        HBox connectBtnRow = new HBox(8, connectBtn, disconnectBtn, tcpipField, tcpipBtn);
        connectGrid.add(connectBtnRow, 0, 1, 2, 1);
        applyFormColumns(connectGrid, 2);

        GridPane appGrid = new GridPane();
        appGrid.setHgap(8);
        appGrid.setVgap(8);
        appGrid.add(packageField, 0, 0, 2, 1);
        GridPane.setHgrow(packageField, Priority.ALWAYS);
        packageField.setMaxWidth(Double.MAX_VALUE);
        HBox appBtnRow = new HBox(8, uninstallBtn, installBtn, replaceCheck);
        appBtnRow.setAlignment(Pos.CENTER_LEFT);
        appGrid.add(appBtnRow, 0, 1, 2, 1);
        applyFormColumns(appGrid, 2);

        GridPane forwardGrid = new GridPane();
        forwardGrid.setHgap(8);
        forwardGrid.setVgap(8);
        forwardGrid.add(forwardLocalField, 0, 0);
        forwardGrid.add(forwardRemoteField, 1, 0);
        HBox forwardBtnRow = new HBox(8, listForwardBtn, addForwardBtn, removeForwardBtn);
        forwardBtnRow.setAlignment(Pos.CENTER_LEFT);
        forwardGrid.add(forwardBtnRow, 0, 1, 2, 1);
        applyFormColumns(forwardGrid, 2);

        GridPane shellGrid = commandRow("Shell", shellField, shellBtn);
        GridPane customGrid = commandRow("ADB", customField, customBtn);

        VBox leftContent = new VBox(6,
                titled("无线连接", connectGrid),
                titled("设备", deviceGrid),
                titled("服务", serviceGrid),
                titled("应用", appGrid),
                titled("转发", forwardGrid),
                new Separator(),
                shellGrid,
                customGrid
        );
        leftContent.setPadding(UiLayout.COMPACT_PADDING);
        leftContent.setMinWidth(320);

        ScrollPane scroll = new ScrollPane(leftContent);
        scroll.setFitToWidth(true);
        scroll.setMinWidth(280);
        scroll.setPrefWidth(360);

        fileBrowser.setMinWidth(360);
        fileBrowser.setPrefWidth(480);

        HBox outputBar = UiLayout.toolbar(new Region(), clearBtn);
        HBox.setHgrow(outputBar.getChildren().get(0), Priority.ALWAYS);
        outputBar.setPadding(new Insets(4, 8, 0, 8));

        VBox outputPane = new VBox(4, outputBar, output);
        VBox.setVgrow(output, Priority.ALWAYS);
        outputPane.setPadding(new Insets(0, 8, 8, 0));
        outputPane.setMinWidth(240);

        SplitPane split = new SplitPane(scroll, fileBrowser, outputPane);
        split.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        SplitPane.setResizableWithParent(scroll, true);
        SplitPane.setResizableWithParent(fileBrowser, true);
        SplitPane.setResizableWithParent(outputPane, true);

        BorderPane content = new BorderPane();
        content.setCenter(split);
        Platform.runLater(() -> split.setDividerPositions(0.24, 0.68));
        return content;
    }

    private static void applyFormColumns(GridPane grid, int columnCount) {
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(Region.USE_PREF_SIZE);
        labelCol.setMaxWidth(Region.USE_PREF_SIZE);
        grid.getColumnConstraints().add(labelCol);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        fieldCol.setMinWidth(120);
        grid.getColumnConstraints().add(fieldCol);
        if (columnCount > 2) {
            for (int i = 2; i < columnCount; i++) {
                ColumnConstraints actionCol = new ColumnConstraints();
                actionCol.setMinWidth(Region.USE_PREF_SIZE);
                actionCol.setMaxWidth(Region.USE_PREF_SIZE);
                grid.getColumnConstraints().add(actionCol);
            }
        }
    }

    private static GridPane commandRow(String label, TextField field, Button actionBtn) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        field.setPromptText(label);
        grid.add(field, 0, 0);
        grid.add(actionBtn, 1, 0);
        GridPane.setHgrow(field, Priority.ALWAYS);
        field.setMaxWidth(Double.MAX_VALUE);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(fieldCol, new ColumnConstraints());
        return grid;
    }

    private static Button actionButton(String text, Runnable action) {
        Button btn = new Button(text);
        btn.setOnAction(e -> action.run());
        btn.setWrapText(true);
        btn.setMinWidth(Region.USE_PREF_SIZE);
        return btn;
    }

    private static TitledPane titled(String title, javafx.scene.Node content) {
        TitledPane pane = new TitledPane(title, content);
        pane.setCollapsible(true);
        pane.setExpanded(true);
        return pane;
    }

    private static GridPane buttonGrid(int columns, Button... buttons) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        for (int c = 0; c < columns; c++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setHgrow(Priority.ALWAYS);
            col.setMinWidth(Region.USE_PREF_SIZE);
            grid.getColumnConstraints().add(col);
        }
        int col = 0;
        int row = 0;
        for (Button btn : buttons) {
            btn.setWrapText(true);
            btn.setMaxWidth(Double.MAX_VALUE);
            grid.add(btn, col, row);
            col++;
            if (col >= columns) {
                col = 0;
                row++;
            }
        }
        return grid;
    }

    private static int parsePort(String text, int defaultValue) {
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        int port = Integer.parseInt(text.trim());
        if (port <= 0 || port > 65535) {
            throw new NumberFormatException("端口超出范围");
        }
        return port;
    }
}
