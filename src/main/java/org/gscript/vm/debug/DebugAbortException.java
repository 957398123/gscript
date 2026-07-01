package org.gscript.vm.debug;

/**
 * 调试中止异常。
 *
 * <p>当用户在调试器中点击"停止"或调试会话被终止时，{@link DebugController} 在解释器线程
 * 挂起等待处抛出此异常，用于沿递归 {@code eval} 调用栈快速展开，终止脚本执行。
 *
 * <p>这是一个不受检异常（继承 {@link RuntimeException}），会被解释器 {@code eval} 方法中
 * 的 {@code catch (Exception)} 捕获——因此在 {@code catch (Exception)} 首行必须判断并重抛，
 * 避免被当作普通虚拟机异常处理。
 *
 * <p>该异常仅用于调试控制流，不携带任何业务语义，不应被业务代码捕获。
 */
public class DebugAbortException extends RuntimeException {

    public DebugAbortException() {
        super("Debug session aborted.");
    }
}
