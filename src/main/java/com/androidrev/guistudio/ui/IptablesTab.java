package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.adb.AppInfo;
import com.androidrev.guistudio.config.Config;
import com.androidrev.guistudio.config.ProxyEndpoint;
import com.androidrev.guistudio.config.RedirectRule;
import com.androidrev.guistudio.config.RedirectRules;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.util.Callback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class IptablesTab {
    private IptablesTab() {
    }

    public static SplitPane build(AppContext app) {
        TextArea rulesView = new TextArea();
        rulesView.setEditable(false);
        rulesView.setWrapText(true);
        rulesView.setPrefRowCount(12);

        ComboBox<ForwardTarget> targetSelect = new ComboBox<>();
        targetSelect.setPromptText("转发范围（全部 / 指定应用）");
        targetSelect.setCellFactory(forwardTargetCellFactory());
        targetSelect.setButtonCell(forwardTargetCellFactory().call(null));
        targetSelect.getItems().add(ForwardTarget.ALL);
        targetSelect.setValue(ForwardTarget.ALL);
        UiLayout.fillWidth(targetSelect);

        ComboBox<String> protocolSelect = new ComboBox<>();
        protocolSelect.getItems().addAll("http", "https", "socks5", "socks4");
        protocolSelect.setValue("http");

        TextField proxyEntry = new TextField();
        proxyEntry.setPromptText("代理IP:端口");
        UiLayout.fillWidth(proxyEntry);
        applyDefaultProxy(app.getConfig(), protocolSelect, proxyEntry);
        app.addConfigListener(cfg -> Platform.runLater(() -> applyDefaultProxy(cfg, protocolSelect, proxyEntry)));

        app.getShared().addAppsListener(apps -> Platform.runLater(() -> syncForwardTargetSelect(targetSelect, apps)));

        Runnable refreshRules = () -> Async.run(() -> {
            try {
                var check = app.getAdb().hasDevice();
                if (!check.connected()) {
                    Platform.runLater(() -> app.getLogger().log(AppContext.SOURCE_IPTABLES, "未检测到ADB设备"));
                    return;
                }
                String out = app.getAdb().listNatRules();
                Platform.runLater(() -> {
                    rulesView.setText(out);
                    app.getLogger().log(AppContext.SOURCE_IPTABLES, "已刷新iptables规则");
                });
            } catch (Exception e) {
                Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_IPTABLES, e));
            }
        });

        Button refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> refreshRules.run());

        Button enableBtn = new Button("开启");
        enableBtn.setOnAction(e -> {
            ProxyEndpoint endpoint;
            try {
                endpoint = buildProxyEndpoint(protocolSelect.getValue(), proxyEntry.getText());
            } catch (IllegalArgumentException ex) {
                Logger.showError(app, AppContext.SOURCE_IPTABLES, ex);
                return;
            }
            ForwardTarget target = targetSelect.getValue();
            if (target == null) {
                target = ForwardTarget.ALL;
            }
            final ForwardTarget forwardTarget = target;
            Async.run(() -> {
                try {
                    Config cfg = app.getConfig();
                    String proxyAddr = endpoint.toHostPort();
                    List<RedirectRule> rules;
                    String uid = null;
                    if (forwardTarget.isAllApps()) {
                        rules = RedirectRules.allTcpRules();
                    } else {
                        String pkg = forwardTarget.app().getPackageName();
                        uid = app.getAdb().fetchPackageUid(pkg);
                        rules = RedirectRules.perAppTcpRules();
                    }
                    Exception lastErr = null;
                    for (RedirectRule rule : rules) {
                        try {
                            app.getAdb().addRedirectRuleFromTemplate(rule.getTemplate(), proxyAddr, uid);
                        } catch (Exception ex) {
                            lastErr = ex;
                            break;
                        }
                    }
                    if (lastErr != null) {
                        Exception err = lastErr;
                        Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_IPTABLES, err));
                        return;
                    }
                    final String logUid = uid;
                    Platform.runLater(() -> {
                        if (forwardTarget.isAllApps()) {
                            app.getLogger().log(AppContext.SOURCE_IPTABLES,
                                    "转发已开启（全部应用）-> %s (%s)", endpoint.toUrl(), endpoint.protocol());
                        } else {
                            app.getLogger().log(AppContext.SOURCE_IPTABLES,
                                    "转发已开启（%s, uid=%s）-> %s (%s)",
                                    forwardTarget.app().getPackageName(), logUid,
                                    endpoint.toUrl(), endpoint.protocol());
                        }
                        refreshRules.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_IPTABLES, ex));
                }
            });
        });

        Button disableBtn = new Button("关闭");
        disableBtn.setOnAction(e -> Async.run(() -> {
            try {
                app.getAdb().clearNatOutputRules();
                Platform.runLater(() -> {
                    app.getLogger().log(AppContext.SOURCE_IPTABLES, "已清除nat OUTPUT转发规则");
                    refreshRules.run();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_IPTABLES, ex));
            }
        }));

        HBox proxyRow = new HBox(UiLayout.GAP, protocolSelect, proxyEntry);
        UiLayout.fillWidth(proxyEntry);
        protocolSelect.setMinWidth(88);

        var form = UiLayout.panel(
                targetSelect,
                proxyRow,
                UiLayout.toolbar(enableBtn, disableBtn, refreshBtn)
        );
        form.setPrefWidth(280);

        SplitPane split = new SplitPane(form, rulesView);
        split.setDividerPositions(0.35);
        return split;
    }

    private static ProxyEndpoint buildProxyEndpoint(String protocol, String address) {
        String proto = protocol == null || protocol.isBlank() ? "http" : protocol.trim().toLowerCase();
        String addr = address == null ? "" : address.trim();
        if (addr.isEmpty()) {
            throw new IllegalArgumentException("请输入代理IP:端口");
        }
        if (addr.contains("://")) {
            return ProxyEndpoint.parse(addr);
        }
        return ProxyEndpoint.parse(proto + "://" + addr);
    }

    private static void applyDefaultProxy(Config cfg, ComboBox<String> protocolSelect, TextField proxyEntry) {
        String value = cfg.getDefaultProxy();
        if (value == null || value.isBlank()) {
            return;
        }
        if (!proxyEntry.getText().isBlank()) {
            return;
        }
        try {
            ProxyEndpoint endpoint = ProxyEndpoint.parse(value.trim());
            protocolSelect.setValue(endpoint.protocol());
            proxyEntry.setText(endpoint.toHostPort());
        } catch (IllegalArgumentException e) {
            proxyEntry.setText(value.trim());
        }
    }

    private static void syncForwardTargetSelect(ComboBox<ForwardTarget> targetSelect, List<AppInfo> apps) {
        ForwardTarget previous = targetSelect.getValue();
        boolean wasAll = previous == null || previous.isAllApps();
        String selectedPkg = !wasAll && previous.app() != null ? previous.app().getPackageName() : null;

        List<ForwardTarget> items = new ArrayList<>();
        items.add(ForwardTarget.ALL);
        apps.stream()
                .sorted(Comparator.comparing(AppInfo::getAppName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AppInfo::getPackageName))
                .map(ForwardTarget::of)
                .forEach(items::add);
        targetSelect.getItems().setAll(items);

        if (wasAll) {
            targetSelect.setValue(ForwardTarget.ALL);
        } else if (selectedPkg != null) {
            items.stream()
                    .filter(t -> !t.isAllApps() && selectedPkg.equals(t.app().getPackageName()))
                    .findFirst()
                    .ifPresentOrElse(targetSelect::setValue, () -> targetSelect.setValue(ForwardTarget.ALL));
        }
    }

    private static Callback<ListView<ForwardTarget>, ListCell<ForwardTarget>> forwardTargetCellFactory() {
        return list -> new ListCell<>() {
            @Override
            protected void updateItem(ForwardTarget item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        };
    }
}
