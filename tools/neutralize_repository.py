#!/usr/bin/env python3
"""One-time, guarded terminology rewrite; preserves package IDs and commit counts."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def git(*args, cwd=None, data=None):
    result = subprocess.run(['git', *args], cwd=cwd, input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        detail = result.stderr.decode('utf-8', 'replace')
        token = os.environ.get('GH_TOKEN')
        if token:
            encoded = base64.b64encode(('x-access-token:' + token).encode()).decode()
            detail = detail.replace(token, '[redacted]').replace(encoded, '[redacted]')
        raise RuntimeError('Git command failed: ' + detail)
    return result.stdout


def make_transform(package):
    # The migration's vocabulary comes from the preserved original default. It is
    # deliberately not duplicated as labels, identifiers, or test package names.
    parts = package.split(b'.')
    first, second = parts[-3:-1]
    pair = re.compile(re.escape(first) + rb'[-_ ]*' + re.escape(second), re.I)
    alias = re.compile(re.escape(first[:4] + second), re.I)
    singles = [(re.compile(re.escape(first), re.I), b'target'),
               (re.compile(re.escape(second), re.I), b'app')]
    protected = b'\x00PRESERVED_ANDROID_PACKAGE\x00'

    def replacement(match):
        value = match.group()
        if b'-' in value: return b'target-app'
        if b'_' in value: return b'TARGET_APP' if value.isupper() else b'target_app'
        if b' ' in value: return b'Target app' if value[:1].isupper() else b'target app'
        return b'TargetApp' if value[:1].isupper() else b'targetapp'

    def transform(value):
        value = value.replace(package, protected)
        value = pair.sub(replacement, value)
        value = alias.sub(replacement, value)
        for pattern, new in singles:
            value = pattern.sub(lambda m: new.capitalize() if m.group()[:1].isupper() else new, value)
        return value.replace(protected, package)
    return transform, rb'|'.join(re.escape(x) for x in (first, second, first[:4] + second))


def refs(repo):
    return dict(line.split(b' ', 1) for line in git('for-each-ref', '--format=%(refname) %(objectname)',
        'refs/heads', 'refs/tags', cwd=repo).splitlines())


def verify_objects(repo, transform):
    objects = git('rev-list', '--objects', '--all', cwd=repo).splitlines()
    process = subprocess.Popen(['git', 'cat-file', '--batch'], cwd=repo,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    checked = 0
    try:
        for entry in objects:
            oid, _, path = entry.partition(b' ')
            if transform(path) != path: raise RuntimeError('Historical path still contains old terminology')
            process.stdin.write(oid + b'\n'); process.stdin.flush()
            header = process.stdout.readline().split()
            data = process.stdout.read(int(header[2])); process.stdout.read(1)
            # Binary platform stubs are dependencies, not application branding.
            if header[1] == b'blob' and b'\0' not in data:
                if transform(data) != data: raise RuntimeError('Historical text was not fully rewritten: ' + oid.decode())
                checked += 1
            elif header[1] in (b'commit', b'tag'):
                message = data.split(b'\n\n', 1)[-1]
                if transform(message) != message: raise RuntimeError('Historical message was not fully rewritten')
    finally:
        process.stdin.close(); process.wait()
    git('fsck', '--full', '--no-reflogs', cwd=repo)
    return checked


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True)
    parser.add_argument('--expected-head', required=True)
    parser.add_argument('--expected-parent', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    # A workflow token stays in process environment, never in a URL or log.
    if os.environ.get('GH_TOKEN'):
        credential = base64.b64encode(('x-access-token:' + os.environ['GH_TOKEN']).encode()).decode()
        os.environ.update(GIT_CONFIG_COUNT='1',
            GIT_CONFIG_KEY_0='http.https://github.com/.extraheader',
            GIT_CONFIG_VALUE_0='AUTHORIZATION: basic ' + credential)
    with tempfile.TemporaryDirectory(prefix='repository-rewrite-') as temp:
        repo = Path(temp) / 'repository.git'
        git('init', '--bare', str(repo))
        git('fetch', '--no-write-fetch-head', args.source,
            '+refs/heads/*:refs/heads/*', '+refs/tags/*:refs/tags/*', cwd=repo)
        before = refs(repo)
        master = b'refs/heads/master'
        original_tree = git('rev-parse', 'master^{tree}', cwd=repo)
        if before[master].decode() != args.expected_head:
            raise RuntimeError('Master changed; refusing to rewrite a different revision')
        if git('rev-parse', 'master^', cwd=repo).decode().strip() != args.expected_parent:
            raise RuntimeError('One-time parent guard no longer matches; refusing to repeat rewrite')
        target = git('show', 'master:manager/src/main/java/moe/shizuku/manager/control/PortraitTarget.kt', cwd=repo)
        package = re.search(rb'const val DEFAULT_PACKAGE = "([^"]+)"', target).group(1)
        transform, search = make_transform(package)
        affected = set(git('log', '--all', '--format=%H', '-i', '-G', search.decode(), cwd=repo).splitlines())
        order = git('rev-list', '--all', '--reverse', '--topo-order', cwd=repo).splitlines()
        first = next(oid for oid in order if oid in affected)
        boundary = git('rev-parse', first.decode() + '^', cwd=repo).decode().strip()
        counts = {ref: git('rev-list', '--count', ref.decode(), cwd=repo).strip() for ref in before}
        # Keep imported upstream history/signatures outside the rewrite range.
        import git_filter_repo
        options = git_filter_repo.FilteringOptions.parse_args(['--force', '--prune-empty', 'never',
            '--prune-degenerate', 'never', '--preserve-commit-hashes', '--replace-refs', 'delete-no-add',
            '--refs', *[ref.decode() for ref in before], '^' + boundary])
        def blob_callback(blob, metadata):
            if b'\0' not in blob.data: blob.data = transform(blob.data)
        previous = os.getcwd()
        os.chdir(repo)
        try:
            git_filter_repo.RepoFilter(options, blob_callback=blob_callback,
                message_callback=transform, filename_callback=transform, refname_callback=transform).run()
        finally:
            os.chdir(previous)
        destinations = {ref: transform(ref) for ref in before}
        if len(set(destinations.values())) != len(before): raise RuntimeError('Branch rename collision')
        # A range-limited filter intentionally retains old names. Remove only
        # those local aliases whose original tip still matches the snapshot.
        for old, new in destinations.items():
            if old != new:
                git('update-ref', '-d', old.decode(), before[old].decode(), cwd=repo)
        after = refs(repo)
        if set(after) != set(destinations.values()): raise RuntimeError('Unexpected ref set after rewrite')
        for ref, count in counts.items():
            if git('rev-list', '--count', destinations[ref].decode(), cwd=repo).strip() != count:
                raise RuntimeError('Commit count changed; refusing to break APK version ordering')
        checked = verify_objects(repo, transform)
        # The current application source must already be neutral before this job.
        if git('rev-parse', 'master^{tree}', cwd=repo) != original_tree:
            raise RuntimeError('Current master tree changed during historical rewrite')
        changed = {ref: destinations[ref] for ref in before
                   if destinations[ref] != ref or after[destinations[ref]] != before[ref]}
        report = {'changed_refs': len(changed), 'renamed_branches': sum(a != b for a, b in changed.items()),
                  'checked_text_blobs': checked, 'old_master': args.expected_head,
                  'new_master': after[master].decode(), 'applied': args.apply,
                  'retained_limits': 'GitHub pull refs, old PR diffs, cached commit pages and external clones are not erased.'}
        if args.apply:
            current = dict(line.split(b'\t')[::-1] for line in git('ls-remote', '--heads', '--tags', args.source, cwd=repo).splitlines()
                           if not line.split(b'\t')[1].endswith(b'^{}'))
            if current != before: raise RuntimeError('Published refs moved; no rewrite pushed')
            push = ['push', '--atomic']
            updates = []
            for old, new in changed.items():
                push.append('--force-with-lease=' + old.decode() + ':' + before[old].decode())
                if old != new:
                    push.append('--force-with-lease=' + new.decode() + ':')
                    updates.append(':' + old.decode())
                updates.append(after[new].decode() + ':' + new.decode())
            git(*push, args.source, *updates, cwd=repo)
        print(json.dumps(report, indent=2))
        Path(os.environ.get('GITHUB_STEP_SUMMARY', str(Path.cwd() / 'rewrite-result.md'))).write_text(
            'Repository terminology rewrite\n\n```json\n' + json.dumps(report, indent=2) + '\n```\n')


if __name__ == '__main__':
    main()
