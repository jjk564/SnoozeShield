import os
import sys
import subprocess
import webbrowser

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
PC_BRIDGE_DIR = os.path.join(ROOT_DIR, "pc_bridge")
SERVER_SCRIPT = os.path.join(PC_BRIDGE_DIR, "server.py")


def main():
    port = int(os.environ.get("WAKEGUARD_PORT", "5175"))
    if "--no-browser" not in sys.argv:
        webbrowser.open(f"http://localhost:{port}")
    subprocess.run([sys.executable, SERVER_SCRIPT], cwd=PC_BRIDGE_DIR)


if __name__ == "__main__":
    main()
