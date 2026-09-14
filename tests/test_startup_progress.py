import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'runtime'))
from mower_android.startup import StartupProgress


class StartupProgressTests(unittest.TestCase):
    def test_progress_is_bounded_and_stage_is_preserved(self):
        with tempfile.TemporaryDirectory() as root, patch.dict(os.environ, MOWER_DATA_DIR=root):
            progress = StartupProgress()
            with patch('builtins.print') as output:
                for percent in range(101): progress.report('解压 MAA 组件', percent)
                self.assertEqual(output.call_count, 21)
                progress.report('加载 Mower WebUI')
                self.assertEqual(output.call_count, 22)
            self.assertEqual(json.loads(progress.path.read_text()), {'stage': '加载 Mower WebUI', 'percent': -1})
            self.assertFalse(progress.path.with_suffix('.new').exists())
