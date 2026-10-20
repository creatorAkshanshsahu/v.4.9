import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('legacy_inspector', Path(__file__).with_name('inspect-legacy-apk.py'))
legacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legacy)


class LegacyInspectionTest(unittest.TestCase):
    def test_download_destinations_reject_arbitrary_hosts_and_userinfo(self):
        self.assertTrue(legacy.allowed(legacy.START))
        for url in ['http://drive.google.com/file', 'https://drive.google.com.evil.test/file',
                    'https://token@drive.google.com/file', 'file:///etc/passwd', 'https://localhost/file']:
            self.assertFalse(legacy.allowed(url))

    def test_confirmation_must_belong_to_the_same_public_file(self):
        form = legacy.DownloadForm()
        form.feed('<form id="download-form" action="https://drive.usercontent.google.com/download">'
                  f'<input type="hidden" name="id" value="{legacy.FILE_ID}">'
                  '<input type="hidden" name="confirm" value="t"></form>')
        self.assertTrue(form.target(legacy.START).startswith('https://drive.usercontent.google.com/download?id='))
        form.fields['id'] = 'other-file'
        with self.assertRaises(ValueError):
            form.target(legacy.START)

    def test_arbitrary_string_literals_and_urls_are_redacted(self):
        for value in ['https://private.test/media?token=secret', 'Bearer real-credential', '1' * 32]:
            self.assertNotIn(value, legacy.safe_instruction('const-string', 'v0, ' + value, value))
        self.assertIn('native', legacy.safe_instruction('const-string', 'v0, native', 'native'))
        self.assertIn('[operand redacted]', legacy.safe_instruction('invoke-static', 'https://private.test/?token=secret'))

    def test_numeric_and_array_payloads_cannot_dump_keys(self):
        self.assertIn('literal redacted', legacy.safe_instruction('const-wide', 'v0, 12534875639246986936'))
        self.assertIn('payload omitted', legacy.safe_instruction('fill-array-data-payload', '1 2 3 secret'))
        self.assertEqual('const/4 v0, 1', legacy.safe_instruction('const/4', 'v0, 1'))

    def test_method_and_field_references_remain_readable(self):
        value = 'v0, v1, Lcom/aktv/app/MainActivity;->startEmbed(Lcom/aktv/app/Channel; Z)V'
        self.assertIn('startEmbed', legacy.safe_instruction('invoke-direct', value))


if __name__ == '__main__':
    unittest.main()
