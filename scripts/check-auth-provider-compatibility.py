#!/usr/bin/env python3
"""Execute a released-API provider binary against candidate classes, without recompiling it.

This is a focused linkage/behavior gate, not a whole-repository ABI certification.
Maven resolves declared dependencies; no sibling source paths are added.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(*args, cwd):
    subprocess.run(args, cwd=cwd, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-jar", type=Path, required=True)
    parser.add_argument("--baseline-sha256", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    baseline = args.baseline_jar.resolve(strict=True)
    digest = hashlib.sha256(baseline.read_bytes()).hexdigest()
    if digest != args.baseline_sha256:
        parser.error("baseline artifact SHA-256 mismatch")
    run("mvn", "-q", "-pl", "quantum-models", "-DskipTests", "package", cwd=root)
    run("mvn", "-q", "-pl", "quantum-models", "dependency:build-classpath",
        "-DincludeScope=test", "-Dmdep.outputFile=target/compatibility-classpath.txt", cwd=root)
    module = root / "quantum-models"
    dependencies = (module / "target/compatibility-classpath.txt").read_text().strip()
    candidate = module / "target/classes"
    fixture = root / "compatibility/auth-provider/LegacyAuthProviderProbe.java"
    with tempfile.TemporaryDirectory(prefix="quantum-provider-compat-") as directory:
        # Old model classes are first only during compilation; the runtime contains no old model JAR.
        run("javac", "-proc:none", "-cp", str(baseline) + os.pathsep + dependencies,
            "-d", directory, str(fixture), cwd=root)
        run("java", "-cp", os.pathsep.join([directory, str(candidate), dependencies]),
            "LegacyAuthProviderProbe", cwd=root)
    print(json.dumps({"result": "passed", "baseline_sha256": digest,
                      "candidate": str(candidate), "scope": "AuthProvider and login response linkage"}))


if __name__ == "__main__":
    main()
