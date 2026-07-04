# gscript Debug

gscript 语言的 VSCode 调试扩展，提供：

- **调试器**：Launch（stdio）+ Attach（socket）两种模式
- **语法高亮**：基于 TextMate 语法的 gscript 语法支持
- **断点调试**：行断点、单步（步入/步过/步出）、暂停、调用栈、变量查看、表达式求值

## 安装

### 1. 构建调试适配器 jar

在项目根目录执行：

```bash
mvn package -DskipTests
```

生成 `target/gscript-1.0-SNAPSHOT.jar`（纯项目 jar，零外部依赖）。

> **JDK 版本要求**：必须用 **JDK 1.8** 构建（`-source 1.4 -target 1.4`）。JDK 1.8 是最后一个支持 `-source 1.4` 的版本；JDK 9+ 最低只能 `-source 1.6`，无法生成真 1.4 字节码。代码本身仅用 Java 1.4 语法与 API，无泛型/枚举/注解/自动装箱。

### 2. 配置 jar 路径

在 VSCode 设置中配置 `gscript.jarPath` 指向生成的 jar：

```json
{
  "gscript.jarPath": "e:/JProjects/gscript/target/gscript-1.0-SNAPSHOT.jar",
  "gscript.javaPath": "java"
}
```

或在 `launch.json` 的单条配置中覆盖：

```json
{
  "type": "gscript",
  "request": "launch",
  "name": "Launch gscript",
  "files": ["${file}"],
  "stopOnEntry": false,
  "jarPath": "e:/JProjects/gscript/target/gscript-1.0-SNAPSHOT.jar"
}
```

### 3. 安装扩展

**方式 A（开发模式，推荐开发期使用）**：将 `vscode-extension` 目录复制到 VSCode 扩展目录，或在 VSCode 中用「扩展开发宿主」打开此目录按 F5 调试。

**方式 B（打包安装）**：

```bash
cd vscode-extension
npm install -g @vscode/vsce
vsce package
# 安装生成的 .vsix
code --install-extension gscript-debug-0.2.1.vsix
```

## 使用

### Launch 模式（最常用）

1. 打开一个 gscript 脚本（`.gs` / `.gscript` / `.script`）
2. 在行号左侧点击设置断点
3. 按 F5 选择「Launch gscript」

VSCode 会自动生成默认 `launch.json`：

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "gscript",
      "request": "launch",
      "name": "Launch gscript (current file)",
      "files": ["${file}"],
      "stopOnEntry": false
    },
    {
      "type": "gscript",
      "request": "attach",
      "name": "Attach to gscript (debug mode)",
      "host": "localhost",
      "port": 4711,
      "localRoot": "${workspaceFolder}/src/main/resources",
      "remoteRoot": "",
      "stopOnEntry": false
    },
    {
      "type": "gscript",
      "request": "attach",
      "name": "Attach to gscript (runtime)",
      "host": "localhost",
      "port": 4711,
      "localRoot": "${workspaceFolder}/src/main/resources",
      "remoteRoot": ""
    }
  ]
}
```

### Attach 模式

1. 手动启动调试适配器（socket 模式）：

   ```bash
   java -jar target/gscript-1.0-SNAPSHOT.jar --port=4711
   ```

2. 在 VSCode 中选择「Attach to gscript」配置，按 F5。

Attach 模式有两种启用方式：
- **debug 模式（`waitForDebugger`）**：gscript 程序启动时阻塞等待 VSCode 连接后执行
- **运行时 attach（`attachReady`）**：gscript 程序已运行，VSCode 随后附加（如同 `node --inspect`）

Attach 模式适用于：调试适配器需在 IDE 之外单独运行（容器内、远程机器、或运行时动态附加）的场景。`localRoot`/`remoteRoot` 用于远程 sourcePath 到本地源码的路径映射。

## 调试功能

| 功能 | 支持情况 | 说明 |
|------|---------|------|
| 行断点 | ✅ | 点击行号左侧设置 |
| 单步步过 | ✅ | F10 |
| 单步步入 | ✅ | F11 |
| 单步步出 | ✅ | Shift+F11 |
| 继续 | ✅ | F5 |
| 暂停 | ✅ | Ctrl+Pause |
| 调用栈 | ✅ | 显示函数调用链 |
| 变量查看 | ✅ | Local（沿作用域链）+ Global |
| 对象展开 | ✅ | 点击变量前的展开箭头 |
| 表达式求值 | ✅ | 支持 `a.b.c` 形式的标识符与属性访问 |
| 条件断点 | ❌ | 暂不支持 |
| 异常断点 | ✅ | VSCode 断点面板勾选「Caught/Uncaught Exceptions」即生效；首次抛出挂起，continue 一次跨帧传播不再重复挂起 |
| stopOnEntry | ✅ | 在首条指令前挂起 |

## 表达式求值说明

调试挂起时，在「调试控制台」或悬停变量上可求值。当前采用**轻量求值**（不经过 VM 执行，避免污染暂停状态的栈）：

- 支持：标识符（`a`）、点号属性访问（`a.b.c`）
- 不支持：算术运算、函数调用、字面量表达式

例如 `counter`、`obj.name` 可求值，但 `a + b`、`foo()` 不可。

## 注意事项

1. **Java 版本**：构建需 **JDK 1.8**（生成 major version=48 的真 1.4 字节码，可在 JDK 1.4 运行时执行）；运行调试适配器无版本下限要求。零外部依赖，原 Gson 已替换为自研 JSON 库。
2. **注释语法**：gscript Lexer 同时支持 `//` 单行注释和 `/* */` 块注释（含跨行、行内、含斜杠路径）。
3. **输出重定向**：Launch 模式下 `console.log` 输出会通过 DAP "output" 事件显示在「调试控制台」，而非直接写 stdout（stdout 被 DAP 协议占用）。
4. **单线程模型**：gscript 解释器单线程，调试器固定线程 ID 为 1。
5. **launch.json 属性**：v0.2.0+ 起 `localRoot`/`remoteRoot`/`stopOnEntry` 在 launch + attach 模式均允许。若仍报「属性不允许」，确认已安装 v0.2.0+（`code --list-extensions --show-versions`）。
