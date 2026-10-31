package tw.hoonsor.datapack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.datapack.agent.DataPackAgentCatalog
import java.io.File

/** docs/agent-manifest.json 要跟 describe 完全一致；改動作時用 UPDATE_MANIFEST=1 重新產生 */
class ManifestTest {
    private val file = listOf("docs/agent-manifest.json", "../docs/agent-manifest.json").map { File(it) }.first { it.parentFile?.exists() == true }

    @Test fun manifestMatchesDescribe() {
        val now = DataPackAgentCatalog.describe("0.0.0")
        if (System.getenv("UPDATE_MANIFEST") == "1") file.writeText(now.toString(2) + "\n")
        assertEquals(now.toString(), JSONObject(file.readText()).toString())
    }

    @Test fun everyActionFollowsGuide() {
        DataPackAgentCatalog.actions.forEach { a ->
            val destructive = a.getString("risk") == "destructive"
            assertTrue(a.getString("name"), a.getJSONArray("examples").length() >= 2 || destructive)
            assertTrue(a.getString("title").length in 2..8)
        }
        assertTrue(DataPackAgentCatalog.actions.any { it.getString("name").endsWith("_list") })
    }
}
