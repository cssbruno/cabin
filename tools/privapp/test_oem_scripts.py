import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from build_oem_bundle import build


class OemScriptsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='cabin-script-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.system = self.root / 'mock-system'
        (self.system / 'priv-app').mkdir(parents=True)
        (self.system / 'etc/permissions').mkdir(parents=True)
        self.apk = self.root / 'normal.apk'
        self.apk.write_bytes(b'Android-accepted APK fixture')
        bundle = self.root / 'bundle.zip'
        with patch('build_oem_bundle.inspect_apk', return_value=1005):
            build(self.apk, bundle, self.root)
        self.extracted = self.root / 'bundle'
        with zipfile.ZipFile(bundle) as z:
            z.extractall(self.extracted)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        commands = {'id': 'echo 0', 'pm': 'printf "package:%s\\n" "$CABIN_TEST_APK"', 'chown': 'exit 0', 'restorecon': 'exit 0'}
        for name, body in commands.items():
            self.command(name, body)
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'], CABIN_TEST_APK=str(self.apk))
        for name in ['install.sh', 'rollback.sh']:
            p = self.extracted / name
            s = p.read_text().replace('$BASE/system', '$BASE/PAYLOAD')
            s = s.replace('/system', str(self.system)).replace('$BASE/PAYLOAD', '$BASE/system')
            p.write_text(s)

    def command(self, name, body):
        p = self.bin / name
        p.write_text('#!/bin/sh\n' + body + '\n')
        p.chmod(0o755)

    def run_script(self, name):
        return subprocess.run(['sh', str(self.extracted / name)], env=self.env, capture_output=True, text=True)

    def test_install_and_rollback_leave_factory_and_normal_apk_intact(self):
        factory = self.system / 'priv-app/Factory'
        factory.mkdir()
        sentinel = factory / 'factory.apk'
        sentinel.write_bytes(b'factory')
        result = self.run_script('install.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.system / 'priv-app/Cabin/Cabin.apk').read_bytes(), self.apk.read_bytes())
        result = self.run_script('rollback.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.system / 'priv-app/Cabin').exists())
        self.assertEqual(sentinel.read_bytes(), b'factory')
        self.assertTrue(self.apk.exists())

    def test_existing_destination_is_never_replaced(self):
        target = self.system / 'priv-app/Cabin'
        target.mkdir()
        self.assertNotEqual(self.run_script('install.sh').returncode, 0)
        self.assertTrue(target.exists())

    def test_mismatched_normal_install_rejected(self):
        self.apk.write_bytes(b'other build')
        self.assertNotEqual(self.run_script('install.sh').returncode, 0)
        self.assertFalse((self.system / 'priv-app/Cabin').exists())

    def test_label_failure_cleans_new_files(self):
        self.command('restorecon', 'exit 1')
        self.assertNotEqual(self.run_script('install.sh').returncode, 0)
        self.assertFalse((self.system / 'priv-app/Cabin').exists())
        self.assertFalse((self.system / 'etc/permissions/privapp-permissions-cabin.xml').exists())

    def test_rollback_does_not_remove_modified_files(self):
        self.assertEqual(self.run_script('install.sh').returncode, 0)
        apk = self.system / 'priv-app/Cabin/Cabin.apk'
        apk.write_bytes(b'updated')
        self.assertNotEqual(self.run_script('rollback.sh').returncode, 0)
        self.assertEqual(apk.read_bytes(), b'updated')

    def test_root_required(self):
        self.command('id', 'echo 2000')
        self.assertNotEqual(self.run_script('install.sh').returncode, 0)
        self.assertFalse((self.system / 'priv-app/Cabin').exists())
