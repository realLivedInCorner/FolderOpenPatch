# Folder Open Patch

修复 Minecraft **26.3**（Fabric）在「资源包 / 材质包」界面点击 **打开材质包文件夹** 时游戏无响应 / 卡死的问题。

## 问题原因（26.3 实测）

资源包界面按钮最终调用：

```text
PackSelectionScreen.lambda$init$0
  → Blaze3D.openPath(Path)
    → openUri(file://…)
      → SDLMisc.SDL_OpenURL(...)
```

在部分 Windows 环境下，`SDL_OpenURL`（底层 ShellExecute）会阻塞甚至拖死进程，表现为游戏窗口「未响应」。

## 修复方式

1. **异步打开**：独立 daemon 线程执行打开，立刻返回。
2. **Windows 专用路径**：`explorer.exe <绝对路径>`，绕开 `SDL_OpenURL` / ShellExecute 阻塞点。
3. **多层拦截**：
   - `Blaze3D.openPath`（覆盖材质包、光影包、截图等所有本地路径打开）
   - `PackSelectionScreen.lambda$init$0` 额外 redirect

## 环境要求

| 项目 | 版本 |
|------|------|
| Minecraft | 26.3 |
| Fabric Loader | ≥ 0.19.5 |
| Fabric API | 0.160.5+26.3 |
| Java（构建 / 运行） | **25** |
| Fabric Loom | 1.17+（本机使用 1.17.21） |

## 构建

需要 **JDK 25**（本机可用 `D:\Zulu\zulu-25`）。

```powershell
$env:JAVA_HOME = "D:\Zulu\zulu-25"
.\gradlew.bat build
# 或使用本机 Gradle：
# D:\Java\gradle-9.5.1\bin\gradle.bat build
```

产物：`build/libs/folder-open-patch-1.0.0.jar`

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/)（Minecraft 26.3）
2. 将 `fabric-api` 与本 mod 的 jar 放入 `mods/`
3. 启动游戏，在资源包界面点「打开文件夹」——游戏不应再卡死

## 开发

```powershell
$env:JAVA_HOME = "D:\Zulu\zulu-25"
.\gradlew.bat runClient
```

## 许可

MIT
