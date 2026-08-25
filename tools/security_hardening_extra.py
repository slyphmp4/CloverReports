from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "src/main/java/com/slyph/cloverreports/submission/EvidenceUrlValidator.java"
text = path.read_text(encoding="utf-8")
old = 'plugin.getConfig().getBoolean("report.evidence.require-https", false)'
new = 'plugin.getConfig().getBoolean("report.evidence.require-https", true)'
if text.count(old) != 1:
    raise RuntimeError(f"expected one HTTPS default, found {text.count(old)}")
text = text.replace(old, new, 1)
old = 'return Set.of(80, 443);'
new = 'return Set.of(443);'
if text.count(old) != 1:
    raise RuntimeError(f"expected one default evidence port set, found {text.count(old)}")
text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")

# This helper is transient and should not appear in the final hardening commit.
Path(__file__).unlink()
