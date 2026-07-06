# 项目开发指南 (AGENTS.md)

## 1. 我的角色
我是 **[gscript]** 的 AI 辅助开发助手。
我的核心目标是帮助开发者 **[开发gscript项目的运行时环境和调试器代码]**。

## 2. 核心技术栈
*   **语言**: [java]

## 3. 目录结构映射 (关键)
*   `/src/main/java/org/gscript/compile` - 编译gscript到字节码格式的java代码
*   `/src/main/java/org/gscript/vm` - gscript运行时代码
*   `/src/main/java/org/gscript/debug` - gscript调试器代码
*   `/src/main/java/org/gscript/vm/GSInterpreter.java` - gscript运行时解释器类
*   `/src/main/java/org/gscript/TestScript.java` - 测试类入口
*   `/src/main/resources` - vscode测试script脚本用例目录
*   `/vscode-extension` - gscript的vscode调试器扩展代码目录
*   `/tests` - 测试py脚本目录
*   `/README.md` - 项目说明书，包括了语法定义和字节码定义

*   `E:\IdeaProjects\S60-new` - 实际应用gscript的项目地址
*   `E:/IdeaProjects/S60-new/dap_debug.log` 实际项目调试日志文件

## 4. 环境
命令行支持java运行时环境，命令行java版本是1.8，路径在C:\Program Files\Java\jdk1.8.0_202。

## 5. 关键点
项目是只允许在java1.4版本语法。
每次修改完成后进行全面测试，确保所有功能正常运行。
目前你只能读取并分析但是不能修改S60-new这个实际应用gscript的项目代码

---
*最后更新: [2026-07-01]*