# FRIDER

FRIDER 是一款面向 Android 逆向与动态调试的桌面工具，把应用监控、流量转发、Frida 脚本管理、ADB 常用操作和 Logcat 查看集中在一个界面里。当前版本 2.4.0，作者尹少欣 · shaoxinSEC。

## 运行环境

- JDK 17 或更高版本
- Maven（自行编译时需要）
- 已开启 USB 调试的 Android 设备
- Windows / Linux / macOS

## 快速开始

### 使用已打包 JAR

将 `frider.jar` 放在工作目录后执行：

```shell
java -jar frider.jar
```

Windows 也可双击同目录下的 `frider.bat` 静默启动。

### 开发运行

```bash
mvn javafx:run
```

### 编译打包

在项目根目录执行：

```bash
mvn clean package
```

打包完成后生成 `target/frider.jar`，复制到工作目录即可使用。Windows 下默认包含 JavaFX 的 win 平台原生库；在 Linux / macOS 上编译时会自动切换对应平台依赖。

## 目录说明

把程序放在同一目录下使用，常见结构如下：

```
frider/
├── frider.jar              主程序
├── frider.bat              Windows 静默启动脚本（可选）
├── config.toml             配置文件，修改后自动生效
├── scripts/                Frida 脚本目录
└── tools/                  本地工具（可选）
    ├── adb/                adb 可执行文件
    ├── frida/              frida 客户端及同目录 frida-server
    ├── frida-tools/        frida-ps、frida-trace 等子工具
    └── scrcpy/             scrcpy 可执行文件（可选）
```

`config.toml` 和 `scripts/` 目录必须与 JAR 包位于同一工作目录。工具也可配置为系统 PATH 中的命令。

## 配置说明

`config.toml` 采用 TOML 格式。可在 **设置** 标签页中修改并保存，也可直接编辑文件；保存后程序会自动重新加载，无需重启。

| 配置项 | 说明 |
|--------|------|
| `root_command` | 设备端提权命令，默认 `su`，可按 ROM 改成 `fk` 等 |
| `adb_path` | adb 可执行文件路径 |
| `scrcpy_path` | scrcpy 可执行文件路径，默认 `scrcpy`（使用系统 PATH） |
| `frida_client_path` | 本地 frida 客户端路径 |
| `frida_tools_dir` | frida-ps、frida-trace 等子工具目录，默认 `tools/frida-tools` |
| `frida_server_path` | 设备端 frida-server 路径 |
| `frida_server_start_command` | 设备端启动 frida-server 的命令 |
| `frida_ps_args` | 传给 frida-ps 的参数，默认 `["-U", "-a", "-i"]` |
| `scripts_dir` | 脚本目录，默认 `scripts` |
| `default_proxy` | 流量转发页默认代理地址 |
| `iptables_redirect_rules` | 全局转发时写入的 iptables 规则模板（可选，内置默认规则） |

**工具查找规则：**

- Push Server 时，程序会在 `frida_client_path` 同级目录自动查找 frida-server 可执行文件
- frida-ps、frida-trace 等子工具从 `frida_tools_dir` 目录按名称匹配
- scrcpy 启动时会自动将 `adb_path` 所在目录加入 PATH，便于 scrcpy 调用自定义 adb

## 界面概览

顶部状态栏显示当前 ADB 设备。下方六个标签页对应不同功能，底部为统一日志输出区。

### 应用管理

列出设备上已安装的第三方应用，自动刷新 PID。frida-server 运行后，会尝试通过 frida-ps 补全应用显示名称。

右键菜单可：立即刷新、启动应用、复制 PID/包名/安装路径、终止进程。

### 流量转发

通过 iptables 实现，非系统代理或 VPN 方式，配合抓包代理使用。填写代理地址和协议（http/https/socks4/socks5），选择转发范围后点击开启转发。

- **全部应用**：按 `iptables_redirect_rules` 写入规则
- **指定应用**：按应用 UID 写入转发规则

可随时刷新当前 nat 表规则，或关闭转发清空 OUTPUT 链。

### Frida 管理

左侧为 `scripts/` 目录下的脚本列表，右侧为操作区。

基本流程：

1. 在 **设置** 中配置本地 frida 客户端、frida-tools 目录及设备端 server 路径
2. 点击 **Push** 将本地 frida-server（与客户端同目录）推送到设备
3. 在设备端 Server 列表选中对应项，点击 **Start** 启动
4. 选择目标应用，Attach 或 Spawn 模式
5. 双击脚本或右键执行脚本

脚本列表支持新建、编辑、删除、刷新。运行中的脚本可在列表上看到标记，也可停止单个脚本或全部停止。

Frida 增强工具（从 `frida_tools_dir` 查找可执行文件）：

- frida-kill：终止目标进程
- frida-trace：函数追踪
- frida-dexdump：内存 DEX 导出
- frida-discover：API 发现
- frida-ls-devices：列出 Frida 设备
- 解除 Waiting for debug：执行 `adb shell am clear-debug-app`，清除调试等待状态

### ADB 管理

集中常用 ADB 操作，包括：无线连接与断开、开启 TCP/IP 端口、安装/卸载 APK、端口转发、截图、**投屏（scrcpy）**、查看设备信息、执行 shell 命令、自定义 adb 子命令、浏览设备文件系统等。输出显示在页面下方文本区。

投屏前请在 **设置** 中配置 `scrcpy_path`，连接设备后点击 **投屏** 即可启动 scrcpy 窗口。

### Logcat

实时查看设备日志，可按级别、关键字、包名过滤。支持暂停、清空、导出到文件。彩色显示 ANSI 颜色码。

### 设置

集中配置 ADB 路径、scrcpy 路径、Root 命令、Frida 客户端、frida-tools 目录、设备端 server 路径与启动命令、脚本目录、默认代理等。点击 **保存** 写入 `config.toml` 并立即生效。

## 常见问题

1. **`java -jar` 启动后 JavaFX 报错**

   确认 JDK 版本不低于 17，且在目标操作系统上重新打包。

2. **应用名显示为空**

   确认 frida-server 已运行，且 `frida_tools_dir` 中存在 frida-ps 可执行文件。

3. **工具路径无效时反复报错**

   在 **设置** 标签页修正路径并保存。路径不存在时程序会跳过相关后台任务，避免重复弹窗和日志刷屏。

4. **Frida Client 或子工具找不到**

   检查 **设置** 中的 `frida_client_path` 和 `frida_tools_dir` 是否有效，或将工具放入 `tools/frida`、`tools/frida-tools` 目录。

5. **投屏按钮无反应或报错**

   确认 **设置** 中 `scrcpy_path` 指向有效的 scrcpy 可执行文件，且当前已有 ADB 设备连接。若使用自定义 adb，请同时正确配置 `adb_path`。

## 更新日志

- **[2026/06/30 | frider-2.4.0]** 新增设置标签页，工具路径可在界面配置并保存；配置热更新；统一界面布局、精简重复控件；frida 子工具改为 `frida_tools_dir` 目录管理；Push Server 自动查找客户端同目录的 frida-server；支持配置 scrcpy 路径并在 ADB 管理页一键投屏
- **[2026/05/31 | frider-2.3.1]** 优化程序日志和 adb 日志输出，使页面更加流畅；优化 Frida 管理界面，右侧工具栏可以上下滚动和宽度调整
- **[2026/05/30 | frider-2.3.0]** 初版发布，基于 Java 17 开发，WIN64 上编译，具备应用管理、流量转发、Frida 管理、ADB 管理和 Logcat 监控这些功能
