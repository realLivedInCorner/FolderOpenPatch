# Folder Open Patch — 技术分析报告

> 针对 **Minecraft 26.3（Fabric）**「打开材质包 / 光影包文件夹」导致游戏无响应的问题。  
> 依据：`minecraft-client.jar` / `minecraft-common.jar` 反汇编、Iris 源码片段、多环境反馈。  
> 对应 mod：**1.0.2**（含 `Blaze3D` + `SDLMisc.SDL_OpenURL` 全链路拦截）。

---

## 1. 结论摘要

| 问题 | 结论 |
|------|------|
| 是否与电脑有关 | **有关**。同一游戏 jar，不同机器表现可完全不同。 |
| 是否服务端问题 | **否**。纯客户端 UI。 |
| 是否 GPU / 画质问题 | **否**。不走渲染管线。 |
| 是否人人必现 | **否**。依赖 OS、shell 扩展、杀软、路径、云盘等。 |
| 原版根因 | 打开文件夹最终调用 `SDL_OpenURL`，在部分 Windows 上挂起。 |
| Iris 为何仍中招 | 直接 `Blaze3D.openUri(file://…)`，绕过 `openPath`；且 SDL 本身有问题。 |
| 本 mod 策略 | 多层拦截 → 异步 `explorer.exe` / 系统打开，不进 SDL。 |

---

## 2. 调用链（26.3 实测）

### 2.1 原版材质包

```text
PackSelectionScreen.lambda$init$0(Button)
  → this.packDir                         // java.nio.file.Path
  → Blaze3D.openPath(Path)
      → path.normalize().toUri()
      → Blaze3D.openUri(URI)
          → Util.nonCriticalIoPool().execute(λ)
          → SDLMisc.SDL_OpenURL(uri.toString())   ← 挂起点
```

要点：

1. 游戏**已经**把打开丢到 `nonCriticalIoPool`，仍可能被感知为假死。
2. 风险在 **`SDL_OpenURL`（Windows 上通常为 ShellExecute）** 及进程级副作用。
3. 26.x **不再**走旧的 `Util.OperatingSystem#open` + AWT `Desktop.browseFileDirectory`。

### 2.2 Iris 光影包（实锤漏网）

```java
// net.irisshaders.iris.gui.screen.ShaderPackScreen
private void openShaderPackFolder() {
    CompletableFuture.runAsync(() ->
        Blaze3D.openUri(Iris.getShaderpacksDirectoryManager().getDirectoryUri()));
}
```

- 走 **`openUri`**，不走 `openPath`。
- 外层 `CompletableFuture.runAsync` **不能**消除 SDL / ShellExecute 挂起。
- 更新提示处的 `Blaze3D::openUri` 传入 **http(s)**，与文件夹打开是同一 API、不同 scheme。

### 2.3 `Blaze3D` 公开面（完整）

```text
com.mojang.blaze3d.Blaze3D
  public static void openUri(URI)
  public static void openPath(Path)
  private static void lambda$openUri$0(URI)  // → SDLMisc.SDL_OpenURL
```

因此：

- 拦 `openPath` **不够**（Iris 案例）。
- 拦 `openUri` 可盖住走 Blaze3D 的所有调用。
- 仍可能有 mod **直接调 `SDLMisc.SDL_OpenURL`** → 必须拦最终 sink。

---

## 3. 可能触发「无响应 / 假死」的情形

下列情形可单独或叠加命中。同一机器上命中一条或多条即可能复现。

### 3.1 Windows Shell / 安全软件

| # | 情形 | 机制 |
|---|------|------|
| W1 | 第三方资源管理器扩展（右键菜单、云盘、压缩包、输入法、下载工具） | ShellExecute 加载 shell extension；COM 扩展卡死 → 调用不返回 |
| W2 | 杀毒 / EDR / 主动防御 hook ShellExecute | 扫描或弹窗确认拖慢/阻塞 |
| W3 | Windows Search / 索引重建 | 打开目录变慢甚至卡住 |
| W4 | explorer 进程异常 / 挂起 | DDE/COM 协同失败 |
| W5 | 组策略 / 企业管控限制打开路径 | shell 打开被拦 |
| W6 | 文件夹默认处理程序被第三方接管 | `file://` 目录被错误程序处理 |

