package com.androidrev.guistudio;

public final class AppMetadata {
    public static final String NAME = "FRIDER";
    public static final String VERSION = "2.4.0";
    public static final String AUTHOR = "尹少欣 · shaoxinSEC";
    public static final String TAGLINE = "Android逆向工程辅助工具";

    public static final String SCENARIOS = """
              1. Android应用动态分析与逆向调试
              2. Frida脚本注入、Hook与进程调试
              3. 配合抓包代理进行流量转发与抓包分析
              4. 日常ADB设备管理与日志排查""";

    public static final String FEATURES = """
              1. 应用管理：进程监控、应用名加载、快捷操作
              2. 流量转发：iptables规则配置与按应用/全局转发
              3. Frida管理：Server部署、脚本编辑执行、增强工具集
              4. ADB管理：Shell、文件浏览、应用安装、scrcpy投屏与设备操作
              5. Logcat：实时日志过滤与导出
              6. 设置：界面配置 ADB / Frida / scrcpy 等工具路径，保存后自动生效""";

    public static final String UPDATE_LOG = """
              1. 新增「设置」标签页，集中配置 ADB / Frida / scrcpy 等工具路径
              2. 配置支持界面保存与文件热更新，工具路径无效时不再重复弹窗
              3. 统一界面布局，精简各页重复控件；ADB 管理页支持 scrcpy 一键投屏""";

    private AppMetadata() {
    }
}
