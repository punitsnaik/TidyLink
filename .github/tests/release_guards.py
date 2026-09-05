"""Run with python3 .github/tests/release_guards.py; no signing credentials needed."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap

workflow = (Path(__file__).parents[1] / 'workflows/release.yml').read_text()


def step(name):
    block = workflow.split('      - name: ' + name + '\n', 1)[1].split('\n      - name:', 1)[0]
    return textwrap.dedent(block.split('        run: |\n', 1)[1])


with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    build_tools = root / 'build-tools/test'
    build_tools.mkdir(parents=True)
    aapt = build_tools / 'aapt'
    aapt.write_text('#!/bin/sh\nprintf "%s\\n" "package: name=\'dev.punit.tidylink\' versionCode=\'12\' versionName=\'1.4.0\'"\n')
    signer = build_tools / 'apksigner'
    signer.write_text('#!/bin/sh\nprintf "%s\\n" "Signer #1 certificate DN: CN=Release" "Signer #1 certificate SHA-256 digest: aabb"\n')
    aapt.chmod(0o700)
    signer.chmod(0o700)

    def run(name, **values):
        env = {**os.environ, 'ANDROID_HOME': directory, **values}
        return subprocess.run(['bash', '-c', step(name)], env=env, cwd=directory,
                              capture_output=True, text=True).returncode

    assert run('Verify APK version matches tag', REF_NAME='v1.4.0') == 0
    assert run('Verify APK version matches tag', REF_NAME='v1.4.1') != 0
    assert run('Verify the release signer', EXPECTED_SHA256='AA:BB') == 0
    assert run('Verify the release signer', EXPECTED_SHA256='') != 0
    assert run('Verify the release signer', EXPECTED_SHA256='CC:DD') != 0
print('Release guards: 5 checks passed')
