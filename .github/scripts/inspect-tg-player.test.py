import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('tg_inspector', Path(__file__).with_name('inspect-tg-player.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class WebsiteServerInventoryTests(unittest.TestCase):
    def test_primary_and_every_backup_are_listed_in_website_order(self):
        config = {
            'urls': ['https://cdn.example/primary.mpd?__hdnea__=synthetic-secret'],
            'cookie': '__hdnea__=another-secret',
            'clearKeys': {'synthetic-kid': 'synthetic-key'},
            'backups': [
                {'label': 'upstream label A', 'urls': ['https://backup-a.example/a.m3u8?sig=secret-a'], 'cookie': ''},
                {'label': 'upstream label B', 'urls': ['https://backup-b.example/b.mpd?sig=secret-b'], 'keyId': 'secret-kid', 'key': 'secret-key'},
            ],
        }
        rows = module.website_server_inventory(config)
        self.assertEqual(['Server 1', 'Server 2', 'Server 3'], [row['server'] for row in rows])
        self.assertEqual(1, rows[0]['urlCount'])
        self.assertEqual('https://backup-a.example/a.m3u8?sig=[redacted]', rows[1]['urls'][0])
        self.assertEqual(1, rows[0]['clearKeyCount'])
        self.assertTrue(rows[2]['keyPairPresent'])
        report = str(rows)
        for secret in ('synthetic-secret', 'another-secret', 'synthetic-kid', 'synthetic-key',
                       'secret-a', 'secret-b', 'secret-kid', 'secret-key'):
            self.assertNotIn(secret, report)

    def test_missing_backup_list_is_a_single_server(self):
        self.assertEqual(['Server 1'], [row['server'] for row in module.website_server_inventory({'urls': []})])

    def test_media_probe_matches_observed_no_referer_browser_request(self):
        headers = module.website_media_headers()
        self.assertEqual(module.SITE, headers['Origin'])
        self.assertNotIn('Referer', headers)


if __name__ == '__main__':
    unittest.main()
