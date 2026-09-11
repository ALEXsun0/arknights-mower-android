import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'runtime'))
from mower_android.managed import normalize

class ManagedSettingsTests(unittest.TestCase):
    def test_desktop_import_keeps_tasks_and_replaces_connection(self):
        source = {'adb':'127.0.0.1:5555','maa_path':'C:\\MAA','simulator':{'name':'MuMu12'},
                  'maa_weekly_plan':[{'stage':['1-7']}], 'theme':'dark', 'droidcast':{'enable':True}}
        result = normalize(source)
        self.assertEqual(result['maa_path'], '/mower-data/maa')
        self.assertEqual(result['adb'], 'Android')
        self.assertEqual(result['simulator']['name'], '')
        self.assertFalse(result['droidcast']['enable'])
        self.assertEqual(result['maa_weekly_plan'], source['maa_weekly_plan'])
        self.assertEqual(result['theme'], 'dark')
        self.assertEqual(source['simulator']['name'], 'MuMu12')

    @patch.dict('os.environ', {'MOWER_WEB_TOKEN':'session-secret','MOWER_WEB_PORT':'12345'})
    def test_import_cannot_change_live_web_endpoint(self):
        result = normalize({'webview':{'port':58000,'token':'desktop-token','scale':1.5}})
        self.assertEqual(result['webview'], {'port':12345,'token':'session-secret','scale':1.5})

if __name__ == '__main__': unittest.main()
