import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))
from mower_android import managed


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

    def test_official_ncnn_models_are_packed_unchanged(self):
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

    def test_incomplete_ncnn_models_preserve_existing_component(self):
        for kind in ("det", "rec"):
            self.pair(self.resource / "PaddleOCR" / kind)
        (self.resource / "PaddleOCR/rec/rec.ncnn.bin").unlink()
        component = self.root / "component.zip"
        component.write_bytes(b"existing component")
        with self.assertRaisesRegex(ValueError, "NCNN OCR"):
            managed.pack_component(self.root / "stage", component)
        self.assertEqual(component.read_bytes(), b"existing component")
        self.assertFalse(component.with_suffix(".zip.new").exists())
