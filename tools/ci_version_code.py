"""Shared, history-based Android version codes for both GitHub APK workflows."""
import subprocess

BASE = 2_000_000  # Above the legacy million-based FYT debug sequence.
MAX_VERSION_CODE = 2_100_000_000


def version_code(commit_count: int) -> int:
    value = BASE + commit_count
    if commit_count < 1 or value > MAX_VERSION_CODE:
        raise ValueError("Git history does not fit the Android version-code range")
    return value


def main() -> None:
    shallow = subprocess.check_output(["git", "rev-parse", "--is-shallow-repository"], text=True).strip()
    if shallow != "false":
        raise SystemExit("Full Git history is required; use checkout fetch-depth: 0")
    count = int(subprocess.check_output(["git", "rev-list", "--count", "HEAD"], text=True))
    print(version_code(count))


if __name__ == "__main__":
    main()
