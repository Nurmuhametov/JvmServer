import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ArgsParser(val loginFromFile: Boolean, val serverPort: Int, val mariaAddress: String, val mariaPort : Int, val dbName :String, val dbLogin : String, val dbPassword: String)

const val CONFIG_PATH = "build/resources/main/config.json"
fun main(argv: Array<String>) {
    val json = File(CONFIG_PATH).readLines()
    val data = json.joinToString(separator = "")
    val argsParser = Json.decodeFromString<ArgsParser>(data)
    Server.getInstance(argsParser)
}