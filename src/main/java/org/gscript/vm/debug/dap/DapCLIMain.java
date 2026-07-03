package org.gscript.vm.debug.dap;

import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * DAP 调试适配器 CLI 入口。
 *
 * <p>VSCode 调试扩展通过两种方式启动本程序：
 * <ul>
 *   <li><b>stdio 模式</b>（launch）：{@code java -jar gscript.jar --stdio}
 *       —— DAP 消息通过标准输入/输出传输。</li>
 *   <li><b>socket 模式</b>（attach）：{@code java -jar gscript.jar --port=4711}
 *       —— 本程序监听指定端口，VSCode 通过 socket 连接传输 DAP 消息。</li>
 * </ul>
 *
 * <p>关键点：stdio 模式下必须在 {@link DapServer} 重定向 {@code System.out} 之前
 * 保存原始 stdout 引用，因为 DAP 协议本身占用 stdout 传输消息，而程序输出
 * （{@code console.log}）会被重定向为 DAP "output" 事件，二者不能混用。
 */
public class DapCLIMain {

    public static void main(String[] args) {
        String mode = "stdio";
        int port = 4711;

        // 解析参数
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--stdio".equals(arg)) {
                mode = "stdio";
            } else if (arg.startsWith("--port=")) {
                mode = "socket";
                try {
                    port = Integer.parseInt(arg.substring("--port=".length()));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port: " + arg);
                    System.exit(1);
                }
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage();
                return;
            }
        }

        try {
            if ("stdio".equals(mode)) {
                runStdio();
            } else {
                runSocket(port);
            }
        } catch (Exception e) {
            // 异常已无法通过 DAP 通道回报（传输层可能已断开），写到 stderr 便于排查
            System.err.println("DAP adapter fatal error: " + e);
            System.exit(1);
        }
    }

    /**
     * stdio 模式：DAP 消息通过标准输入/输出传输。
     *
     * <p>必须在构造 DapServer 之前捕获原始 stdout，因为 DapServer 构造后会在
     * configurationDone 时把 System.out 重定向到 DAP "output" 事件，原始 stdout
     * 仍由 DAP 协议专用。
     */
    private static void runStdio() {
        // 捕获原始 stdout（DapServer 会重定向 System.out，但 DAP 消息仍需写原始 stdout）
        InputStream in = System.in;
        PrintStream rawOut = new PrintStream(System.out, true);
        // 关闭原 System.out 的自动刷新包装并不必要，rawOut 直接持有底层 FileOutputStream
        DapServer server = new DapServer(in, rawOut);
        server.run();
    }

    /**
     * socket 模式：监听指定端口，accept 一个连接后用该 socket 传输 DAP 消息。
     *
     * <p>attach 场景下 VSCode 会先启动本程序（带 --port=N），然后通过
     * DebugAdapterServerDescriptor 连接到该端口。socket 模式下 stdout 不被 DAP 占用，
     * 但仍按 stdio 模式的逻辑统一处理（DapServer 重定向 System.out 为 output 事件，
     * DAP 消息走 socket 输出流）。
     */
    private static void runSocket(int port) throws Exception {
        ServerSocket serverSocket = new ServerSocket(port);
        try {
            // 仅接受一次连接（VSCode 调试会话期间保持长连接）
            Socket socket = serverSocket.accept();
            try {
                InputStream in = socket.getInputStream();
                PrintStream out = new PrintStream(socket.getOutputStream(), true);
                DapServer server = new DapServer(in, out);
                server.run();
            } finally {
                try {
                    socket.close();
                } catch (Exception e) {
                }
            }
        } finally {
            try {
                serverSocket.close();
            } catch (Exception e) {
            }
        }
    }

    private static void printUsage() {
        System.out.println("gscript DAP debug adapter");
        System.out.println("Usage:");
        System.out.println("  java -jar gscript.jar --stdio        Run in stdio mode (for launch)");
        System.out.println("  java -jar gscript.jar --port=PORT    Run in socket mode (for attach)");
        System.out.println("  java -jar gscript.jar --help         Show this help");
    }
}
