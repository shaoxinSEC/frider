# FRIDER

FRIDER是一款面向Android逆向与动态调试的桌面工具，把应用监控、流量转发、Frida脚本管理、ADB常用操作和Logcat查看集中在一个界面里。当前版本2.4.0，作者尹少欣 · shaoxinSEC。

## 运行环境

- JDK 17或更高版本
- Maven（自行编译时需要）
- 已开启USB调试的Android设备
- Windows / Linux / macOS

## 快速开始

### 使用已打包JAR

将frider.jar放在工作目录后执行：

```shell
java -jar frider.jar
```

Windows也可双击同目录下的frider.bat静默启动。

### 开发运行

```bash
mvn javafx:run
```

### 编译打包

在项目根目录执行：

```bash
mvn clean package
```

打包完成后生成target/frider.jar，复制到工作目录即可使用。Windows下默认包含JavaFX的win平台原生库；在Linux / macOS上编译时会自动切换对应平台依赖。

## 目录说明

把程序放在同一目录下使用，常见结构如下：

```
frider/
├── frider.jar              主程序
├── frider.bat              Windows静默启动脚本（可选）
├── config.toml             配置文件，修改后自动生效
├── scripts/                Frida脚本目录
└── tools/                  本地工具（可选）
    ├── adb/                adb可执行文件
    ├── frida/              frida客户端及同目录frida-server
    ├── frida-tools/        frida-ps、frida-trace等子工具
    └── scrcpy/             scrcpy可执行文件（可选）
```

config.toml和scripts/ 目录必须与JAR包位于同一工作目录。工具也可配置为系统PATH中的命令。

## 配置说明

config.toml采用TOML格式。可在 设置 标签页中修改并保存，也可直接编辑文件；保存后程序会自动重新加载，无需重启。

| 配置项 | 说明 |
|--------|------|
| root_command | 设备端提权命令，默认su，可按ROM改成fk等 |
| adb_path | adb可执行文件路径 |
| scrcpy_path | scrcpy可执行文件路径，默认scrcpy（使用系统PATH） |
| frida_client_path | 本地frida客户端路径 |
| frida_tools_dir | frida-ps、frida-trace等子工具目录，默认tools/frida-tools |
| frida_server_path | 设备端frida-server路径 |
| frida_server_start_command | 设备端启动frida-server的命令 |
| frida_ps_args | 传给frida-ps的参数，默认 ["-U", "-a", "-i"] |
| scripts_dir | 脚本目录，默认scripts |
| default_proxy | 流量转发页默认代理地址 |
| iptables_redirect_rules | 全局转发时写入的iptables规则模板（可选，内置默认规则） |

工具查找规则：

- Push Server时，程序会在frida_client_path同级目录自动查找frida-server可执行文件
- frida-ps、frida-trace等子工具从frida_tools_dir目录按名称匹配
- scrcpy启动时会自动将adb_path所在目录加入PATH，便于scrcpy调用自定义adb

## 界面概览

顶部状态栏显示当前ADB设备。下方六个标签页对应不同功能，底部为统一日志输出区。

### 应用管理

列出设备上已安装的第三方应用，自动刷新PID。frida-server运行后，会尝试通过frida-ps补全应用显示名称。

右键菜单可：立即刷新、启动应用、复制PID/包名/安装路径、终止进程。

### 流量转发

通过iptables实现，非系统代理或VPN方式，配合抓包代理使用。填写代理地址和协议（http/https/socks4/socks5），选择转发范围后点击开启转发。

- 全部应用：按iptables_redirect_rules写入规则
- 指定应用：按应用UID写入转发规则

可随时刷新当前nat表规则，或关闭转发清空OUTPUT链。

### Frida管理

左侧为scripts/ 目录下的脚本列表，右侧为操作区。

基本流程：

1. 在 设置 中配置本地frida客户端、frida-tools目录及设备端server路径
2. 点击Push将本地frida-server（与客户端同目录）推送到设备
3. 在设备端Server列表选中对应项，点击Start启动
4. 选择目标应用，Attach或Spawn模式
5. 双击脚本或右键执行脚本

脚本列表支持新建、编辑、删除、刷新。运行中的脚本可在列表上看到标记，也可停止单个脚本或全部停止。

Frida增强工具（从frida_tools_dir查找可执行文件）：

- frida-kill：终止目标进程
- frida-trace：函数追踪
- frida-dexdump：内存DEX导出
- frida-discover：API发现
- frida-ls-devices：列出Frida设备
- 解除Waiting for debug：执行adb shell am clear-debug-app，清除调试等待状态

### ADB管理

集中常用ADB操作，包括：无线连接与断开、开启TCP/IP端口、安装/卸载APK、端口转发、截图、投屏（scrcpy）、查看设备信息、执行shell命令、自定义adb子命令、浏览设备文件系统等。输出显示在页面下方文本区。

投屏前请在 设置 中配置scrcpy_path，连接设备后点击 投屏 即可启动scrcpy窗口。

### Logcat

实时查看设备日志，可按级别、关键字、包名过滤。支持暂停、清空、导出到文件。彩色显示ANSI颜色码。

### 设置

集中配置ADB路径、scrcpy路径、Root命令、Frida客户端、frida-tools目录、设备端server路径与启动命令、脚本目录、默认代理等。点击 保存 写入config.toml并立即生效。

## 常见问题

1. java -jar启动后JavaFX报错

   确认JDK版本不低于17，且在目标操作系统上重新打包。

2. 应用名显示为空

   确认frida-server已运行，且frida_tools_dir中存在frida-ps可执行文件。

3. 工具路径无效时反复报错

   在 设置 标签页修正路径并保存。路径不存在时程序会跳过相关后台任务，避免重复弹窗和日志刷屏。

4. Frida Client或子工具找不到

   检查 设置 中的frida_client_path和frida_tools_dir是否有效，或将工具放入tools/frida、tools/frida-tools目录。

5. 投屏按钮无反应或报错

   确认 设置 中scrcpy_path指向有效的scrcpy可执行文件，且当前已有ADB设备连接。若使用自定义adb，请同时正确配置adb_path。

## 更新日志

- [2026/06/30 | frider-2.4.0] 新增设置标签页，工具路径可在界面配置并保存；配置热更新；统一界面布局、精简重复控件；frida子工具改为frida_tools_dir目录管理；Push Server自动查找客户端同目录的frida-server；支持配置scrcpy路径并在ADB管理页一键投屏
- [2026/05/31 | frider-2.3.1] 优化程序日志和adb日志输出，使页面更加流畅；优化Frida管理界面，右侧工具栏可以上下滚动和宽度调整
- [2026/05/30 | frider-2.3.0] 初版发布，基于Java 17开发，WIN64上编译，具备应用管理、流量转发、Frida管理、ADB管理和Logcat监控这些功能
