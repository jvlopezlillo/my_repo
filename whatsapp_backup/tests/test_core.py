import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from wabackup import core  # noqa: E402
from wabackup.adb import Adb, AdbError  # noqa: E402


class FakeAdb(Adb):
    """Móvil simulado: `root` es un directorio que hace de sistema de ficheros."""

    def __init__(self, root: Path):
        self.root, self.serial, self.path = root, "FAKE", "adb"
        self.installed = (root / "apps").exists()

    def _p(self, remote):  # /sdcard/x -> root/sdcard/x
        return self.root / remote.lstrip("/")

    def devices(self): return [("FAKE", "device")]
    def exists(self, remote): return self._p(remote).exists()
    def package_paths(self, pkg): return ["/data/app/base.apk"] if self.installed else []
    def package_version(self, pkg): return ("2.20", "1")
    def shell(self, cmd, check=True): return ""

    def pull(self, remote, local_dir):
        src = self._p(remote); Path(local_dir).mkdir(parents=True, exist_ok=True)
        (shutil.copytree if src.is_dir() else shutil.copy)(src, Path(local_dir) / src.name)

    def push(self, local, remote_dir):
        dst = self._p(remote_dir); dst.mkdir(parents=True, exist_ok=True)
        local = Path(local)
        (shutil.copytree if local.is_dir() else shutil.copy)(local, dst / local.name)

    def install(self, apks, downgrade=False): self.installed = True


class Test(unittest.TestCase):
    def test_roundtrip(self):
        t = Path(tempfile.mkdtemp())
        phone = t / "phone"
        (phone / "data/app").mkdir(parents=True); (phone / "data/app/base.apk").write_bytes(b"apk")
        (phone / "apps").mkdir()
        wa = phone / "sdcard/WhatsApp"
        (wa / "Databases").mkdir(parents=True); (wa / "Media/Images").mkdir(parents=True)
        (wa / "Databases/msgstore.db.crypt14").write_bytes(b"chats")
        (wa / "Media/Images/a.jpg").write_bytes(b"img")

        out = t / "bk"
        core.backup(FakeAdb(phone), out, lambda m: None)
        self.assertEqual(core.verify(out)["version_name"], "2.20")

        new = t / "new"; (new / "sdcard").mkdir(parents=True)
        fa = FakeAdb(new)
        core.restore(fa, out, lambda m: None)
        self.assertTrue(fa.installed)
        self.assertEqual((new / "sdcard/WhatsApp/Media/Images/a.jpg").read_bytes(), b"img")
        self.assertEqual((new / "sdcard/WhatsApp/Databases/msgstore.db.crypt14").read_bytes(), b"chats")

        (out / "storage/legacy/WhatsApp/Media/Images/a.jpg").write_bytes(b"corrupt")
        with self.assertRaises(AdbError):
            core.verify(out)

    def test_nonempty_dest(self):
        t = Path(tempfile.mkdtemp()); (t / "x").write_text("1")
        with self.assertRaises(AdbError):
            core.backup(FakeAdb(t), t, lambda m: None)


if __name__ == "__main__":
    unittest.main()
