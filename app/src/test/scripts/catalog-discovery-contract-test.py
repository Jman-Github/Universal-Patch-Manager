"""Run discovery contracts against production Kotlin sources without Gradle tasks."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[4]
MAIN = ROOT / "app/src/main/java/app/universal/patch/manager"
CACHE = ROOT / ".gradle-user-home/caches/modules-2/files-2.1"
JAVA = Path(r"C:\Program Files\Java\jdk-17\bin\java.exe")


def jar(group, artifact, version):
    paths = list((CACHE / group / artifact / version).glob("*/*.jar"))
    if len(paths) != 1:
        raise RuntimeError(f"Expected one cached jar for {group}:{artifact}:{version}, found {paths}")
    return str(paths[0])


stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10")
coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0")
annotations = jar("org.jetbrains", "annotations", "23.0.0")
compiler = [
    jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.10"),
    jar("org.jetbrains.kotlin", "kotlin-reflect", "2.4.10"),
    jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.4.10"),
    stdlib, coroutines, annotations,
]
runtime = [stdlib, coroutines, annotations] + [
    jar("org.jetbrains.kotlinx", f"kotlinx-serialization-{module}-jvm", "1.11.0")
    for module in ("core", "json")
] + [jar("io.ktor", f"ktor-{module}-jvm", "3.5.1") for module in ("http", "utils", "io")] + [
    jar("org.slf4j", "slf4j-api", "2.0.18"),
    jar("org.jetbrains.kotlinx", "kotlinx-io-core-jvm", "0.9.0"),
    jar("org.jetbrains.kotlinx", "kotlinx-io-bytestring-jvm", "0.9.0"),
]
source = (MAIN / "network/api/ExternalBundlesApi.kt").read_text(encoding="utf-8")
filters = source[source.index("@Serializable\ndata class CatalogFilters"):
                 source.index("/** Read-only registry queries.")]
metadata = source[source.index("internal fun BundleNode.hasCurrentPatchMetadata"):
                  source.index("internal fun List<BundleNode>")]
with tempfile.TemporaryDirectory(prefix="catalog-contract-") as temporary:
    temporary = Path(temporary)
    core = temporary / "CatalogFiltersCore.kt"
    core.write_text("package app.universal.patch.manager.network.api\n"
                    "import kotlinx.serialization.Serializable\n"
                    "import kotlinx.serialization.json.*\n"
                    "import app.universal.patch.manager.network.dto.BundleNode\n" + filters + metadata,
                    encoding="utf-8")
    classes = temporary / "classes"
    sources = [core, MAIN / "network/api/CatalogAppsIndex.kt",
               MAIN / "network/api/ExternalBundlesEndpoints.kt",
               MAIN / "network/dto/ExternalBundlesGraphql.kt",
               MAIN / "network/utils/APIResponse.kt",
               MAIN / "ui/component/LazyScrollbarGeometry.kt",
               MAIN / "domain/bundles/BundleReleaseUrls.kt",
               MAIN / "domain/bundles/RepositoryReleaseSource.kt",
               ROOT / "app/src/test/fixtures/CatalogDiscoveryContract.kt"]
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(compiler),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                    "-jvm-target", "17", "-classpath", os.pathsep.join(runtime),
                    "-Xplugin=" + jar("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.4.10"),
                    "-d", str(classes), *map(str, sources)], check=True)
    subprocess.run([str(JAVA), "-cp", os.pathsep.join([str(classes), *runtime]),
                    "app.universal.patch.manager.network.api.CatalogDiscoveryContractKt", *sys.argv[1:]], check=True)
