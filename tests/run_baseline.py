#!/usr/bin/env python3
"""Run all gscript tests and save outputs to tests/baseline/ for before/after comparison."""
import os
import subprocess
import sys

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.join(BASE_DIR, "..")
BASELINE_DIR = os.path.join(BASE_DIR, "baseline")

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
    "test_wait_attach.py",
]

def main():
    if not os.path.isdir(BASELINE_DIR):
        os.makedirs(BASELINE_DIR)
    results = []
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
                timeout=120,
            )
        except subprocess.TimeoutExpired:
            print("  TIMEOUT")
            results.append((t, "TIMEOUT", ""))
            continue
        out = proc.stdout or ""
        err = proc.stderr or ""
        combined = "=== STDOUT ===\n" + out + "\n=== STDERR ===\n" + err
        base_name = t.replace(".py", ".out")
        with open(os.path.join(BASELINE_DIR, base_name), "w", encoding="utf-8") as f:
            f.write(combined)
        # Determine pass/fail from output
        last_line = ""
        for line in reversed(out.splitlines()):
            if line.strip():
                last_line = line.strip()
                break
        print("  -> %s" % last_line)
        results.append((t, "DONE", last_line))

    print("\n=== BASELINE SUMMARY ===")
    for t, status, last in results:
        print("  %-30s %s  %s" % (t, status, last))

if __name__ == "__main__":
    main()
