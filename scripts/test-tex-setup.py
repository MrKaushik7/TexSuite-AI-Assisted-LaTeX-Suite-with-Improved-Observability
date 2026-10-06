#!/usr/bin/env python3
"""Exercise the real installer using isolated commands and terminal input."""

import os
from pathlib import Path
import pty
import select
import shutil
import subprocess
import tempfile
import time
import unittest


PROJECT = Path(__file__).resolve().parent.parent
DOWNLOAD_URL = "https://www.tug.org/mactex/morepackages.html"
OPEN_PROMPT = "Open the official BasicTeX download page? [y/N]: "
RECHECK_PROMPT = "Press Enter to check for pdflatex, or type skip to finish: "


class TeXSetupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="texsuite setup ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.fallback = self.root / "texbin"
        self.bin.mkdir()
        self.fallback.mkdir()
        scripts = self.root / "project/scripts"
        scripts.mkdir(parents=True)
        target = self.root / "project/target"
        target.mkdir()
        shutil.copyfile(PROJECT / "target/texsuite.jar", target / "texsuite.jar")
        for document in ("README.md", "opinions.md"):
            shutil.copyfile(PROJECT / document, target.parent / document)
        self.installer = scripts / "install.sh"
        # Isolate the fixed macOS fallback without adding production test hooks.
        installer = (PROJECT / "scripts/install.sh").read_text()
        self.assertEqual(1, installer.count("/Library/TeX/texbin"))
        self.installer.write_text(installer.replace("/Library/TeX/texbin", str(self.fallback)))
        for command in ("dirname", "mkdir", "install", "cat", "chmod"):
            (self.bin / command).symlink_to(shutil.which(command))
        self.write_executable(self.bin / "open", "printf '%s\\n' \"$1\" >> \"$BROWSER_LOG\"\nexit \"${BROWSER_STATUS:-0}\"\n")
        self.browser_log = self.root / "browser.log"
        self.env = dict(os.environ, PATH=str(self.bin),
                        TEXSUITE_INSTALL_DIR=str(self.root / "app"),
                        TEXSUITE_BIN_DIR=str(self.root / "launchers"),
                        BROWSER_LOG=str(self.browser_log))

    @staticmethod
    def write_executable(path, body="exit 0\n"):
        path.write_text("#!/bin/sh\n" + body)
        path.chmod(0o755)

    def run_noninteractive(self):
        result = subprocess.run(["/bin/sh", str(self.installer)], env=self.env,
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=10,
                                cwd=self.root)
        self.assertEqual(0, result.returncode, result.stdout)
        return result.stdout

    def run_interactive(self, answers, install_on_recheck=False):
        master, slave = pty.openpty()
        process = subprocess.Popen(["/bin/sh", str(self.installer)], env=self.env,
                                   stdin=slave, stdout=slave, stderr=slave, cwd=self.root)
        os.close(slave)
        output = bytearray()
        sent = 0
        deadline = time.monotonic() + 10
        try:
            while time.monotonic() < deadline:
                if select.select([master], [], [], 0.1)[0]:
                    try:
                        chunk = os.read(master, 65536)
                    except OSError:
                        break  # macOS/Linux PTYs signal closed slave differently.
                    if not chunk:
                        break
                    output.extend(chunk)
                text = output.decode(errors="replace")
                if sent < len(answers):
                    prompt = OPEN_PROMPT if sent == 0 else RECHECK_PROMPT
                    if prompt in text:
                        if sent == 1 and install_on_recheck:
                            self.write_executable(self.fallback / "pdflatex")
                        os.write(master, answers[sent])
                        sent += 1
            self.assertEqual(len(answers), sent, output.decode(errors="replace"))
            self.assertEqual(0, process.wait(timeout=1), output.decode(errors="replace"))
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            os.close(master)
        return output.decode().replace("\r\n", "\n")

    def assert_browser_opened_once(self):
        self.assertEqual(DOWNLOAD_URL + "\n", self.browser_log.read_text())

    def test_existing_compiler_uses_path_before_fallback(self):
        self.write_executable(self.bin / "pdflatex")
        self.write_executable(self.fallback / "pdflatex")
        output = self.run_noninteractive()
        self.assertIn(f"TeX compiler found: {self.bin}/pdflatex", output)
        self.assertNotIn(OPEN_PROMPT, output)
        self.assertFalse(self.browser_log.exists())

    def test_fallback_ignores_nonexecutable_path_candidate(self):
        (self.bin / "pdflatex").write_text("not executable")
        self.write_executable(self.fallback / "pdflatex")
        self.assertIn(f"TeX compiler found: {self.fallback}/pdflatex", self.run_noninteractive())

    def test_relative_and_empty_path_entries_are_ignored(self):
        self.write_executable(self.root / "pdflatex")
        self.env["PATH"] = ":.:" + str(self.bin)
        self.assertIn("pdflatex was not found", self.run_noninteractive())

    def test_missing_compiler_noninteractive_has_guidance_without_prompt(self):
        output = self.run_noninteractive()
        self.assertIn(DOWNLOAD_URL, output)
        self.assertIn("Install BasicTeX (recommended): " + DOWNLOAD_URL, output)
        self.assertIn("add missing packages with tlmgr", output)
        self.assertIn("Optional full MacTeX for broader package coverage: "
                      "https://www.tug.org/mactex/mactex-download.html", output)
        self.assertIn("--allow-no-compile", output)
        self.assertNotIn(OPEN_PROMPT, output)
        self.assertFalse(self.browser_log.exists())
        self.assertTrue((self.root / "app/texsuite.jar").is_file())
        for document in ("README.md", "opinions.md"):
            self.assertEqual((PROJECT / document).read_bytes(),
                             (self.root / "app" / document).read_bytes())

    def test_decline_and_default_do_not_open_browser(self):
        for answer in (b"n\n", b"\n"):
            output = self.run_interactive([answer])
            self.assertNotIn(RECHECK_PROMPT, output)
            self.assertFalse(self.browser_log.exists())

    def test_eof_at_initial_prompt_finishes(self):
        self.run_interactive([b"\x04"])
        self.assertFalse(self.browser_log.exists())

    def test_accept_and_successful_recheck(self):
        output = self.run_interactive([b"y\n", b"\n"], install_on_recheck=True)
        self.assert_browser_opened_once()
        self.assertIn(f"TeX compiler found: {self.fallback}/pdflatex", output)

    def test_accept_and_missing_recheck(self):
        output = self.run_interactive([b"yes\n", b"\n"])
        self.assert_browser_opened_once()
        self.assertIn("pdflatex is still unavailable", output)
        self.assertEqual(1, output.count(RECHECK_PROMPT))

    def test_browser_failure_and_skip_keep_install_successful(self):
        self.env["BROWSER_STATUS"] = "9"
        output = self.run_interactive([b"y\n", b"skip\n"])
        self.assert_browser_opened_once()
        self.assertIn("Could not open the browser", output)
        self.assertNotIn("pdflatex is still unavailable", output)
        self.assertTrue((self.root / "launchers/texsuite").is_file())

    def test_eof_at_recheck_finishes(self):
        output = self.run_interactive([b"y\n", b"\x04"])
        self.assert_browser_opened_once()
        self.assertNotIn("pdflatex is still unavailable", output)


if __name__ == "__main__":
    unittest.main()
