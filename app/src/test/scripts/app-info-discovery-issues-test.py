"""Check version-code retention with production SQL and localized catalog diagnostics."""
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[4]
base = ROOT / "app/src/main/java/app/universal/patch/manager"
dao = (base / "data/room/apps/installed/InstalledAppDao.kt").read_text()
query = re.search(r'@Query\("([^"]+)"\)\s*suspend fun rememberVersionCode', dao).group(1)
with tempfile.TemporaryDirectory(prefix="app-info-version-", dir=ROOT / "build") as temporary:
    database = Path(temporary) / "apps.db"
    db = sqlite3.connect(database)
    db.execute("CREATE TABLE installed_app(current_package_name TEXT PRIMARY KEY,version TEXT,version_code INTEGER,selection_payload TEXT,repatch_source_path TEXT)")
    db.execute("INSERT INTO installed_app VALUES('app','4.3.2360',NULL,'selected patches','retained.apk')")
    db.execute(query, dict(packageName="app", version="4.3.2360", versionCode=2360))
    db.commit()
    db.close()
    db = sqlite3.connect(database)  # Metadata remains after uninstall and manager restart.
    assert db.execute("SELECT * FROM installed_app").fetchone() == ("app", "4.3.2360", 2360, "selected patches", "retained.apk")
    db.execute(query, dict(packageName="app", version="4.3.2360", versionCode=9999))
    assert db.execute("SELECT version_code FROM installed_app").fetchone()[0] == 2360
    db.execute("UPDATE installed_app SET version='5.0',version_code=NULL")
    db.execute(query, dict(packageName="app", version="4.3.2360", versionCode=2360))
    assert db.execute("SELECT version_code FROM installed_app").fetchone()[0] is None
    db.close()
resources = []
for locale in ("values", "values-en-rUS"):
    xml = ET.parse(ROOT / f"app/src/main/res/{locale}/strings.xml").getroot()
    keys = [node.attrib["name"] for node in xml]
    assert len(keys) == len(set(keys))
    resources.append({node.attrib["name"]: node.text for node in xml if node.tag == "string"})
for key in ("catalog_request_timeout", "catalog_bundle_changed_discovery", "catalog_changed_refresh"):
    assert resources[0][key] == resources[1][key]
namespace = {"__file__": str(Path(__file__).with_name("bundle-ui-issues-test.py"))}
exec(Path(namespace["__file__"]).read_text().split('(ROOT / "build").mkdir')[0], namespace)
compiler, deps, JAVA, cp = (namespace[k] for k in ("compiler", "deps", "JAVA", "cp"))
with tempfile.TemporaryDirectory(prefix="catalog-errors-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    (work / "Context.kt").write_text("""package android.content
open class Context {
    fun getString(id: Int) = when(id) { 1 -> "localized fallback"; 2 -> "localized history"; else -> "localized catalog" }
}
""")
    (work / "R.kt").write_text("""package app.universal.patch.manager
object R { object string {
const val patch_bundle_discovery_error=1
const val catalog_changed_history=2
const val catalog_changed_refresh=3
} }
""")
    (work / "Main.kt").write_text("""import android.content.Context
import app.universal.patch.manager.util.catalogErrorMessage
fun main() {
    val context=Context()
    check(context.catalogErrorMessage(null) == "localized fallback")
    check(context.catalogErrorMessage("Catalog changed; refresh release history") == "localized history")
    check(context.catalogErrorMessage("Catalog changed; refresh the catalog") == "localized catalog")
    check(context.catalogErrorMessage("Server detail") == "Server detail")
    println("PASS: version code survives restart, existing codes/version boundaries/selections protected, localized errors preserve remote details")
}
""")
    classes = work / "classes"
    sources = list(work.glob("*.kt")) + [base / "util/CatalogErrorMessage.kt"]
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps),
        "-d", classes, *sources])), check=True, timeout=90)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps), "MainKt"])), check=True, timeout=30)
