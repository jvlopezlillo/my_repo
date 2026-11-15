// Saca el JavaScript inyectado (MediaDetector.SCRIPT) del código Kotlin, para probarlo tal cual.
const fs = require('fs');
const path = require('path');
module.exports = function loadScript() {
  const kt = fs.readFileSync(path.join(__dirname, '../../app/src/main/java/com/castbrowser/MediaDetector.kt'), 'utf8');
  const m = kt.match(/val SCRIPT = """\n([\s\S]*?)""".trimIndent\(\)/);
  if (!m) throw new Error('No encuentro SCRIPT en MediaDetector.kt');
  return m[1];
};
