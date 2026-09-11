import sys
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'runtime'))
from mower_android.managed import normalize

class ManagedSettingsTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.environment = patch.dict('os.environ', {'MOWER_DATA_DIR': self.folder.name})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_screenshot_history_defaults_to_zero_even_after_desktop_import(self):
        self.assertEqual(normalize({})['screenshot'], 0)
        self.assertEqual(normalize({'screenshot': 72})['screenshot'], 0)

    def test_native_screenshot_setting_wins_over_hidden_webui_draft(self):
        path = Path(self.folder.name) / 'native-screenshot.json'
        path.write_text(json.dumps({'hours': 0.25}))
        self.assertEqual(normalize({'screenshot': 72})['screenshot'], 0.25)
        path.write_text(json.dumps({'hours': 0}))
        self.assertEqual(normalize({'screenshot': 72})['screenshot'], 0)

    def test_invalid_native_screenshot_settings_fail_closed(self):
        path = Path(self.folder.name) / 'native-screenshot.json'
        for value in (-1, True, '1', float('nan'), float('inf'), None):
            with self.subTest(value=value):
                path.write_text(json.dumps({'hours': value}))
                self.assertEqual(normalize({'screenshot': 72})['screenshot'], 0)
        path.write_text('{')
        self.assertEqual(normalize({'screenshot': 72})['screenshot'], 0)

    def test_desktop_import_keeps_tasks_and_replaces_connection(self):
        source = {'adb':'127.0.0.1:5555','maa_path':'C:\\MAA','simulator':{'name':'MuMu12'},
                  'maa_weekly_plan':[{'stage':['1-7']}], 'theme':'dark', 'droidcast':{'enable':True}}
        result = normalize(source)
        self.assertEqual(result['maa_path'], '/mower-data/maa')
        self.assertEqual(result['adb'], 'Android')
        self.assertEqual(result['simulator']['name'], '')
        self.assertFalse(result['droidcast']['enable'])
        self.assertEqual(result['maa_weekly_plan'], source['maa_weekly_plan'])
        self.assertEqual(result['theme'], 'light')
        self.assertEqual(source['simulator']['name'], 'MuMu12')

    def test_native_theme_wins_over_imported_or_stale_webui_theme(self):
        path = Path(self.folder.name) / 'native-appearance.json'
        path.write_text(json.dumps({'theme': 'dark'}))
        self.assertEqual(normalize({'theme': 'light'})['theme'], 'dark')
        path.write_text(json.dumps({'theme': 'light'}))
        self.assertEqual(normalize({'theme': 'dark'})['theme'], 'light')
        for value in ('auto', None, []):
            path.write_text(json.dumps({'theme': value}))
            self.assertEqual(normalize({'theme': 'dark'})['theme'], 'light')

    @patch.dict('os.environ', {'MOWER_WEB_TOKEN':'session-secret','MOWER_WEB_PORT':'12345'})
    def test_import_cannot_change_live_web_endpoint(self):
        result = normalize({'webview':{'port':58000,'token':'desktop-token','scale':1.5}})
        self.assertEqual(result['webview'], {'port':12345,'token':'session-secret','scale':1.5})

if __name__ == '__main__': unittest.main()
