// gscript VSCode 调试扩展入口
//
// 职责：
//   1. 注册 DebugAdapterDescriptorFactory：
//      - launch 模式 → DebugAdapterExecutable（java -jar <jarPath> --stdio）
//      - attach 模式 → DebugAdapterServer({host, port})
//   2. 解析 jarPath / javaPath（launch.json 字段优先于 VSCode 设置）
//   3. 提供 DebugConfigurationProvider：补全默认值、校验 program 字段
//
// 实现语言：纯 JavaScript（CommonJS），无需 npm install / 编译即可加载。

const vscode = require('vscode');
const fs = require('fs');

/**
 * @param {vscode.ExtensionContext} context
 */
function activate(context) {
    const factory = new GscriptDebugAdapterDescriptorFactory();
    const provider = new GscriptDebugConfigurationProvider();

    context.subscriptions.push(
        vscode.debug.registerDebugConfigurationProvider('gscript', provider)
    );
    context.subscriptions.push(
        vscode.debug.registerDebugAdapterDescriptorFactory('gscript', factory)
    );

    console.log('[gscript-debug] 扩展已激活');
}

function deactivate() {
    console.log('[gscript-debug] 扩展已停用');
}

/**
 * 调试适配器描述符工厂。
 * 根据请求类型返回 stdio 进程（launch）或 socket 服务器（attach）。
 */
class GscriptDebugAdapterDescriptorFactory {
    /**
     * @param {vscode.DebugSession} session
     * @returns {vscode.ProviderResult<vscode.DebugAdapterDescriptor>}
     */
    createDebugAdapterDescriptor(session) {
        const config = session.configuration;
        if (config.request === 'attach') {
            const host = config.host || 'localhost';
            const port = config.port;
            return new vscode.DebugAdapterServer(port, host);
        }
        // launch（默认）
        const javaPath = resolveJavaPath(config);
        const jarPath = resolveJarPath(config);
        if (!jarPath) {
            // 路径未配置时返回错误，避免进程启动失败导致难以排查
            throw new Error(
                '未配置 gscript 调试适配器 jar 路径。请在 VSCode 设置 "gscript.jarPath" 或在 launch.json 中设置 "jarPath"。' +
                '\njar 由 mvn package 生成（target/gscript-1.0-SNAPSHOT.jar，含 Gson 的 fat jar）。'
            );
        }
        if (!fs.existsSync(jarPath)) {
            throw new Error('gscript 调试适配器 jar 不存在: ' + jarPath +
                '\n请先执行 mvn package 生成 fat jar。');
        }
        const args = ['-jar', jarPath, '--stdio'];
        const options = {};
        return new vscode.DebugAdapterExecutable(javaPath, args, options);
    }
}

/**
 * 调试配置提供器：补全默认值、校验必填字段。
 */
class GscriptDebugConfigurationProvider {
    /**
     * @param {vscode.WorkspaceFolder} folder
     * @param {vscode.DebugConfiguration} config
     * @param {vscode.CancellationToken} token
     * @returns {Promise<vscode.DebugConfiguration>}
     */
    async resolveDebugConfiguration(folder, config, token) {
        // 若用户直接按 F5 未选配置，补全为 launch 当前文件
        if (!config.type && !config.request && !config.name) {
            config.type = 'gscript';
            config.request = 'launch';
            config.name = 'Launch gscript';
            config.program = '${file}';
            config.stopOnEntry = false;
        }
        if (config.request === 'launch') {
            if (!config.program) {
                await vscode.window.showErrorMessage('请指定要调试的脚本路径 (program)');
                return undefined;
            }
        }
        return config;
    }
}

/**
 * 解析 java 路径：launch.json 的 javaPath > gscript.javaPath 设置 > "java"
 */
function resolveJavaPath(config) {
    if (config.javaPath) return config.javaPath;
    const fromSetting = vscode.workspace.getConfiguration('gscript').get('javaPath');
    if (fromSetting) return fromSetting;
    return 'java';
}

/**
 * 解析 jar 路径：launch.json 的 jarPath > gscript.jarPath 设置
 */
function resolveJarPath(config) {
    if (config.jarPath) return config.jarPath;
    return vscode.workspace.getConfiguration('gscript').get('jarPath') || '';
}

module.exports = { activate, deactivate };
