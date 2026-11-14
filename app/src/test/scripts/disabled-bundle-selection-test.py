"""Exercise production selection filtering across bundle disable/re-enable transitions."""
from pathlib import Path
import subprocess,tempfile,os
root=Path(__file__).resolve().parents[4]
helper=root/'app/src/test/scripts/bundle-ui-issues-test.py'
env={'__file__':str(helper)}
exec(helper.read_text(encoding='utf-8').split('(ROOT / "build").mkdir',1)[0],env)
text=(root/'app/src/main/java/app/universal/patch/manager/ui/viewmodel/SelectedAppInfoViewModel.kt').read_text(encoding='utf-8')
method=text.split('    private fun pruneSelectionForAvailability(',1)[1].split('    val requiredVersion',1)[0]
method='    private fun pruneSelectionForAvailability('+method
fixture="""
data class Patch(val name: String)
object PatchBundleInfo {
    data class Scoped(val uid: Int, val patches: List<Patch>) {
        fun patchSequence(allow: Boolean) = patches.asSequence()
    }
}
sealed interface SelectionState {
    data object Default : SelectionState
    data class Customized(val patchSelection: Map<Int,Set<String>>) : SelectionState {
        fun patches(bundles:List<PatchBundleInfo.Scoped>,vararg args:Any?) =
            patchSelection.filterKeys { uid -> bundles.any { it.uid==uid } }
    }
}
class Flow<T>(val value:T)
class Options { fun filtered(bundles:List<PatchBundleInfo.Scoped>) = this }
class Fixture(selection: Map<Int,Set<String>>) {
    var selectionState: SelectionState = SelectionState.Customized(selection)
    val preferredBundleUidFlow=Flow<Int?>(null)
    val preferredBundleOverrideFlow=Flow<String?>(null)
    val usingMountInstall=false
    val patchAvailabilityEnabled=true
    val options=Options()
    fun replaceOptions(options:Options) {}
    fun installerTypeFor(mount:Boolean)=0
    fun shouldRemoveGmsCore(mount:Boolean)=false
    fun apply(bundles:List<PatchBundleInfo.Scoped>)=pruneSelectionForAvailability(bundles,false)
""" + method + """
}
fun main() {
    val a=setOf("a1","a2","a3")
    val b=setOf("b1","b2")
    val bundleA=PatchBundleInfo.Scoped(1,a.map(::Patch))
    val bundleB=PatchBundleInfo.Scoped(2,b.map(::Patch))
    val model=Fixture(mapOf(1 to a,2 to b))
    model.apply(listOf(bundleA))
    check((model.selectionState as SelectionState.Customized).patchSelection == mapOf(1 to a,2 to b))
    model.apply(listOf(bundleA,bundleB))
    check((model.selectionState as SelectionState.Customized).patchSelection.values.sumOf {it.size}==5)
    val inactiveOnly=Fixture(mapOf(2 to b))
    inactiveOnly.apply(listOf(bundleA))
    check(inactiveOnly.selectionState is SelectionState.Customized)
    inactiveOnly.apply(listOf(bundleB))
    check((inactiveOnly.selectionState as SelectionState.Customized).patchSelection[2]==b)
    model.apply(emptyList())
    check(model.selectionState is SelectionState.Customized)
    println("PASS: inactive selections survive disable/re-enable, including inactive-only and empty bundle lists")
}
"""
with tempfile.TemporaryDirectory(dir=root/'build',prefix='disabled-selection-') as tmp:
    work=Path(tmp); source=work/'Test.kt';source.write_text(fixture,encoding='utf-8')
    classes=work/'classes'
    cp=env['cp'];deps=env['deps']
    subprocess.run(list(map(str,[env['JAVA'],'-cp',cp(env['compiler']),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp(deps),'-d',classes,source])),check=True)
    subprocess.run(list(map(str,[env['JAVA'],'-cp',cp([classes]+deps),'TestKt'])),check=True)
