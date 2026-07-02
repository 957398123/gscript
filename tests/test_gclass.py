#!/usr/bin/env python3
"""
gclass 二进制文件格式端到端测试。

验证项：
1. 序列化正确性：dump 与 dumpgclass 输出完全一致（字节码↔源码行映射）
2. 执行正确性：direct run 与 rungclass 输出完全一致
3. 字符串空格：const s "hello world with spaces" 正确序列化/反序列化
4. CRC32 校验：篡改 .gclass 文件后应拒绝加载
5. 多脚本覆盖：gclass_test / multi_a / multi_b / semantics_test

用法：python test_gclass.py
"""

import os, subprocess, sys, tempfile, shutil

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.join(BASE_DIR, "..")
CLASSES_DIR = os.path.join(PROJECT_DIR, "target", "classes")
JAVA = os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-9.0.4") + r"\bin\java.exe"

passed = 0
failed = 0

def run_testscript(name, mode):
    """运行 TestScript，返回 (stdout, stderr) 文本"""
    cmd = [JAVA, "-cp", CLASSES_DIR, "org.gscript.TestScript", name]
    if mode:
        cmd.append(mode)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30, encoding="utf-8", errors="replace")
    return result.stdout, result.stderr

def assert_eq(label, actual, expected):
    global passed, failed
    if actual == expected:
        print(f"  [PASS] {label}")
        passed += 1
    else:
        print(f"  [FAIL] {label}")
        print(f"    expected: {repr(expected)[:200]}")
        print(f"    actual:   {repr(actual)[:200]}")
        failed += 1

def assert_true(label, cond, detail=""):
    global passed, failed
    if cond:
        print(f"  [PASS] {label}")
        passed += 1
    else:
        print(f"  [FAIL] {label} {detail}")
        failed += 1

def normalize_dump(stderr):
    """从 stderr 提取 dump 内容（去掉头尾标记行和 sourcePath 行），返回纯指令行列表"""
    lines = []
    in_dump = False
    for line in stderr.splitlines():
        if "==== bytecode" in line or "==== gclass bytecode" in line:
            in_dump = True
            continue
        if "==== end" in line:
            in_dump = False
            continue
        if in_dump:
            if line.startswith("sourcePath:"):
                continue
            lines.append(line.strip())
    return lines


print("=" * 60)
print("gclass 二进制文件格式端到端测试")
print("=" * 60)

# ===== 测试 1-4：对每个脚本验证序列化/执行一致性 =====
test_scripts = ["gclass_test", "multi_a", "multi_b", "semantics_test"]

for script_name in test_scripts:
    print(f"\n--- {script_name} ---")

    # 1. 先编译为 gclass
    _, compile_err = run_testscript(script_name, "compile")
    assert_true(f"{script_name}: compile 成功",
                "gclass written" in compile_err,
                f"stderr: {compile_err[:200]}")

    # 2. dump vs dumpgclass 对比
    _, dump_err = run_testscript(script_name, "dump")
    _, dumpgclass_err = run_testscript(script_name, "dumpgclass")
    dump_lines = normalize_dump(dump_err)
    dumpgclass_lines = normalize_dump(dumpgclass_err)
    assert_eq(f"{script_name}: dump 与 dumpgclass 一致", dumpgclass_lines, dump_lines)

    # 3. direct run vs rungclass 对比
    direct_out, _ = run_testscript(script_name, None)
    gclass_out, _ = run_testscript(script_name, "rungclass")
    assert_eq(f"{script_name}: run 与 rungclass 输出一致", gclass_out, direct_out)

# ===== 测试 5：字符串含空格验证 =====
print("\n--- 字符串含空格验证 ---")
direct_out, _ = run_testscript("gclass_test", None)
expected_strings = [
    "hello world with spaces",
    "A says: world",
    "error occurred",
]
for s in expected_strings:
    assert_true(f"字符串含空格: '{s}'", s in direct_out, f"output: {direct_out[:200]}")

# ===== 测试 6：CRC32 校验（篡改文件应拒绝加载）=====
print("\n--- CRC32 校验 ---")
gclass_path = os.path.join(CLASSES_DIR, "gtxt", "gclass_test.gclass")
assert_true(".gclass 文件存在", os.path.exists(gclass_path))

# 备份原文件
backup_path = gclass_path + ".bak"
shutil.copy2(gclass_path, backup_path)

try:
    # 篡改文件：修改 header 之后的某个字节（避开 magic，确保能读到 CRC 比对阶段）
    with open(gclass_path, "rb") as f:
        data = bytearray(f.read())

    # 篡改偏移 25 处的字节（常量池区域）
    if len(data) > 25:
        data[25] ^= 0xFF

    with open(gclass_path, "wb") as f:
        f.write(data)

    # 尝试加载篡改后的文件，应抛出 CRC32 mismatch
    _, corrupt_err = run_testscript("gclass_test", "rungclass")
    assert_true("CRC32 篡改检测：加载被拒绝",
                "CRC32 mismatch" in corrupt_err or "CRC32" in corrupt_err,
                f"stderr: {corrupt_err[:300]}")
