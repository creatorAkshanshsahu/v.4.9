import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('jjtv_inspector', Path(__file__).with_name('inspect-jjtv-player.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class AvailabilityTests(unittest.TestCase):
    def test_window_never_returns_credential_contents(self):
        data = module.window('st=10~exp=100~hmac=synthetic-secret', 50)
        self.assertEqual(50, data['expiresInSeconds'])
        self.assertEqual(-40, data['startsInSeconds'])
        self.assertNotIn('synthetic-secret', str(data))
    def test_unknown_expiry_is_unknown_not_expired(self):
        data = module.window('opaque-synthetic', 50)
        self.assertIsNone(data['expiresInSeconds'])
        self.assertFalse(data['expired'])
    def test_expired_and_future_windows_are_distinct(self):
        self.assertTrue(module.window('exp=40~hmac=synthetic', 50)['expired'])
        self.assertTrue(module.window('st=80~exp=90~hmac=synthetic', 50)['notYetValid'])
    def test_original_url_token_is_read_without_reusing_another_record(self):
        self.assertEqual('exp=90~hmac=synthetic', module.credential('https://cdn.example/a?__hdnea__=exp=90~hmac=synthetic&other=1'))
        self.assertEqual('', module.credential(None))
    def test_failed_lookup_or_200_html_is_not_a_successful_empty_credential_document(self):
        self.assertFalse(module.metadata_valid('COOKIE_PRIMARY', 403, '[]'))
        self.assertFalse(module.metadata_valid('COOKIE_PRIMARY', 200, '<html>challenge</html>'))
        self.assertFalse(module.metadata_valid('COOKIE_STAR_SPORTS', 200, '{"error":"denied"}'))
        self.assertTrue(module.metadata_valid('COOKIE_PRIMARY', 200, '[]'))
        self.assertTrue(module.metadata_valid('COOKIE_STAR_SPORTS', 200, '{"failed_results":[]}'))

    def test_native_comparison_can_expose_the_current_future_start_selection_gap(self):
        values = ['st=1~exp=100~hmac=current', 'st=70~exp=200~hmac=future']
        self.assertEqual(values[1], module.pick_token(values, 50))
        self.assertEqual(values[0], module.pick_token(values, 50, respect_start=True))

if __name__ == '__main__':
    unittest.main()
