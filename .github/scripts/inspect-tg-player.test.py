import runpy
import unittest
from pathlib import Path

module = runpy.run_path(str(Path(__file__).with_name('inspect-tg-player.py')))

class CredentialStatusTest(unittest.TestCase):
    def test_expiry_and_future_start_are_separate(self):
        active = module['credential_window']('__hdnea__=st=10~exp=100~hmac=private', 50)
        self.assertEqual(50, active['expiresInSeconds'])
        self.assertFalse(active['expired'])
        self.assertFalse(active['notYetValid'])
        self.assertNotIn('private', str(active))
        self.assertTrue(module['credential_window']('__hdnea__=st=80~exp=200~hmac=private', 50)['notYetValid'])
        self.assertTrue(module['credential_window']('__hdnea__=exp=40~hmac=private', 50)['expired'])
    def test_opaque_or_absent_credentials_are_unknown(self):
        self.assertIsNone(module['credential_window']('__hdnea__=opaque-private', 50)['expired'])
        self.assertFalse(module['credential_window']('', 50)['present'])
    def test_relay_metadata_is_not_misrepresented_as_its_wire_authentication(self):
        data = module['source_credential_status']({'urls':['https://media.example/a.mpd?__hdnea__=exp=80~hmac=private'],
            'cookie':'__hdnea__=exp=90~hmac=private', 'backups':[{'urls':['https://allinonereborn2.online/jtv.mpd?id=872'],
            'cookie':'__hdnea__=exp=1~hmac=private'}]}, 50)
        self.assertTrue(data[1]['urls'][0]['relayOwnsQuery'])
        self.assertFalse(data[1]['urls'][0]['embeddedCredential']['present'])
        self.assertNotIn('hmac', str(data))
        self.assertNotIn('id=872', str(data))

if __name__ == '__main__':
    unittest.main()
