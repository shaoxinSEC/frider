package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.config.Config;
import com.androidrev.guistudio.config.ConfigManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class MainWindow extends Application {
    private AppContext appContext;
    private ConfigManager configManager;

    @Override
    public void start(Stage stage) {
        Logger logger = new Logger();
        Config initialConfig = Config.defaultConfig();

        try {
            configManager = new ConfigManager(cfg -> Platform.runLater(() -> {
                if (appContext != null) {
                    appContext.onConfigReload(cfg);
                }
            }));
            initialConfig = configManager.get();
        } catch (Exception e) {
            logger.log(AppContext.SOURCE_SYSTEM, "配置加载失败: %s", e.getMessage());
            configManager = null;
        }
        if (configManager != null) {
            try {
                configManager.ensureScriptsDir();
                configManager.ensureFridaToolsDir();
            } catch (Exception e) {
                logger.log(AppContext.SOURCE_SYSTEM, "工具目录创建失败: %s", e.getMessage());
            }
            logger.log(AppContext.SOURCE_SYSTEM, "配置文件: %s", configManager.getPath());
        }

        appContext = new AppContext(stage, logger, configManager, initialConfig);
        appContext.getDeviceManager().start();

        Tab settingsTab = new Tab("设置", SettingsTab.build(appContext));
        settingsTab.setClosable(false);

        TabPane tabs = new TabPane(
                new Tab("应用管理", AppsTab.build(appContext)),
                new Tab("流量转发", IptablesTab.build(appContext)),
                new Tab("Frida管理", FridaTab.build(appContext)),
                new Tab("ADB管理", AdbTab.build(appContext)),
                new Tab("Logcat", LogcatTab.build(appContext)),
                settingsTab
        );
        tabs.getTabs().forEach(tab -> tab.setClosable(false));
        tabs.setMinHeight(100);

        ListView<String> logArea = logger.getWidget();
        BorderPane logPane = new BorderPane(logArea);
        logPane.setMinHeight(80);
        logPane.setPrefHeight(220);
        BorderPane.setMargin(logArea, new Insets(4, 6, 6, 6));

        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);
        splitPane.getItems().addAll(tabs, logPane);
        SplitPane.setResizableWithParent(tabs, true);
        SplitPane.setResizableWithParent(logPane, true);
        splitPane.setMinHeight(200);

        BorderPane root = new BorderPane();
        root.setCenter(splitPane);

        MenuItem aboutItem = new MenuItem("关于");
        aboutItem.setOnAction(e -> Dialogs.showAboutDialog(stage));
        Menu helpMenu = new Menu("帮助");
        helpMenu.getItems().add(aboutItem);
        MenuBar menuBar = new MenuBar(helpMenu);

        DeviceStatusBar deviceBar = appContext.getDeviceManager().getStatusBar();
        deviceBar.setAlignment(Pos.CENTER_RIGHT);
        deviceBar.setPadding(new Insets(0, 4, 0, 12));

        BorderPane header = new BorderPane();
        header.setLeft(menuBar);
        header.setRight(deviceBar);
        BorderPane.setMargin(deviceBar, new Insets(2, 10, 2, 0));
        root.setTop(header);

        Scene scene = new Scene(root, 1100, 720);
        var styleUrl = MainWindow.class.getResource("/styles/main.css");
        if (styleUrl != null) {
            scene.getStylesheets().add(styleUrl.toExternalForm());
        }
        stage.setTitle("FRIDER");
        stage.setScene(scene);
        applyStageIcon(stage);
        stage.setOnCloseRequest(e -> {
            appContext.getDeviceManager().stop();
            if (configManager != null) {
                configManager.close();
            }
        });
        stage.show();
        Platform.runLater(() -> {
            appContext.reportToolAvailability();
            splitPane.setDividerPositions(0.68);
        });
    }

    private static void applyStageIcon(Stage stage) {
        var logoUrl = MainWindow.class.getResource("/logo.jpg");
        if (logoUrl != null) {
            stage.getIcons().add(new Image(logoUrl.toExternalForm()));
        }
    }
}
