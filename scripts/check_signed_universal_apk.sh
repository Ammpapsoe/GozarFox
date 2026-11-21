#!/usr/bin/env bash
# Verify the release APK without uploading binaries or exposing key material.
set -euo pipefail
apk=${1:?apk required}
report=${2:?evidence path required}
expected=800d24fa5cc38d2a63c216051d4710b950b0b3c4f457096ccfc11794a2b3bfd3
buildtools="${ANDROID_HOME:?}/build-tools/36.0.0"
test -s "$apk"
info=$("$buildtools/apksigner" verify --verbose --print-certs "$apk")
grep -Fxq 'Number of signers: 1' <<< "$info"
actual=$(awk '/Signer #1 certificate SHA-256 digest:/ {print $NF}' <<< "$info")
if [[ "$actual" != "$expected" ]]; then
    echo '::error::APK signing certificate does not match v1.19.0' >&2
    exit 1
fi
badging=$("$buildtools/aapt" dump badging "$apk")
manifest=$("$buildtools/aapt" dump xmltree "$apk" AndroidManifest.xml)
if grep -Fq RecoveryFixtureActivity <<< "$manifest"; then
    echo '::error::Unexpected recovery fixture in release APK' >&2
    exit 1
fi
python3 - "$apk" "$report" "$actual" "$badging" <<'PY'
import hashlib, json, os, re, subprocess, sys, zipfile
from pathlib import Path
apk, report = map(Path, sys.argv[1:3])
certificate, badging = sys.argv[3:]
root = Path('app/build.gradle').read_text()
expected_version = re.search(r"^\s*versionName '([^']+)'", root, re.M)[1]
expected_code = re.search(r'^\s*versionCode (\d+)', root, re.M)[1]
package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
if not package or package.groups() != ('com.gozar', expected_code, expected_version):
    raise SystemExit('Unexpected release package name, version or version code')
with zipfile.ZipFile(apk) as z:
    names = [entry.filename for entry in z.infolist() if entry.file_size]
abi = {n.split('/')[1] for n in names if n.startswith('lib/') and n.count('/') == 2 and n.endswith('.so')}
if abi != {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}:
    raise SystemExit(f'Wrong Universal ABI set: {sorted(abi)}')
if any(n.endswith(('libtor.so', 'libgojni.so')) or 'TorService' in n or 'IPtProxy' in n for n in names):
    raise SystemExit('Removed Tor components were found in the APK')
size = apk.stat().st_size
if not 0 < size < 64 * 1024 * 1024:
    raise SystemExit(f'Universal APK size is outside the official budget: {size}')
with apk.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
if os.getenv('GITHUB_SHA', sha) != sha:
    raise SystemExit('Wrong source commit')
result = {'commit': sha, 'version': expected_version, 'version_code': int(expected_code),
          'apk_sha256': digest, 'bytes': size, 'signing_certificate_sha256': certificate,
          'abis': sorted(abi), 'scope': 'signing-validation-only'}
report.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
PY
