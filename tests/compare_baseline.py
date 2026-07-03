#!/usr/bin/env python3
"""Run all gscript tests and compare outputs with tests/baseline/ (pre-migration)."""
import os
import subprocess
import sys
import difflib

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.join(BASE_DIR, "..")
BASELINE_DIR = os.path.join(BASE_DIR, "baseline")
NEW_DIR = os.path.join(BASE_DIR, "baseline_new")

TESTS = [
    "test_timer.py",
    "test_host_interaction.py",
    "test_gclass.py",
    "test_dap_e2e.py",
    "test_while_breakpoint.py",
    "test_multi_file.py",
    "test_cross_file_step.py",
    "test_step_catch.py",
    "test_path_mismatch.py",
    "test_timer_debug.py",
]

def main():
    if not os.path.isdir(NEW_DIR):
        os.makedirs(NEW_DIR)
    results = []
    total_pass = 0
    total_fail = 0
    for t in TESTS:
        path = os.path.join(BASE_DIR, t)
        if not os.path.isfile(path):
            print("SKIP (not found): %s" % t)
            continue
        print("=== Running %s ===" % t)
        try:
            proc = subprocess.run(
                [sys.executable, path],
                cwd=PROJECT_DIR,
                capture_output=True,
                text=True,
                timeout=180,
            )
        except subprocess.TimeoutExpired:
            print("  TIMEOUT")
            results.append((t, "TIMEOUT", 0, 0))
            continue
        out = proc.stdout or ""
        err = proc.stderr or ""
        combined = "=== STDOUT ===\n" + out + "\n=== STDERR ===\n" + err
        base_name = t.replace(".py", ".out")
        with open(os.path.join(NEW_DIR, base_name), "w", encoding="utf-8") as f:
            f.write(combined)

        # Count pass/fail from output
        for line in out.splitlines():
            if "[PASS]" in line:
                total_pass += 1
            elif "[FAIL]" in line:
                total_fail += 1

        # Compare with baseline
        baseline_path = os.path.join(BASELINE_DIR, base_name)
        if os.path.isfile(baseline_path):
            with open(baseline_path, "r", encoding="utf-8") as f:
                baseline_content = f.read()
            if combined == baseline_content:
                print("  -> MATCH (identical to baseline)")
                results.append((t, "MATCH", 0, 0))
            else:
                # Show diff summary
                base_lines = baseline_content.splitlines()
                new_lines = combined.splitlines()
                diff = list(difflib.unified_diff(base_lines, new_lines, lineterm="", n=1))
                diff_count = sum(1 for l in diff if l.startswith("+") and not l.startswith("+++"))
                diff_count += sum(1 for l in diff if l.startswith("-") and not l.startswith("---"))
                print("  -> DIFF (%d changed lines)" % diff_count)
                if diff_count <= 20:
                    for dl in diff[:40]:
                        print("    " + dl)
                results.append((t, "DIFF", diff_count, 0))
        else:
            print("  -> NO BASELINE")
            results.append((t, "NO_BASELINE", 0, 0))

    print("\n=== COMPARISON SUMMARY ===")
    for t, status, diff_count, _ in results:
        print("  %-30s %s" % (t, status))

    print("\n=== TEST RESULTS (pass/fail counts from output) ===")
    print("  Total PASS: %d" % total_pass)
    print("  Total FAIL: %d" % total_fail)

    # Return non-zero if any failures
    if total_fail > 0:
        sys.exit(1)

if __name__ == "__main__":
    main()
