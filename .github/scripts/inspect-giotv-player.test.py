import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('giotv_inspector', Path(__file__).with_name('inspect-giotv-player.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ContractTests(unittest.TestCase):
    def test_window_reports_lifetime_only(self):
        data = module.window('__hdnea__=st=10~exp=100~hmac=synthetic-secret', 50)
        self.assertEqual(50, data['expiresInSeconds'])
        self.assertEqual(-40, data['startsInSeconds'])
        self.assertNotIn('synthetic-secret', str(data))
    def test_unknown_expiry_is_not_called_expired(self):
        data = module.window('__hdnea__=opaque-synthetic', 50)
        self.assertIsNone(data['expiresInSeconds'])
        self.assertFalse(data['expired'])
    def test_roles_are_recognised_after_a_rename(self):
        self.assertEqual('primary', module.classify('lp_cookie'))
        self.assertEqual('star', module.classify('sportsToken'))
        self.assertEqual('fallback', module.classify('cookieFallback'))
        self.assertEqual('catalogue', module.classify('channelsUrl'))
        self.assertIsNone(module.classify('viewerCount'))
    def test_endpoints_are_static_absolute_site_or_jio_only(self):
        code = '''
        const CHANNELS_URL = "https://cdn.jio.com/list.json";
        const cookiePrimary = "/data/cookies.json";
        const bad = "https://www.googletagmanager.com/gtag.js";
        const dynamic = `${base}/channels.json`;
        Player.cookie = "https://relay.example.net/c.json";
        '''
        found = module.endpoints(code, module.SITE + '/pind?id=816')
        self.assertEqual('https://cdn.jio.com/list.json', found['catalogue']['url'])
        self.assertFalse(found['catalogue']['sameSite'])
        self.assertEqual(module.SITE + '/data/cookies.json', found['primary']['url'])
        self.assertTrue(found['primary']['sameSite'])
        self.assertNotIn('googletagmanager', str(found))
        self.assertNotIn('channels.json', str(found))
    def test_a_challenge_or_html_page_is_not_a_metadata_document(self):
        self.assertFalse(module.metadata_valid('primary', 403, '[]'))
        self.assertFalse(module.metadata_valid('primary', 200, '<html>Brave Shields</html>'))
        self.assertFalse(module.metadata_valid('catalogue', 200, '{"error":"denied"}'))
        self.assertTrue(module.metadata_valid('primary', 200, '[]'))
        self.assertTrue(module.metadata_valid('catalogue', 200, '[{"id":"816"}]'))
        self.assertTrue(module.metadata_valid('catalogue', 200, '{"id":"816"}'))
    def test_catalogue_reports_the_fields_the_resolver_needs(self):
        report = module.catalogue_report([{'id': 816, 'url': 'https://jiotvmblive.cdn.jio.com/bpk-tv/a/x.mpd',
                                           'url1': 'https://example.com/b.mpd', 'keys': [{'keyId': 'a', 'key': 'b'}]},
                                          {'id': '872', 'keyId': 'a', 'key': 'b'}])
        self.assertEqual(2, report['channelCount'])
        self.assertEqual('816', report['sample'][0]['id'])
        self.assertEqual('int', report['sample'][0]['idType'])
        self.assertTrue(report['sample'][0]['hasKeysArray'])
        self.assertTrue(report['sample'][1]['hasLegacyKey'])

if __name__ == '__main__':
    unittest.main()
