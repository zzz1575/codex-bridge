import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch as mock_patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import privacy
import sync_source
import finalize_pr


class MaintenanceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def repo(self, name):
        p = self.root / name
        p.mkdir()
        privacy.git(p, 'init', '-q', '-b', 'main')
        privacy.git(p, 'config', 'user.name', 'Fixture')
        privacy.git(p, 'config', 'user.email', 'fixture@example.com')
        return p

    def commit(self, p, name='fixture'):
        privacy.git(p, 'add', '.')
        privacy.git(p, 'commit', '-qm', name)
        return privacy.git(p, 'rev-parse', 'HEAD').decode().strip()

    def test_no_secret_values_in_audit_error(self):
        value = b'private-value-for-unit-fixture'
        report = privacy.inspect(value, 'config.txt', [value])
        self.assertTrue(report)
        self.assertNotIn(value.decode(), json.dumps(list(report)))

    def test_secret_formats(self):
        data = b'gh' + b'p_' + b'A' * 30
        self.assertIn(('access-token', 'x'), privacy.inspect(data, 'x'))
        key = b'-----BEGIN ' + b'OPENSSH PRIVATE KEY-----'
        self.assertIn(('private-key', 'x'), privacy.inspect(key, 'x'))

    def test_documentation_addresses_and_email_are_allowed(self):
        data = b'https://example.com/ https://192.0.2.10/ fixture@example.com https://127.0.0.1/'
        self.assertFalse(privacy.inspect(data, 'fixture.txt'))

    def test_real_server_address_is_flagged(self):
        address = b'https://' + b'10.20.30.40/'
        self.assertIn(('server-address-review', 'x'), privacy.inspect(address, 'x'))

    def test_known_home_path_is_flagged(self):
        data = b'C:' + b'/Users/' + b'private-person/project'
        self.assertIn(('home-path-review', 'x'), privacy.inspect(data, 'x'))

    def test_private_file_even_when_empty(self):
        repo = self.repo('private')
        (repo / '.private').mkdir()
        (repo / '.private/access.json').write_text('{}')
        self.commit(repo)
        self.assertTrue(any(f['kind'] == 'private-or-generated-file' for f in privacy.audit(repo)))

    def test_deleted_historical_secret_is_still_rejected(self):
        repo = self.repo('history')
        (repo / 'normal.txt').write_text('safe')
        base = self.commit(repo)
        (repo / 'normal.txt').write_text('gh' + 'p_' + 'B' * 30)
        self.commit(repo, 'transient fixture')
        (repo / 'normal.txt').write_text('safe again')
        self.commit(repo, 'remove fixture')
        self.assertFalse(privacy.audit(repo))
        self.assertTrue(privacy.audit(repo, base))

    def test_three_way_preserves_fork_only_files(self):
        src = self.repo('source')
        (src / 'core.txt').write_text('one\ntwo\nthree\n')
        old = self.commit(src)
        dest = self.root / 'publication'
        subprocess.check_call(['git', 'clone', '-q', str(src), str(dest)])
        privacy.git(dest, 'config', 'user.name', 'Fixture')
        privacy.git(dest, 'config', 'user.email', 'fixture@example.com')
        (dest / 'android.txt').write_text('fork-only')
        self.commit(dest)
        (src / 'core.txt').write_text('one\nchanged\nthree\n')
        new = self.commit(src)
        sync_source.apply(dest, sync_source.patch(src, old, new))
        self.assertEqual('fork-only', (dest / 'android.txt').read_text())
        self.assertIn('changed', (dest / 'core.txt').read_text())
        self.assertEqual(new, privacy.git(src, 'rev-parse', 'HEAD').decode().strip())

    def test_conflict_never_overwrites_source(self):
        src = self.repo('source')
        (src / 'core.txt').write_text('old\n')
        old = self.commit(src)
        dest = self.root / 'publication'
        subprocess.check_call(['git', 'clone', '-q', str(src), str(dest)])
        privacy.git(dest, 'config', 'user.name', 'Fixture')
        privacy.git(dest, 'config', 'user.email', 'fixture@example.com')
        (dest / 'core.txt').write_text('fork\n')
        self.commit(dest)
        (src / 'core.txt').write_text('source\n')
        new = self.commit(src)
        with self.assertRaises(RuntimeError):
            sync_source.apply(dest, sync_source.patch(src, old, new))
        self.assertEqual('source\n', (src / 'core.txt').read_text())
        self.assertEqual(new, privacy.git(src, 'rev-parse', 'HEAD').decode().strip())

    def test_android_patch_is_limited_to_component_prefix(self):
        src = self.repo('android-source')
        (src / 'README.md').write_text('old client\n')
        old = self.commit(src)
        dest = self.repo('fork')
        (dest / 'android').mkdir()
        (dest / 'android/README.md').write_text('old client\n')
        (dest / 'README.md').write_text('Bridge root\n')
        self.commit(dest)
        (src / 'README.md').write_text('new client\n')
        new = self.commit(src)
        sync_source.apply(dest, sync_source.patch(src, old, new), 'android')
        self.assertEqual('Bridge root\n', (dest / 'README.md').read_text())
        self.assertEqual('new client\n', (dest / 'android/README.md').read_text())

    def test_rewritten_source_history_is_rejected(self):
        src = self.repo('source')
        (src / 'core.txt').write_text('old')
        old = self.commit(src)
        (src / 'core.txt').write_text('new')
        new = self.commit(src)
        with self.assertRaises(RuntimeError):
            sync_source.patch(src, new, old)

    def test_source_cannot_be_the_scratch_clone(self):
        repo = self.repo('same')
        with self.assertRaises(RuntimeError):
            sync_source.validate_config({'publication_repo': str(repo), 'components': {'bridge': {'repo': str(repo)}}})

    def test_unknown_scratch_clone_is_rejected(self):
        repo = self.repo('unknown')
        with self.assertRaises(RuntimeError):
            sync_source.validate_config({'publication_repo': str(repo), 'components': {}})

    def test_bundle_name_cannot_escape_incoming_directory(self):
        for filename in ['../outside.bundle', '/tmp/outside.bundle', 'android-x.bundle']:
            with self.assertRaises(RuntimeError):
                sync_source.receive({}, filename)

    def test_checkpoint_advances_only_after_merge(self):
        src = self.repo('source')
        (src / 'x').write_text('safe')
        oid = self.commit(src)
        dest = self.repo('scratch')
        (dest / '.git/source-sync-owned').write_text('dedicated-publication-scratch')
        state = self.root / 'state'
        sync_source.atomic_json(state / 'state.json', {'bridge': {'synced_source': 'older', 'pending': {'source': oid, 'pr': 7}}})
        config = {'publication_repo': str(dest), 'state_dir': str(state), 'repository': 'example/fixture', 'components': {'bridge': {'repo': str(src)}}}
        with mock_patch.object(sync_source, 'gh', return_value=b'{"state":"MERGED"}'):
            self.assertEqual('up-to-date', sync_source.sync(config, 'bridge')['status'])
        self.assertEqual(oid, sync_source.load(state / 'state.json', {})['bridge']['synced_source'])

    def test_unmerged_closed_pr_stops_sync(self):
        src = self.repo('source')
        dest = self.repo('scratch')
        (dest / '.git/source-sync-owned').write_text('dedicated-publication-scratch')
        state = self.root / 'state'
        sync_source.atomic_json(state / 'state.json', {'bridge': {'synced_source': 'older', 'pending': {'source': 'pending', 'pr': 7}}})
        config = {'publication_repo': str(dest), 'state_dir': str(state), 'repository': 'example/fixture', 'components': {'bridge': {'repo': str(src)}}}
        with mock_patch.object(sync_source, 'gh', return_value=b'{"state":"CLOSED"}'):
            with self.assertRaises(RuntimeError):
                sync_source.sync(config, 'bridge')
        self.assertEqual('older', sync_source.load(state / 'state.json', {})['bridge']['synced_source'])

    def test_finalizer_rejects_unmanaged_branch(self):
        with self.assertRaises(RuntimeError):
            finalize_pr.finalize('example/fixture', 'feature/unrelated', 'tested')

    def test_finalizer_cannot_merge_stale_checks(self):
        replies = [b'[{"number":1}]', b'{"headRefOid":"newer","mergeStateStatus":"CLEAN","autoMergeRequest":{}}']
        with mock_patch.object(finalize_pr, 'gh', side_effect=replies) as gh:
            result = finalize_pr.finalize('example/fixture', 'sync/bridge', 'older')
            self.assertEqual('stale-checks', result['status'])
            self.assertEqual(2, gh.call_count)

    def test_finalizer_honors_disabled_auto_merge(self):
        replies = [b'[{"number":1}]', b'{"headRefOid":"tested","mergeStateStatus":"CLEAN","autoMergeRequest":null}']
        with mock_patch.object(finalize_pr, 'gh', side_effect=replies) as gh:
            self.assertEqual('auto-merge-not-requested', finalize_pr.finalize('example/fixture', 'sync/android', 'tested')['status'])
            self.assertEqual(2, gh.call_count)

    def test_finalizer_uses_exact_head_without_admin_bypass(self):
        replies = [b'[{"number":1}]', b'{"headRefOid":"tested","mergeStateStatus":"CLEAN","autoMergeRequest":{"enabled":true}}', b'']
        with mock_patch.object(finalize_pr, 'gh', side_effect=replies) as gh, mock_patch.object(finalize_pr, 'refresh_pending'):
            self.assertEqual('merged', finalize_pr.finalize('example/fixture', 'sync/bridge', 'tested')['status'])
            arguments = gh.call_args.args
            self.assertIn('--match-head-commit', arguments)
            self.assertNotIn('--admin', arguments)

    def test_finalizer_refreshes_behind_branch_instead_of_merging(self):
        replies = [b'[{"number":1}]', b'{"headRefOid":"tested","mergeStateStatus":"BEHIND","autoMergeRequest":{"enabled":true}}', b'', b'']
        with mock_patch.object(finalize_pr, 'gh', side_effect=replies) as gh:
            self.assertEqual('retesting-updated-branch', finalize_pr.finalize('example/fixture', 'sync/bridge', 'tested')['status'])
            self.assertEqual('update-branch', gh.call_args_list[2].args[1])
            self.assertEqual('workflow', gh.call_args_list[3].args[0])

    def test_reconcile_polls_pending_pr_once(self):
        repo = self.repo('scratch')
        (repo / '.git/source-sync-owned').write_text('dedicated-publication-scratch')
        state = self.root / 'state'
        sync_source.atomic_json(state / 'state.json', {'bridge': {'synced_source': 'old', 'pending': {'source': 'new', 'pr': 7}}})
        config = {'publication_repo': str(repo), 'state_dir': str(state), 'components': {'bridge': {'repo': str(self.root / 'source')}}}
        with mock_patch.object(sync_source, 'sync', return_value={'component': 'bridge', 'status': 'waiting-for-checks'}) as sync:
            sync_source.drain(config)
            self.assertEqual(1, sync.call_count)

    def test_reconcile_does_not_retry_blocked_publication(self):
        repo = self.repo('scratch')
        (repo / '.git/source-sync-owned').write_text('dedicated-publication-scratch')
        state = self.root / 'state'
        sync_source.atomic_json(state / 'state.json', {'bridge': {'synced_source': 'old', 'pending': {'source': 'new', 'pr': 7}}})
        sync_source.atomic_json(state / 'bridge-status.json', {'status': 'blocked'})
        config = {'publication_repo': str(repo), 'state_dir': str(state), 'components': {'bridge': {'repo': str(self.root / 'source')}}}
        with mock_patch.object(sync_source, 'sync') as sync:
            sync_source.drain(config)
            sync.assert_not_called()


if __name__ == '__main__':
    unittest.main()
