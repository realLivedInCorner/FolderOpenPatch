# Folder Open Patch

修复 **Minecraft 26.3（Fabric）** 中点击「打开材质包 / 光影包文件夹」后**游戏无响应、假死**的问题。

面向玩家的客户端修复 mod：不改游戏玩法，只把「打开本地文件夹 / 链接」改成安全、非阻塞的系统调用。

---

## 功能

- 资源包界面「打开文件夹」不再卡死游戏
- 覆盖 Iris 等 mod 的「打开光影文件夹」
- 覆盖所有走 `Blaze3D` / `SDL_OpenURL` 的本地路径与 URL 打开
- Windows / macOS / Linux 均有对应打开方式（Windows 用 `explorer.exe`）

---

## 问题简述（26.3）

原版调用链（字节码实测）：

```text
PackSelectionScreen（打开材质包文件夹按钮）
  → Blaze3D.openPath(Path)
    → Blaze3D.openUri(file://…)
      → Util.nonCriticalIoPool().execute(…)
        → SDLMisc.SDL_OpenURL(...)     ← 真正挂起点
```

`SDL_OpenURL` 在部分 Windows 环境下会经 ShellExecute 拖住进程，表现为「未响应」。  
这与**本机环境**强相关（资源管理器扩展、杀软、路径形态、云盘等），不是人人必现。

Iris 示例（绕过 `openPath`，直接 `openUri`）：

```java
// ShaderPackScreen.openShaderPackFolder
CompletableFuture.runAsync(() ->
    Blaze3D.openUri(Iris.getShaderpacksDirectoryManager().getDirectoryUri()));
```

外层 `runAsync` 不能避免 `SDL_OpenURL` 挂起，因此必须拦更底层的 API。

---

## 修复原理

多层拦截（由外到内）：

| 层级 | 目标 | 说明 |
|------|------|------|
| 1 | `SDLMisc.SDL_OpenURL`（CharSequence / ByteBuffer） | 最终 sink；直接调 LWJGL 的 mod 也覆盖 |
| 2 | `Blaze3D.openUri` | Iris 等 |
| 3 | `Blaze3D.openPath` | 原版材质包等 `Path` |
| 4 | `PackSelectionScreen` | 材质包按钮 redirect 兜底 |

打开策略：

- **本地文件 / 目录 / `file:` URI** → Windows：`explorer.exe <绝对路径>`
- **http(s) / mailto 等** → 同样系统打开，不进 SDL
- 一律 **daemon 线程异步**，客户端线程立刻返回
- 目录不存在时先 `mkdirs` 再打开

---

## 兼容与需求

| 项目 | 版本 |
|------|------|
| Minecraft | **26.3** |
| Fabric Loader | ≥ 0.19.5 |
| Fabric API | 0.160.5+26.3 |
| Java（运行） | **25** |
| 侧边 | 仅客户端（Client） |

26.x 为**未混淆**客户端，构建使用 Fabric Loom 1.17+，无需 yarn/mojmap mappings。

---

## 安装（玩家）

1. 安装 [Fabric Loader](https://fabricmc.net/use/)（Minecraft **26.3**）
2. 下载 [Releases](https://github.com/realLivedInCorner/FolderOpenPatch/releases) 中的 `folder-open-patch-x.y.z.jar`
3. 将 **Fabric API** 与本 mod 的 jar 放入 `mods/`
4. 启动游戏 → 资源包 / 光影界面 → 打开文件夹

**建议使用最新版（1.0.2+）**，已覆盖 Iris 与直接 `SDL_OpenURL` 的情况。

---

## 构建（开发者）

### 环境

- **JDK 25**（必需，编译目标 `release 25`）
- 网络（首次拉 Minecraft / Fabric 依赖）
- Gradle：项目 wrapper，或本机 Gradle 8/9

### 构建命令

```powershell
cd FolderOpenPatch
$env:JAVA_HOME = "D:\Zulu\zulu-25"   # 改成你的 JDK 25 路径
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat build
# 或：D:\Java\gradle-9.5.1\bin\gradle.bat build
```

产物：

```text
build\libs\folder-open-patch-<version>.jar
```

### 常用任务

| 命令 | 作用 |
|------|------|
| `build` | 编译并打包 |
| `clean` | 清理 `build/` |
| `runClient` | 启动开发客户端 |
| `build --refresh-dependencies` | 强制刷新依赖 |

> 若报「不支持发行版本 25」，说明 Gradle 用了错误的 Java，先设置 `JAVA_HOME` 为 JDK 25。

---

## 版本历史

| 版本 | 内容 |
|------|------|
| **1.0.2** | 拦截 `SDLMisc.SDL_OpenURL`（最终 sink），覆盖直接调 LWJGL 的 mod |
| **1.0.1** | 拦截 `Blaze3D.openUri`，修复 Iris 光影文件夹假死 |
| **1.0.0** | 首版：拦截 `Blaze3D.openPath` + 材质包界面 |

---

## 项目结构

```text
FolderOpenPatch/
├── src/client/java/dev/folderopenpatch/client/
│   ├── FolderOpenPatchClient.java      # 客户端入口
│   ├── SafeFolderOpener.java           # 异步 / 系统打开实现
│   └── mixin/
│       ├── Blaze3DOpenPathMixin.java
│       ├── Blaze3DOpenUriMixin.java
│       ├── PackSelectionScreenMixin.java
│       └── SdlOpenUrlMixin.java
├── src/client/resources/folderopenpatch.client.mixins.json
├── src/main/resources/fabric.mod.json
├── report.md                           # 详细技术分析报告
├── build.gradle / gradle.properties
└── README.md
```

---

## 文档

- 触发条件、调用链、与电脑环境的关系：见 [**report.md**](./report.md)

---

## 贡献与反馈

欢迎提交 Issue / PR：

- 仍会假死的界面或 mod（附日志与版本）
- 特定路径 / 启动器 / 系统上的复现步骤
- 新的打开 API 调用点（截图、日志、崩溃报告）

---

## 许可

[MIT](./LICENSE)
