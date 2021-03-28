import java.io.File

object Schedule {
    const val gamesToPlay = 2
    val users = File("build/resources/main/participants_list").readLines().toTypedArray()
    val sheet = mutableMapOf<String, ArrayList<String>>()
}