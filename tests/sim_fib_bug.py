# -*- coding: utf-8 -*-
"""
准确模拟 gscript VM 执行 fibonacci(10)，验证 return 后 env 不恢复的 bug。

复现 gscript VM 的关键语义：
- GSFunction.env 是共享字段，pushenv function 会修改它
- return 时 destroy() 调用 returnSpecScope("function")，只回退到 function env，不恢复到调用前
- 访问变量从 fib_func.env 开始向上查找

对比两种实现：
  BUGGY  : returnSpecScope("function") 只回退到 function env（当前实现）
  FIXED  : destroy 时再回退一层到 function env 的 parent
"""

class Env:
    def __init__(self, name, parent=None):
        self.name = name
        self.parent = parent
        self.vars = {}

    def get(self, name):
        env = self
        while env is not None:
            if name in env.vars:
                return env.vars[name]
            env = env.parent
        return None


class Func:
    """模拟 GSFunction：env 是共享字段"""
    def __init__(self):
        self.env = None
        self.name = "fibonacci"

    def addEnv(self, type_):
        return Env(type_, self.env)

    def setEnv(self, env):
        self.env = env

    def returnSpecScope(self, type_):
        """当前实现：回退到指定类型的 env（包括该 env 本身）"""
        while self.env is not None and self.env.parent is not None:
            if type_ == self.env.name:
                break
            self.setEnv(self.env.parent)

    def destroy_buggy(self):
        """当前实现：只 returnSpecScope('function')，不恢复到调用前"""
        self.returnSpecScope("function")

    def destroy_fixed(self):
        """修复实现：回退到 function env 的 parent（调用前的静态作用域）"""
        self.returnSpecScope("function")
        if self.env is not None and self.env.name == "function":
            self.setEnv(self.env.parent)


def run_fib(n_arg, fixed=False):
    """模拟执行 fibonacci(n_arg)"""
    fib_func = Func()
    fib_func.env = Env("global")  # 静态作用域 = global

    call_stack = []
    stack = []

    def eval_fib(args):
        """args = [this, n]"""
        # pushenv function
        new_env = fib_func.addEnv("function")
        new_env.vars["this"] = args[0]
        new_env.vars["fibonacci"] = fib_func
        new_env.vars["n"] = args[1]
        fib_func.setEnv(new_env)

        # if (n <= 1) return n;
        n_val = fib_func.env.get("n")
        if n_val <= 1:
            result = n_val
            # return: destroy
            if fixed:
                fib_func.destroy_fixed()
            else:
                fib_func.destroy_buggy()
            return result

        # return fibonacci(n - 1) + fibonacci(n - 2);
        # 计算 fibonacci(n - 1)
        n_val = fib_func.env.get("n")
        r1 = eval_fib([None, n_val - 1])

        # 计算 fibonacci(n - 2)
        # 注意：此时 fib_func.env 可能已被子调用污染
        n_val_buggy = fib_func.env.get("n")
        r2 = eval_fib([None, n_val_buggy - 2])

        result = r1 + r2
        # return: destroy
        if fixed:
            fib_func.destroy_fixed()
        else:
            fib_func.destroy_buggy()
        return result

    return eval_fib([None, n_arg])


def main():
    print("=" * 60)
    print("gscript fibonacci(10) bug 验证")
    print("=" * 60)

    # 正确答案
    def fib_correct(n):
        if n <= 1:
            return n
        return fib_correct(n - 1) + fib_correct(n - 2)

    print(f"正确结果 fibonacci(10) = {fib_correct(10)}")
    print()

    # 有 bug 的实现
    result_buggy = run_fib(10, fixed=False)
    print(f"BUGGY (当前实现) fibonacci(10) = {result_buggy}")
    print(f"  与用户报告的 -80 {'匹配' if result_buggy == -80 else '不匹配'}")
    print()

    # 修复后的实现
    result_fixed = run_fib(10, fixed=True)
    print(f"FIXED (修复后)   fibonacci(10) = {result_fixed}")
    print(f"  与正确结果 55 {'匹配' if result_fixed == 55 else '不匹配'}")
    print()

    # 验证多个值
    print("-" * 60)
    print("验证修复后多个 fibonacci 值：")
    all_correct = True
    for i in range(11):
        correct = fib_correct(i)
        fixed_result = run_fib(i, fixed=True)
        status = "OK" if fixed_result == correct else "FAIL"
        if fixed_result != correct:
            all_correct = False
        print(f"  fib({i}) = {fixed_result} (期望 {correct}) [{status}]")
    print()
    print(f"修复后所有值{'正确' if all_correct else '仍有错误'}")


if __name__ == "__main__":
    main()
