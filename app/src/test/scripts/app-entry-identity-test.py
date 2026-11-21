"""Exercise the archive's production patch-payload comparison across copied inputs."""
import os, subprocess, tempfile, tomllib
from pathlib import Path
ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / "app/src/main/java/app/universal/patch/manager"
CACHE = ROOT / ".gradle-user-home/caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
version = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["kotlin"]
def jar(group,name,v="*"):
    return next((CACHE/group/name).glob(v+"/*/*.jar"))
compiler = [jar("org.jetbrains.kotlin",n,version) for n in
            ("kotlin-compiler-embeddable","kotlin-stdlib","kotlin-reflect","kotlin-script-runtime")]
compiler += [jar("org.jetbrains.kotlinx","kotlinx-coroutines-core-jvm"),jar("org.jetbrains","annotations")]
deps = compiler + [jar("org.jetbrains.kotlinx",n) for n in
                   ("kotlinx-serialization-core-jvm","kotlinx-serialization-json-jvm")]
source = (BASE/"domain/repository/AppEntryArchive.kt").read_text(encoding="utf-8")
compare = source.split("    private fun samePayload(",1)[1].split("    private fun previewIcon(",1)[0]
rewrite = source.split("    private fun PatchProfilePayload.rewriteOptions(",1)[1].split("    private companion object",1)[0]
fixture = """
package app.universal.patch.manager.domain.repository
import java.io.File
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.data.room.options.Option
import kotlinx.serialization.json.*
class Inputs { fun isManagedPatchOptionInputPath(path: String) = File(path).isAbsolute }
class Comparison {
 private val fs = Inputs()
 fun samePayload(""" + compare + """
 private fun PatchProfilePayload.rewriteOptions(""" + rewrite + """
}
fun main(args: Array<String>) {
 val check = Comparison()
 val a = File(args[0],"a.txt").apply { writeText("same") }
 val b = File(args[0],"b.txt").apply { writeText("same") }
 fun payload(id: Int, patches: List<String>, file: File, label: String) = PatchProfilePayload(listOf(
  PatchProfilePayload.Bundle(id,patches,mapOf("patch" to mapOf("file" to Option.SerializedValue(JsonPrimitive(file.path)))),
   displayName=label,sourceEndpoint="https://example.com/bundle",version="1")
 ))
 val original = payload(1,listOf("a","b"),a,"Old label")
 check(check.samePayload(original,payload(9,listOf("b","a"),b,"New label")))
 check(!check.samePayload(original,payload(9,listOf("a"),b,"New label")))
 b.writeText("different")
 check(!check.samePayload(original,payload(9,listOf("a","b"),b,"Old label")))
 check(!check.samePayload(original,original.copy(signatureWorkflow=PatchProfilePayload.SignatureWorkflow(enabled=true))))
 check(!check.samePayload(original,null))
 a.writeBytes(ByteArray(1024 * 1024))
 var checks = 0
 val cancelled = runCatching {
  check.samePayload(original, original) {
   if (++checks == 3) throw java.util.concurrent.CancellationException("cancelled")
  }
 }.exceptionOrNull()
 check(cancelled is java.util.concurrent.CancellationException)
 check(checks == 3)
 println("PASS: copied inputs and bundle IDs compare equal; different patches, files and signing state remain distinct")
}
"""
option = """
package app.universal.patch.manager.data.room.options
import kotlinx.serialization.json.JsonElement
data class Option(val value: Int) { data class SerializedValue(val raw: JsonElement) }
"""
with tempfile.TemporaryDirectory(dir=ROOT/"build",prefix="entry-identity-") as d:
    work=Path(d);files=[]
    for name,content in [("Main.kt",fixture),("Option.kt",option)]:
        f=work/name;f.write_text(content,encoding="utf-8");files.append(f)
    files.append(BASE/"data/room/profile/PatchProfilePayload.kt")
    cp=os.pathsep.join(map(str,deps))
    subprocess.run(list(map(str,[JAVA,"-cp",cp,"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler","-no-stdlib",
        "-no-reflect","-jvm-target","17","-classpath",cp,"-d",work,*files])),check=True)
    subprocess.run([str(JAVA),"-cp",str(work)+os.pathsep+cp,
        "app.universal.patch.manager.domain.repository.MainKt",d],check=True)
