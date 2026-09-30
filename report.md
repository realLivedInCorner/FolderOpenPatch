# Folder Open Patch — 技术分析报告

> 针对 **Minecraft 26.3（Fabric）**「资源包 / 材质包」界面点击 **打开材质包文件夹** 后游戏无响应的问题。  
> 基于对 `minecraft-client.jar` / `minecraft-common.jar` 的字节码反汇编结论。

---

## 1. 结论摘要

| 项目 | 结论 |
|------|------|
| 是否与电脑有关 | **有关**。同一份游戏 jar，不同机器表现可以完全不同。 |
| 是否服务端问题 | **否**。纯客户端 UI 操作。 |
| 是否渲染/GPU 问题 | **否**。不走渲染管线。 |
| 是否人人必现 | **否**。依赖 OS、shell 扩展、路径形态、安全软件等环境。 |
| 本 mod 的修复点 | 拦截 `Blaze3D.openUri` / `openPath`，改用异步 `explorer.exe <路径>` 打开目录。 |

---

## 2. 原版调用链（26.3 实测）

资源包界面「打开文件夹」按钮（`pack.openFolder`）在字节码中为：

```text
PackSelectionScreen.lambda$init$0(Button)
  → this.packDir                          // java.nio.file.Path
  → Blaze3D.openPath(Path)
      → path.normalize().toUri()
      → Blaze3D.openUri(URI)
          → Util.nonCriticalIoPool().execute(...)
          → SDLMisc.SDL_OpenURL(uri.toString())
```

要点：

1. 游戏 **已经** 把打开动作丢到 `nonCriticalIoPool`，理论上不应直接卡死渲染线程。
2. 真正的风险点是 **`SDL_OpenURL` 在 Windows 上的底层实现**（通常为 `ShellExecute` / `ShellExecuteEx`），以及该调用引发的进程级副作用。
3. 26.x **不再** 使用旧的 `Util.OperatingSystem#open` + AWT `Desktop.browseFileDirectory` 路径；旧版分析结论不能直接套用。

---

## 3. 可能触发「无响应 / 假死」的情形（尽可能列举）

下列情形可单独或叠加出现。同一台机器上，只要命中其中一条或几条，就可能表现为「点开文件夹后游戏未响应」。

### 3.1 Windows Shell / 资源管理器侧

| # | 情形 | 机制说明 |
|---|------|----------|
| W1 | 第三方资源管理器扩展（右键菜单、云盘、压缩软件、输入法、下载工具等）阻塞 | `ShellExecute` 打开目录时会加载 shell extension；某个 COM 扩展卡死或死锁，打开调用长时间不返回。 |
| W2 | 杀毒 / EDR / 主动防御拦截 `ShellExecute` | 安全软件 hook ShellExecute 后做异步扫描或弹窗确认，调用被拖住。 |
| W3 | Windows Search / 索引服务正忙 | 目标目录被索引或重建索引时，shell 打开可能变慢甚至卡住。 |
| W4 | 资源管理器进程本身异常 / 挂起 | 若依赖已有 explorer 实例做 DDE/COM 协同，explorer 无响应会连带拖住打开请求。 |
| W5 | 组策略 / 企业管控限制打开特定路径 | 某些托管环境禁止 shell 打开文件系统路径。 |
| W6 | 默认「文件夹」处理程序被第三方接管 | 若文件夹关联被改（少见），`file://` 目录可能被错误程序处理。 |

### 3.2 路径与文件系统