finally:
    # 恢复原文件
    shutil.move(backup_path, gclass_path)

# ===== 测试 7：magic 损坏检测 =====
print("\n--- Magic 校验 ---")
shutil.copy2(gclass_path, backup_path)
try:
    with open(gclass_path, "rb") as f:
        data = bytearray(f.read())
    # 篡改 magic（前4字节）
    data[0] = 0x00
    with open(gclass_path, "wb") as f:
        f.write(data)
    _, magic_err = run_testscript("gclass_test", "rungclass")
    assert_true("Magic 损坏检测：加载被拒绝",
                "magic" in magic_err.lower(),
                f"stderr: {magic_err[:300]}")
finally:
    shutil.move(backup_path, gclass_path)

# ===== 测试 8：恢复后正常加载 =====
print("\n--- 恢复后正常加载 ---")
_, restore_err = run_testscript("gclass_test", "rungclass")
assert_true("恢复后 gclass 正常加载",
            "gclass loaded" in restore_err,
            f"stderr: {restore_err[:200]}")

# ===== 测试 9：FunctionTable 正确性 =====
print("\n--- FunctionTable 正确性 ---")
import re
# fundef 指令行格式： "   1 [line 27 ] fundef add 9"
fundef_re = re.compile(r'^\s*(\d+)\s+\[line\s+\d+\s*\]\s+fundef\s+(\S+)\s+(\d+)')
# 函数表条目行格式： "  add@ip2(len=9)"
ftentry_re = re.compile(r'^\s*(\S+)@ip(\d+)\(len=(\d+)\)')

for script_name in test_scripts:
    _, dumpgclass_err = run_testscript(script_name, "dumpgclass")
    # 从 dump 段提取 fundef 指令
    fundefs = []  # [(fundef_index, name, bodyLen)]
    in_ft = False
    ft_entries = []  # [(name, startIp, bodyLen)]
    for line in dumpgclass_err.splitlines():
        m = fundef_re.match(line)
        if m:
            idx = int(m.group(1))
            name = m.group(2)
            body_len = int(m.group(3))
            fundefs.append((idx, name, body_len))
        if "==== function table ====" in line:
            in_ft = True
            continue
        if "==== end function table ====" in line:
            in_ft = False
            continue
        if in_ft:
            m2 = ftentry_re.match(line)
            if m2:
                ft_entries.append((m2.group(1), int(m2.group(2)), int(m2.group(3))))
    # 验证：函数表条目数 == fundef 指令数
    assert_true(f"{script_name}: FunctionTable 条目数 == fundef 指令数",
                len(ft_entries) == len(fundefs),
                f"fundef={len(fundefs)}, ft_entries={len(ft_entries)}")
    # 验证：每个函数表条目与对应 fundef 匹配（name, startIp=index+1, bodyLen）
    for i, (fidx, fname, flen) in enumerate(fundefs):
        if i < len(ft_entries):
            ftname, ftip, ftlen = ft_entries[i]
            assert_true(f"{script_name}: 函数 '{fname}' 元数据匹配",
                        ftname == fname and ftip == fidx + 1 and ftlen == flen,
                        f"expected {fname}@ip{fidx+1}(len={flen}), got {ftname}@ip{ftip}(len={ftlen})")

# ===== 测试 10：RLE 压缩 + Attributes 标志 =====
print("\n--- RLE 压缩 + Attributes 标志 ---")
import struct
for script_name in test_scripts:
    gclass_path = os.path.join(CLASSES_DIR, "gtxt", script_name + ".gclass")
    assert_true(f"{script_name}: .gclass 文件存在", os.path.exists(gclass_path))
    if not os.path.exists(gclass_path):
        continue
    with open(gclass_path, "rb") as f:
        header = f.read(8)
    flags = struct.unpack(">H", header[6:8])[0]
    has_rle = bool(flags & 0x0004)
    has_attr = bool(flags & 0x0008)
    file_size = os.path.getsize(gclass_path)
    print(f"  {script_name}: size={file_size}B, RLE={has_rle}, Attributes={has_attr}")
    assert_true(f"{script_name}: FLAG_RLE_SOURCE_MAP 已设置", has_rle)
    assert_true(f"{script_name}: FLAG_HAS_ATTRIBUTES 已设置", has_attr)

# ===== 汇总 =====
print("\n" + "=" * 60)
print(f"结果: {passed} passed, {failed} failed")
print("=" * 60)
sys.exit(0 if failed == 0 else 1)