### 3.2 路径与文件系统

| # | 情形 | 机制 |
|---|------|------|
| P1 | 非 ASCII / 中文 / 特殊字符路径 | 代码页与 shell 解析，历史高发 |
| P2 | 路径过长（MAX_PATH） | 长路径未启用时异常等待 |
| P3 | 空格、`#`、`%`、`&` 等 | 经 `file://` 再交给 SDL/shell，边界多 |
| P4 | 目录不存在 | 打开前未创建，实现不一致 |
| P5 | 网络盘 / UNC / 映射盘 | 网络往返、凭据弹窗、断线 |
| P6 | OneDrive / 网盘占位文件 | 大量 placeholder，同步组件拖慢 shell |
| P7 | 权限不足 / ACL 拒绝 | UAC / 安全对话框阻塞 |
| P8 | 符号链接 / 联接 / 只读介质异常 | 路径解析失败或挂起 |
| P9 | 启动器把实例放在异常深度/盘符 | 叠加 P1–P8 |

### 3.3 `file://` + SDL

| # | 情形 | 机制 |
|---|------|------|
| S0 | **模组直接 `Blaze3D.openUri(file://…)`** | Iris 已实锤；`runAsync` 无效 |
| S1 | **模组直接 `SDLMisc.SDL_OpenURL`** | 完全绕过 Blaze3D |
| S2 | 目录 URI 被当成「打开文档/URL」 | 语义混用，边界行为差 |
| S3 | 不同 SDL 构建 / Windows 版本差异 | 实现不一致 |
| S4 | 打开失败后重试 / 同步错误处理 | 放大卡顿 |
| S5 | 与 Pack Watcher / 列表刷新 IO 争用 | 体感更卡 |

### 3.4 游戏与启动环境

| # | 情形 | 机制 |
|---|------|------|
| G1 | 第三方启动器（PCL、HMCL、MultiMC 等） | 工作目录、环境变量、JVM 参数差异 |
| G2 | 便携版 / 路径过深 | 叠加 P2 |
| G3 | 大量 pack + Watcher 扫描 | IO 压力 |
| G4 | 全屏独占 + 焦点/弹窗 | 弹出资源管理器后焦点异常，像假死 |
| G5 | 磁盘 100% / 内存不足 | 任意 COM/同步调用被拖长 |
| G6 | UAC 弹窗未注意 | 表现为游戏无响应 |

### 3.5 与电脑的关系（归纳）

| 相关性 | 内容 |
|--------|------|
| **强相关** | Windows 版本与补丁、shell 扩展、杀软策略、路径字符、网络盘/云盘、启动器与实例布局 |
| **弱相关 / 无关** | 显卡型号、画质、多人联线、服务端、材质包内容本身（只要不阻止目录打开） |

**因此**：A 电脑秒开、B 电脑假死是预期现象 = **游戏实现方式 × 本机环境**，不是单纯「你电脑坏了」或「游戏写错了」。

---

## 4. 为何「已经异步」仍像卡死

`Blaze3D.openUri` 提交到 `nonCriticalIoPool`，但仍可能：

1. **进程级阻塞**：shell 扩展 / 杀软 hook 影响 COM 或消息循环，不限于工作线程。
2. **模态对话框 / 焦点抢占**：全屏下资源管理器或安全软件弹窗。
3. **错误重试或二次同步调用**放大等待。
4. **Watcher + 列表刷新** 与打开叠加。
5. 用户把「长时间打不开 + 无画面更新」统称为未响应。

---

## 5. 场景速查