| # | 情形 | 机制说明 |
|---|------|----------|
| P1 | 路径含非 ASCII / 中文 / 特殊字符 | 与代码页、ANSI/Unicode 转换、shell 解析有关；历史上是高发点。 |
| P2 | 路径过长（接近或超过 MAX_PATH） | 未启用长路径时，shell 打开可能失败或异常等待。 |
| P3 | 路径含空格、`#`、`%`、`&` 等 | 经 `file://` URI 编码后再交给 SDL/shell，解析边界情况多。 |
| P4 | 目录不存在 | `packDir` 尚未创建时打开；不同实现对「不存在目录」处理不一致，可能失败或挂起。 |
| P5 | 网络驱动器 / 映射盘 / UNC 路径 | 打开需网络往返；网络慢、凭据弹窗、断线都会表现为长时间无响应。 |
| P6 | OneDrive / 网盘同步占位文件（Files On-Demand） | 目录内大量占位项，shell 枚举与同步组件交互，可能极慢。 |
| P7 | 权限不足 / ACL 拒绝 | 无读权限时 shell 可能弹 UAC 或安全对话框，阻塞调用。 |
| P8 | 目录位于只读介质、受保护系统目录、符号链接/联接异常 | 打开路径解析异常。 |
| P9 | 实例目录路径含奇怪启动器自定义结构 | 第三方启动器把 `.minecraft` 放在异常位置时更容易踩中 P1–P8。 |

### 3.3 `file://` URI + SDL 特有

| # | 情形 | 机制说明 |
|---|------|----------|
| S0 | **模组直接调用 `Blaze3D.openUri(file://…)`**（已实锤） | 例如 Iris `ShaderPackScreen.openShaderPackFolder()`：`CompletableFuture.runAsync(() -> Blaze3D.openUri(directoryUri))`。即便外层异步，仍走 `SDL_OpenURL`，在本机环境下依旧可假死。 |

| # | 情形 | 机制说明 |
|---|------|----------|
| S1 | 目录 URI 与「打开文档」语义混用 | `Path.toUri()` 得到 `file:///C:/.../resourcepacks/`，SDL/Shell 可能按 URL/文档打开而非「在资源管理器中浏览目录」，边界行为不稳定。 |
| S2 | `SDL_OpenURL` 对 `file://` 的平台实现差异 | 不同 SDL 构建、不同 Windows 版本行为不一致。 |
| S3 | 打开失败后重试 / 错误处理不当 | 失败路径若同步等待或重试，会放大卡顿。 |
| S4 | 与游戏内其他线程争用 | 虽在 `nonCriticalIoPool`，若 pool 任务堆积或与文件监视（PackSelectionScreen 的 Watcher）互相影响，体感更差。 |

### 3.4 游戏 / 启动环境

| # | 情形 | 机制说明 |
|---|------|----------|
| G1 | 使用第三方启动器（PCL、HMCL、MultiMC 等） | 可能修改工作目录、环境变量、注入 JVM 参数，间接影响 shell/进程环境。 |
| G2 | 便携版 / 绿色版实例路径过深 | 叠加 P2。 |
| G3 | 同时开着大量 pack / Watcher 扫描 | 打开文件夹前后若正在扫目录，IO 压力大。 |
| G4 | 游戏窗口全屏 / 无边框全屏 + 焦点切换异常 | shell 弹出资源管理器时焦点/独占全屏切换异常，用户感觉「卡死」。 |
| G5 | 系统负载极高 / 内存不足 / 磁盘 100% | 任何同步或 COM 调用都可能被拖长。 |
| G6 | 用户账户控制（UAC）弹窗未注意 | 打开触发提权/确认时，游戏线程旁观感为无响应。 |

### 3.5 与「本机有关」的归纳

- **强相关**：Windows 版本与补丁、已装 shell 扩展、杀软策略、路径字符集、网络盘/云盘、启动器与实例布局。  
- **弱相关或无关**：显卡型号、游戏画质、多人联线、服务端、材质包内容本身（只要不阻止目录打开）。  
- **因此**：A 电脑秒开、B 电脑假死，是预期现象，不是「只有你电脑坏了」或「只有游戏坏了」的二选一，而是 **游戏实现方式 × 本机环境** 的组合结果。

---

## 4. 为什么「已经异步」仍可能像卡死

`Blaze3D.openUri` 确实提交到了 `nonCriticalIoPool`：

```text
openUri → TracingExecutor.execute(λ SDL_OpenURL)
```

仍可能被用户感知为「无响应」的原因包括：

