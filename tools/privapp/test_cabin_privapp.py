import contextlib
import io
from pathlib import Path
import tempfile
import os
import subprocess
import unittest
from unittest.mock import patch
import zipfile
import xml.etree.ElementTree as ET
import cabin_privapp as installer


class InstallerTest(unittest.TestCase):
    def test_archive_contains_cabin_and_exact_widget_and_carlink_permissions(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'signed.apk'
            apk.write_bytes(b'fixture')
            output = Path(folder) / 'module.zip'
            installer.package(apk, output, 1005)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(set(archive.namelist()), {'module.prop', 'customize.sh',
                    'system/priv-app/Cabin/Cabin.apk', 'system/etc/permissions/privapp-permissions-cabin.xml'})
                self.assertEqual(archive.read('system/priv-app/Cabin/Cabin.apk'), b'fixture')
                root = ET.fromstring(archive.read('system/etc/permissions/privapp-permissions-cabin.xml'))
                allowlist = root.find('privapp-permissions')
                self.assertEqual(allowlist.attrib['package'], 'zeno.carlink')
                self.assertEqual({item.attrib['name'] for item in allowlist}, {
                    'android.permission.BIND_APPWIDGET',
                    'android.permission.LOCAL_MAC_ADDRESS', 'android.permission.TETHER_PRIVILEGED',
                    'android.permission.OVERRIDE_WIFI_CONFIG', 'android.permission.INSTALL_PACKAGES'})
                manifest = ET.parse(Path(__file__).resolve().parents[2] / 'app/src/main/AndroidManifest.xml')
                declared = {item.attrib['{http://schemas.android.com/apk/res/android}name']
                            for item in manifest.findall('uses-permission')}
                self.assertTrue({item.attrib['name'] for item in allowlist} <= declared)
            with self.assertRaises(FileExistsError):
                installer.package(apk, output, 1005)

    def test_permission_audit_does_not_confuse_requests_with_grants(self):
        dump = """requested permissions:
          android.permission.FORCE_STOP_PACKAGES
          android.permission.INTERNET
        install permissions:
          android.permission.INTERNET: granted=true
          android.permission.LOCAL_MAC_ADDRESS: granted=false
        """
        report = installer.permission_report(dump)
        self.assertIn('FORCE_STOP_PACKAGES: not reported', report)
        self.assertIn('INTERNET: granted', report)
        self.assertIn('LOCAL_MAC_ADDRESS: denied', report)
        self.assertIn('BLUETOOTH_CONNECT: not reported', report)
        self.assertIn('RECORD_AUDIO: mixed user states', installer.permission_report(
            'android.permission.RECORD_AUDIO: granted=true\nandroid.permission.RECORD_AUDIO: granted=false'))

    def test_module_setup_requires_exact_installed_apk(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / 'system/priv-app/Cabin/Cabin.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'new signed build')
            installed = root / 'installed.apk'
            installed.write_bytes(b'old signed build')
            env = dict(os.environ, BOOTMODE='true', API='29', MODPATH=folder, CABIN_TEST_APK=str(installed))
            helpers = """pm() { printf 'package:%s\n' "$CABIN_TEST_APK"; }
abort() { echo "$1"; exit 1; }
ui_print() { :; }
set_perm_recursive() { :; }
"""
            result = subprocess.run(['sh', '-c', helpers + installer.CUSTOMIZE], env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            installed.write_bytes(apk.read_bytes())
            result = subprocess.run(['sh', '-c', helpers + installer.CUSTOMIZE], env=env, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stdout)

    @patch.object(installer, 'sdk_tool', return_value='tool')
    @patch.object(installer, 'run', side_effect=['', "package: name='other.app' versionCode='1'"])
    def test_wrong_package_rejected(self, *_):
        with self.assertRaises(ValueError):
            installer.inspect_apk(Path('apk'), Path('sdk'), False)

    @patch.object(installer, 'sdk_tool', return_value='tool')
    @patch.object(installer, 'run', side_effect=['', "package: name='zeno.carlink' versionCode='1005'\napplication-debuggable"])
    def test_debug_apk_requires_explicit_opt_in(self, *_):
        with self.assertRaises(ValueError):
            installer.inspect_apk(Path('apk'), Path('sdk'), False)

    @patch.object(installer, 'sdk_tool', return_value='tool')
    @patch.object(installer, 'run', side_effect=['', "package: name='zeno.carlink' versionCode='1005'\napplication-debuggable"])
    def test_explicit_development_build_allowed(self, *_):
        self.assertEqual(installer.inspect_apk(Path('apk'), Path('sdk'), True), 1005)

    @patch('sys.argv', ['installer', 'install', '--apk', 'anything.apk'])
    @patch.object(installer, 'run')
    def test_never_selects_a_device_implicitly(self, command):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            installer.main()
        command.assert_not_called()

    @patch('sys.argv', ['installer', 'install', '--serial', 'device', '--apk', 'anything.apk'])
    @patch.object(installer, 'run', return_value='2000')
    def test_root_denial_prevents_any_install(self, command):
        with self.assertRaises(ValueError):
            installer.main()
        self.assertEqual(command.call_count, 1)


if __name__ == '__main__':
    unittest.main()
