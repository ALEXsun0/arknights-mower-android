import importlib.util
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))
from mower_android import managed, ocr_models


class OfficialOcrModelsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.resource = self.root / "stage/resource"

    def pair(self, directory):
        directory.mkdir(parents=True)
        kind = directory.name
        for ext in ("param", "bin"):
            (directory / f"{kind}.ncnn.{ext}").write_bytes(
                f"official {kind} {ext}".encode()
            )

    def test_new_official_package_packs_models_without_converter(self):
        for base in (
            "PaddleOCR",
            "PaddleCharOCR",
            "global/YoStarEN/resource/PaddleOCR",
        ):
            for kind in ("det", "rec"):
                self.pair(self.resource / base / kind)
        for kind in ("det", "rec"):
            self.pair(self.root / "stage/cache/resource/PaddleOCR" / kind)
        (self.root / "stage/libMaaCore.so").write_bytes(b"core")
        with patch.object(
            importlib.util,
            "find_spec",
            side_effect=AssertionError("No conversion needed"),
        ):
            managed.pack_component(self.root / "stage", self.root / "component.zip")
        with zipfile.ZipFile(self.root / "component.zip") as archive:
            self.assertEqual(
                archive.read("resource/PaddleOCR/det/det.ncnn.param"),
                b"official det param",
            )
            self.assertIn(
                "cache/resource/PaddleOCR/rec/rec.ncnn.bin", archive.namelist()
            )
            self.assertIn(
                "resource/global/YoStarEN/resource/PaddleOCR/rec/rec.ncnn.bin",
                archive.namelist(),
            )

    def test_official_pair_is_preserved_when_onnx_is_also_present(self):
        folder = self.resource / "PaddleOCR/det"
        self.pair(folder)
        (folder / "inference.onnx").write_bytes(b"onnx")
        with patch.object(
            importlib.util,
            "find_spec",
            side_effect=AssertionError("No conversion needed"),
        ):
            ocr_models.prepare_models(self.resource)
        self.assertEqual((folder / "det.ncnn.bin").read_bytes(), b"official det bin")

    def test_old_package_converts_onnx_and_reuses_matching_result(self):
        folder = self.resource / "PaddleOCR/rec"
        folder.mkdir(parents=True)
        (folder / "inference.onnx").write_bytes(b"old official onnx")

        def convert(args, **kwargs):
            self.assertIn("inputshape=[1,3,48,320]", args)
            for ext in ("param", "bin"):
                (Path(kwargs["cwd"]) / f"rec.ncnn.{ext}").write_bytes(b"converted")
            return SimpleNamespace(returncode=0)

        with (
            patch.object(
                importlib.util,
                "find_spec",
                return_value=SimpleNamespace(origin="/converter/__init__.py"),
            ),
            patch.object(ocr_models.subprocess, "run", side_effect=convert) as run,
        ):
            ocr_models.prepare_models(self.resource)
            ocr_models.prepare_models(self.resource)
        run.assert_called_once()
        self.assertEqual((folder / "rec.ncnn.bin").read_bytes(), b"converted")

    def test_old_onnx_without_converter_still_reports_actionable_error(self):
        folder = self.resource / "PaddleOCR/det"
        folder.mkdir(parents=True)
        (folder / "inference.onnx").write_bytes(b"onnx")
        with (
            patch.object(importlib.util, "find_spec", return_value=None),
            self.assertRaisesRegex(RuntimeError, "缺少 Android OCR 模型转换器"),
        ):
            ocr_models.prepare_models(self.resource)