| 场景 | 更可能命中 |
|------|------------|
| C 盘纯 ASCII 路径、无第三方 shell | 通常不触发 |
| 中文用户名 / 中文路径 | P1 |
| OneDrive 下的实例 | P6 |
| 360 / 火绒 / 企业终端防护 | W2 |
| Bandizip / 云盘右键扩展 | W1 |
| 实例在网络盘 | P5 |
| 首次点击目录未生成 | P4 |
| 低配 + 磁盘满载 | G5 |
| 全屏独占点打开 | G4 |
| Iris 打开光影文件夹 | S0 |
| 某些老 mod 自己 `SDL_OpenURL` | S1 |

---

## 6. 本 Mod 的修复设计

### 6.1 拦截层级

```text
                    ┌─────────────────────────────┐
  各种调用点 ──────►│ SDLMisc.SDL_OpenURL (sink)  │──► SafeFolderOpener
                    └─────────────────────────────┘
                              ▲
         ┌────────────────────┼────────────────────┐
         │                    │                    │
  Blaze3D.openUri     Blaze3D.openPath    PackSelectionScreen
  (Iris 等)           (原版 Path)          (材质包按钮 redirect)
```

| 层 | Mixin | 作用 |
|----|-------|------|
| 1 | `SdlOpenUrlMixin` | `SDL_OpenURL` CharSequence / ByteBuffer |
| 2 | `Blaze3DOpenUriMixin` | `Blaze3D.openUri` |
| 3 | `Blaze3DOpenPathMixin` | `Blaze3D.openPath` |
| 4 | `PackSelectionScreenMixin` | 材质包按钮 |

### 6.2 打开策略

| 输入 | 处理 |
|------|------|
| `file:` URI / 本地路径 / `Path` | 后台线程 `explorer.exe <abs>`（Win）/ `open` / `xdg-open` |
| `http(s)` / `mailto` 等 | 同样系统打开，不进 SDL |
| 目录不存在 | `mkdirs` 后再开 |
| 无法识别 | 放行原逻辑，避免误伤 |

### 6.3 为何拦 SDL 而不是只拦 GUI 按钮

- 按钮会变，mod 入口会变，**sink 不容易变**。
- Iris 证明「只拦材质包界面」不够。
- 直接 LWJGL 调用只能在 `SDLMisc` 层堵住。

### 6.4 兼容边界

| 项目 | 说明 |
|------|------|
| 目标版本 | Minecraft **26.3**（未混淆） |
| 不拦截 | 纯 native / JNI 自开文件夹、`Runtime.exec("explorer …")` 且不经上述 API |
| AWT `Desktop` | 26.3 主路径不用；若未来或老 mod 使用，可再加拦截 |

---

## 7. 验证建议

1. **原版**：资源包界面 → 打开文件夹 → 应立刻弹出 explorer，游戏不卡。
2. **Iris**：光影界面 → 打开光影文件夹 → 同上。
3. **路径**：中文路径、空格、网络盘各测一次（网络盘打开可能仍慢，但不应整局假死）。
4. **对照**：卸载本 mod 名现原问题，可确认命中环境因素。
5. **日志**：`logs/latest.log` 应有 `Opened path asynchronously: …`。

---

## 8. 版本与覆盖范围

| 版本 | 变更 | 覆盖 |
|------|------|------|
| 1.0.0 | `openPath` + 材质包界面 | 原版材质包 |
| 1.0.1 | + `openUri` | Iris 等 |
| 1.0.2 | + `SDL_OpenURL` | 直接 LWJGL / 其余 sink |

---

## 9. 参考

- 源码：`src/client/java/dev/folderopenpatch/client/`
- 字节码：`PackSelectionScreen`、`Blaze3D`、`SDLMisc.SDL_OpenURL`（MC 26.3 / LWJGL 3.4.3）
- 构建：Fabric Loom 1.17.x，Java 25，Fabric API `0.160.5+26.3`

---

*报告版本：对应 Folder Open Patch **1.0.2**（tag `v1.0.2`）*
