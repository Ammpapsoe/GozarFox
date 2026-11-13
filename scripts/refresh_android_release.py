import hashlib
import json
import os
import pathlib
import time
import urllib.error
import urllib.parse
import urllib.request

repo = os.environ['GITHUB_REPOSITORY']
token = os.environ['GH_TOKEN']
sha = os.environ['GITHUB_SHA']
run_id = os.environ['GITHUB_RUN_ID']
tag = 'v1.10.1-desktop'
root = pathlib.Path('out')
notes = pathlib.Path(os.environ['RELEASE_NOTES']).read_text()
base = f'https://api.github.com/repos/{repo}'
prefix = f'_android_refresh_{run_id}_'
headers = {
    'Authorization': 'Bearer ' + token,
    'Accept': 'application/vnd.github+json',
    'User-Agent': 'Gozar-Android-refresh',
    'X-GitHub-Api-Version': '2022-11-28',
}


class Redirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, response_headers, newurl):
        redirected = super().redirect_request(request, fp, code, message, response_headers, newurl)
        if redirected and urllib.parse.urlsplit(newurl).netloc not in ('api.github.com', 'uploads.github.com'):
            redirected.remove_header('Authorization')
        return redirected


opener = urllib.request.build_opener(Redirect)


def request(url, method='GET', body=None, content_type=None, accept=None, retry=True):
    request_headers = dict(headers)
    if content_type:
        request_headers['Content-Type'] = content_type
    if accept:
        request_headers['Accept'] = accept
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        request_headers['Content-Type'] = 'application/json'
    attempts = 4 if retry else 1
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(url, data=body, headers=request_headers, method=method)
            with opener.open(req, timeout=120) as response:
                data = response.read()
                if not data:
                    return None
                if 'json' in response.headers.get('Content-Type', ''):
                    return json.loads(data)
                return data
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt + 1 == attempts:
                raise RuntimeError(f'GitHub request failed with HTTP {error.code}') from None
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt + 1 == attempts:
                raise RuntimeError('GitHub request failed') from error
        time.sleep(2 ** attempt)


def release():
    return request(base + '/releases/tags/' + tag)


def assets_by_id():
    return {asset['id']: asset for asset in release()['assets']}


def rename(asset_id, name):
    return request(base + f'/releases/assets/{asset_id}', 'PATCH', {'name': name})


def delete(asset_id):
    return request(base + f'/releases/assets/{asset_id}', 'DELETE')


def reference():
    return request(base + '/git/ref/tags/' + tag)['object']


def set_reference(value):
    return request(base + '/git/refs/tags/' + tag, 'PATCH', {'sha': value, 'force': True})


def asset_digest(asset):
    known = asset.get('digest')
    if known:
        return known
    data = request(asset['url'], accept='application/octet-stream')
    if not isinstance(data, bytes):
        raise RuntimeError('Could not read an uploaded asset')
    return 'sha256:' + hashlib.sha256(data).hexdigest()


if repo != 'bardia049b-stack/Gozar1' or not sha or not run_id:
    raise SystemExit('The release destination is not the expected repository')
files = [root / f'Gozar_1.10.1_{abi}.apk' for abi in ('universal', 'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')]
files += [pathlib.Path(str(path) + '.sig') for path in files]
if any(not path.is_file() or not path.stat().st_size for path in files):
    raise SystemExit('A signed Android release file is missing')
if not notes.startswith('Graphics improvements.\n'):
    raise SystemExit('Release notes have an unexpected caption')
initial = release()
original_assets = {asset['name']: asset for asset in initial['assets']}
expected_names = {path.name for path in files}
if not expected_names.issubset(original_assets) or any(name.startswith('_android_refresh_') for name in original_assets):
    raise SystemExit('The existing Android assets do not match the expected release')
stable_assets = {name: asset['id'] for name, asset in original_assets.items() if name not in expected_names}
old_ids = {name: original_assets[name]['id'] for name in expected_names}
old_ref = reference()
if old_ref['type'] != 'commit':
    raise SystemExit('The existing release tag is not a lightweight tag')
old_sha = old_ref['sha']
if old_sha != '913ed4a690b79d7c92317db08f00ecd9ee0218ad':
    raise SystemExit('The release tag was already updated or changed; refusing to overwrite it')
old_body = initial.get('body') or ''
release_id = initial['id']
upload_base = initial['upload_url'].split('{', 1)[0]
if urllib.parse.urlsplit(upload_base).netloc != 'uploads.github.com':
    raise SystemExit('Unexpected release upload host')
staged_ids = {}

try:
    for path in files:
        upload_name = prefix + path.name
        url = upload_base + '?' + urllib.parse.urlencode({'name': upload_name})
        data = path.read_bytes()
        uploaded = request(url, 'POST', data, 'application/octet-stream', retry=False)
        staged_ids[path.name] = uploaded['id']
        if uploaded['size'] != len(data) or uploaded['state'] != 'uploaded':
            raise RuntimeError('An Android asset upload was incomplete')
        expected = 'sha256:' + hashlib.sha256(data).hexdigest()
        if asset_digest(uploaded) != expected:
            raise RuntimeError('An uploaded Android asset did not match its local file')
    print('Replacement APKs and signatures staged and verified.', flush=True)
    if reference()['sha'] != old_sha:
        raise RuntimeError('The release tag changed during the update')
    current = {item['name']: item for item in release()['assets']}
    if any(current.get(name, {}).get('id') != asset_id for name, asset_id in old_ids.items()):
        raise RuntimeError('The release assets changed during the update')
    for name, asset_id in old_ids.items():
        rename(asset_id, prefix + 'backup_' + name)
    for name, asset_id in staged_ids.items():
        rename(asset_id, name)
    set_reference(sha)
    request(base + f'/releases/{release_id}', 'PATCH', {'body': notes})
    updated = release()
    current = {asset['name']: asset for asset in updated['assets']}
    if any(current.get(name, {}).get('id') != asset_id for name, asset_id in staged_ids.items()):
        raise RuntimeError('An updated Android asset is missing')
    if any(current.get(name, {}).get('id') != asset_id for name, asset_id in stable_assets.items()):
        raise RuntimeError('An unrelated release asset changed')
    if reference()['sha'] != sha or updated.get('body', '').strip() != notes.strip():
        raise RuntimeError('The release tag or notes did not update')
except Exception:
    try:
        current = assets_by_id()
        for name, asset_id in staged_ids.items():
            item = current.get(asset_id)
            if item and item['name'] == name:
                rename(asset_id, prefix + name)
        current = assets_by_id()
        for name, asset_id in old_ids.items():
            item = current.get(asset_id)
            if item and item['name'] != name:
                rename(asset_id, name)
        if old_sha != sha and reference()['sha'] == sha:
            set_reference(old_sha)
        if old_body != notes and (release().get('body') or '') == notes:
            request(base + f'/releases/{release_id}', 'PATCH', {'body': old_body})
        current = assets_by_id()
        for asset_id, item in current.items():
            if item['name'].startswith(prefix) and asset_id not in old_ids.values():
                delete(asset_id)
        print('The existing release was restored after an update error.', flush=True)
    except Exception:
        print('Automatic rollback was incomplete; the release needs manual attention.', flush=True)
    raise

for asset_id in old_ids.values():
    delete(asset_id)
final = release()
if {asset['name'] for asset in final['assets']} != set(original_assets):
    raise SystemExit('The release was updated but its asset list needs attention')
print('Existing release updated: five signed APKs, matching signatures, source tag and download badges.', flush=True)
