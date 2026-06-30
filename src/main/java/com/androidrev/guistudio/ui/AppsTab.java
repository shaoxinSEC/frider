package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.adb.AdbClient;
import com.androidrev.guistudio.adb.AppInfo;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;

public final class AppsTab {
    private AppsTab() {
    }

    public static BorderPane build(AppContext app) {
        ObservableList<AppInfo> items = FXCollections.observableArrayList();
        TableView<AppInfo> table = new TableView<>(items);

        TableColumn<AppInfo, String> pidCol = new TableColumn<>("PID");
        pidCol.setCellValueFactory(new PropertyValueFactory<>("pid"));
        pidCol.setPrefWidth(80);

        TableColumn<AppInfo, String> pkgCol = new TableColumn<>("包名");
        pkgCol.setCellValueFactory(new PropertyValueFactory<>("packageName"));
        pkgCol.setPrefWidth(280);

        TableColumn<AppInfo, String> nameCol = new TableColumn<>("应用名");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("appName"));
        nameCol.setPrefWidth(160);

        TableColumn<AppInfo, String> pathCol = new TableColumn<>("安装路径");
        pathCol.setCellValueFactory(new PropertyValueFactory<>("installPath"));
        pathCol.setPrefWidth(400);

        table.getColumns().addAll(pidCol, pkgCol, nameCol, pathCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        AppsMonitor monitor = new AppsMonitor(app, items, table);

        MenuItem refreshItem = new MenuItem("立即刷新");
        refreshItem.setOnAction(e -> monitor.forceRefresh());

        MenuItem copyPidItem = new MenuItem("复制PID");
        copyPidItem.setOnAction(e -> {
            AppInfo selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                copyField(app, selected.getPid(), "PID");
            }
        });

        MenuItem copyPkgItem = new MenuItem("复制包名");
        copyPkgItem.setOnAction(e -> {
            AppInfo selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                copyField(app, selected.getPackageName(), "包名");
            }
        });

        MenuItem copyPathItem = new MenuItem("复制安装路径");
        copyPathItem.setOnAction(e -> {
            AppInfo selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                copyField(app, selected.getInstallPath(), "安装路径");
            }
        });

        MenuItem launchItem = new MenuItem("启动");
        launchItem.setOnAction(e -> {
            AppInfo selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                launchApp(app, selected, monitor::forceRefresh);
            }
        });

        MenuItem killItem = new MenuItem("终止进程");
        killItem.setOnAction(e -> {
            AppInfo selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                killAt(app, selected, monitor::forceRefresh);
            }
        });
        ContextMenu menu = new ContextMenu(
                refreshItem,
                launchItem,
                copyPidItem,
                copyPkgItem,
                copyPathItem,
                killItem
        );
        table.setContextMenu(menu);
        table.setOnContextMenuRequested(e -> {
            AppInfo selected = table.getSelectionModel().getSelectedItem();
            boolean hasRow = selected != null;
            copyPidItem.setDisable(!hasRow || !isCopyable(hasRow ? selected.getPid() : null));
            copyPkgItem.setDisable(!hasRow || !isCopyable(hasRow ? selected.getPackageName() : null));
            copyPathItem.setDisable(!hasRow || !isCopyable(hasRow ? selected.getInstallPath() : null));
            launchItem.setDisable(!hasRow || !AdbClient.isValidPackageName(selected.getPackageName()));
            killItem.setDisable(!hasRow || "N/A".equals(selected.getPid()));
        });

        BorderPane pane = new BorderPane();
        pane.setCenter(table);

        monitor.start();
        return pane;
    }

    private static boolean isCopyable(String value) {
        return value != null && !value.isBlank() && !"N/A".equals(value);
    }

    private static void copyField(AppContext app, String text, String label) {
        if (!isCopyable(text)) {
            app.getLogger().log(AppContext.SOURCE_APPS, "无有效的%s可复制", label);
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        app.getLogger().log(AppContext.SOURCE_APPS, "已复制%s", label);
    }

    private static void launchApp(AppContext app, AppInfo target, Runnable onDone) {
        String pkg = target.getPackageName();
        if (!AdbClient.isValidPackageName(pkg)) {
            app.getLogger().log(AppContext.SOURCE_APPS, "无效的应用行");
            return;
        }
        Async.run(() -> {
            try {
                app.getAdb().launchApp(pkg);
                Platform.runLater(() ->
                        app.getLogger().log(AppContext.SOURCE_APPS, "已启动 %s", pkg));
                onDone.run();
            } catch (Exception e) {
                Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_APPS, e));
            }
        });
    }

    private static void killAt(AppContext app, AppInfo target, Runnable onDone) {
        if (target.getPackageName() == null || target.getPackageName().isBlank()) {
            app.getLogger().log(AppContext.SOURCE_APPS, "无效的应用行");
            return;
        }
        if ("N/A".equals(target.getPid())) {
            app.getLogger().log(AppContext.SOURCE_APPS, "应用 %s 未运行", target.getPackageName());
            return;
        }
        Async.run(() -> {
            try {
                app.getAdb().killProcess(target.getPid());
                Platform.runLater(() ->
                        app.getLogger().log(AppContext.SOURCE_APPS, "已终止 %s (PID %s)",
                                target.getPackageName(), target.getPid()));
                onDone.run();
            } catch (Exception e) {
                Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_APPS, e));
            }
        });
    }
}
