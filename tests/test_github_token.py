import hashlib
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))
from arknights_mower.utils import network_settings
from mower_android import python_updates


class AndroidGithubTokenTests(unittest.TestCase):
    def test_python_package_download_uses_shared_token_and_skips_mirror(self):
        content = b"validated fixture"
        url = f"https://github.com/{python_updates.REPO}/releases/download/v1.0.0/adapter.zip"
        item = {
            "browser_download_url": url,
            "size": len(content),
            "digest": "sha256:" + hashlib.sha256(content).hexdigest(),
        }
        response = MagicMock()
        response.__enter__.return_value = response
        response.iter_content.return_value = [content]
        with (
            patch.object(
                network_settings,
                "get_effective_settings",
                return_value={
                    "github_proxy": "https://mirror.invalid/",
                    "github_token": "github_pat_test",
                },
            ),
            patch.object(python_updates.requests, "get", return_value=response) as get,
        ):
            self.assertEqual(python_updates.fetch(item, 1024), content)
        self.assertEqual(get.call_args.args[0], url)
        self.assertEqual(
            get.call_args.kwargs["headers"]["Authorization"], "Bearer github_pat_test"
        )
        get.assert_called_once()

    def test_official_component_import_lookup_uses_shared_token(self):
        from arknights_mower.utils import maa_update
        from mower_android import managed

        with tempfile.TemporaryDirectory() as temp:
            package = Path(temp) / "component.tar.gz"
            package.write_bytes(b"not the official component")
            response = MagicMock()
            response.json.return_value = {
                "tag_name": "v6.21.3",
                "assets": [
                    {
                        "name": "MAAComponent-v6.21.3-android-arm64.tar.gz",
                        "size": 100,
                        "browser_download_url": "https://github.com/official/component",
                        "digest": "sha256:" + "0" * 64,
                    }
                ],
            }
            with (
                patch.object(
                    network_settings,
                    "get_effective_settings",
                    return_value={"github_token": "github_pat_test"},
                ),
                patch("requests.get", return_value=response) as get,
                self.assertRaisesRegex(maa_update.MaaUpdateError, "SHA256"),
            ):
                managed.import_component(
                    package, "MAAComponent-v6.21.3-android-arm64.tar.gz"
                )
            self.assertEqual(
                get.call_args.kwargs["headers"]["Authorization"],
                "Bearer github_pat_test",
            )

    def test_mower_full_and_ota_downloads_use_shared_token(self):
        from mower_android import app_update

        content = b"validated mower archive"
        url = "https://github.com/ArkMowers/MowerRelease/releases/download/v1.0.0/package.zip"
        item = {
            "browser_download_url": url,
            "size": len(content),
            "digest": "sha256:" + hashlib.sha256(content).hexdigest(),
        }
        response = MagicMock()
        response.__enter__.return_value = response
        response.iter_content.return_value = [content]
        with (
            tempfile.TemporaryDirectory() as temp,
            patch.object(app_update, "folder", return_value=Path(temp)),
            patch.dict(app_update._job),
            patch.object(
                network_settings,
                "get_effective_settings",
                return_value={
                    "github_proxy": "https://mirror.invalid/",
                    "github_token": "github_pat_test",
                },
            ),
            patch("requests.get", return_value=response) as get,
        ):
            path = app_update.download_asset(item, "Mower OTA")
            self.assertEqual(path.read_bytes(), content)
        self.assertEqual(get.call_args.args[0], url)
        self.assertEqual(
            get.call_args.kwargs["headers"]["Authorization"], "Bearer github_pat_test"
        )
        get.assert_called_once()

    def test_settings_routes_require_instance_token_before_side_effects(self):
        from arknights_mower.views.network import network_bp
        from flask import Flask

        app = Flask(__name__)
        app.token = "instance_token"
        app.register_blueprint(network_bp)
        client = app.test_client()
        with (
            patch.object(network_settings, "get_settings") as read,
            patch.object(network_settings, "save_settings") as save,
            patch("requests.get") as get,
        ):
            for credential in ({}, {"token": "wrong"}):
                for method, path in (
                    (client.get, "/network/settings"),
                    (client.head, "/network/settings"),
                    (client.post, "/network/settings"),
                    (client.post, "/network/test"),
                ):
                    self.assertEqual(
                        method(
                            path,
                            headers={**credential, "X-Mower-Settings": "1"},
                            json={},
                        ).status_code,
                        403,
                    )
            read.assert_not_called()
            save.assert_not_called()
            get.assert_not_called()
        with patch.object(
            network_settings,
            "save_settings",
            return_value={"github_token": "github_pat_test"},
        ) as save:
            response = client.post(
                "/network/settings",
                json={
                    "http_proxy": "",
                    "github_proxy": "",
                    "github_token": "github_pat_test",
                },
                headers={"token": app.token, "X-Mower-Settings": "1"},
            )
            self.assertEqual(response.status_code, 200)
            self.assertEqual(save.call_args.args[0]["github_token"], "github_pat_test")