1. **进程级阻塞**：某些 shell 扩展/杀软 hook 会影响进程内 COM 初始化或消息循环，不限于那一个线程。  
2. **模态对话框 / 焦点抢占**：资源管理器或安全软件弹窗抢焦点，全屏游戏看起来死了。  
3. **错误重试或二次同步调用**（若存在）放大等待。  
4. **Watcher + 列表刷新** 与打开动作叠加，IO 卡顿。  
5. 用户把「长时间打不开 + 游戏无画面更新」统称为未响应，即使渲染线程理论上还在跑。

本 mod 的策略是：**完全不走 `SDL_OPENURL` 打开目录**，改为后台线程直接：

```text
explorer.exe <绝对路径>
```

对「打开一个本地目录」来说，这是 Windows 上更短、更可控的路径。

---

## 5. 触发场景速查表

| 场景描述 | 更可能命中 |
|----------|------------|
| 纯本地 C 盘 ASCII 路径、无杀软 | 通常不触发 |
| 中文用户名 / 中文路径 | P1 |
| 打开 OneDrive 下的实例 | P6 |
| 开了 360 / 火绒 / 企业终端防护 | W2 |
| 装了 Bandizip / 云盘右键扩展 | W1 |
| 实例在网络盘 | P5 |
| 首次点击后目录还没生成 | P4 |
| 低配机 + 磁盘满载 | G5 |
| 全屏独占下点打开 | G4 |

---

## 6. 本 Mod 的修复设计

| 层级 | 目标 | 实现 |
|------|------|------|
| 主拦截 | 所有本地文件/目录 URI | Mixin `Blaze3D.openUri` → `file:` 则 `SafeFolderOpener` → cancel |
| 路径拦截 | `Path` 直开 | Mixin `Blaze3D.openPath` → 同上 |
| 副拦截 | 资源包按钮专用 | Mixin `PackSelectionScreen.lambda$init$0` Redirect |
| 非 file | http/https 等 | 不拦截，保持原版（更新链接等） |
| 执行 | 不阻塞、不走 SDL | daemon 线程 + `explorer.exe`（Windows）/ `open`（mac）/ `xdg-open`（Linux） |
| 兜底 | 目录不存在 | 先 `mkdirs` 再打开 |

### 6.1 与 Iris 的关系

Iris 光影包界面「打开光影文件夹」实际代码为：

```java
private void openShaderPackFolder() {
    CompletableFuture.runAsync(() ->
        Blaze3D.openUri(Iris.getShaderpacksDirectoryManager().getDirectoryUri()));
}
```

- 外层 `runAsync` **不能**避免 `SDL_OpenURL` 挂起（进程级/焦点问题仍在）。
- 因此必须拦 `openUri`，而不仅是 `openPath`。
- 同文件更新提示处的 `Blaze3D::openUri` 传入的是 **http(s) 链接**，本 mod 不拦截，仍可正常打开更新页。

兼容说明：

- 目标版本：**Minecraft 26.3**（未混淆，Mojang 官方命名）。  
- 依赖：Fabric Loader ≥ 0.19.5，Fabric API `0.160.5+26.3`，Java 25。  
- 若未来版本改回 AWT 或换 API，需同步更新 Mixin 目标。

---

## 7. 验证建议

1. 在「曾经会假死」的机器上：资源包界面点「打开文件夹」，应立刻弹出 explorer，游戏不卡。  
2. 路径含中文、空格时再测一次。  
3. 网络盘 / OneDrive 路径再测一次（打开可能仍慢，但游戏不应整体假死）。  
4. 对照：卸载本 mod 名现原问题，可确认环境命中上述表中项。

---

## 8. 参考

- 本仓库源码：`src/client/java/dev/folderopenpatch/client/`  
- 字节码依据：`net.minecraft.client.gui.screens.packs.PackSelectionScreen`、`com.mojang.blaze3d.Blaze3D`（26.3 client jar）  
- 构建：Fabric Loom 1.17.x，`net.fabricmc.fabric-loom`，Java 25  

---

*报告对应 mod 版本：1.0.1（tag `v1.0.1`）；1.0.0 起覆盖原版材质包路径，1.0.1 起覆盖 Iris 等模组的 `openUri` 路径。*
