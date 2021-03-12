import kotlinx.serialization.Serializable

typealias Position = Pair<Int, Int>
@Serializable
data class LoginInfo(val LOGIN: String)
@Serializable
data class Message(val MESSAGE: String)
@Serializable
data class LobbyInfo(val _id: String?,
                     val width: Int,
                     val height: Int,
                     val gameBarrierCount: Int,
                     val playerBarrierCount: Int,
                     val name: String,
                     val players_count: Int)
@Serializable
data class GetLobbyResponse(val DATA: MutableList<LobbyInfo>, val SUCCESS: Boolean)
@Serializable
data class LobbyID(val id: String)
@Serializable
data class StartGameInfo(val move: Boolean, val width: Int, val height: Int, val position: List<Int>)
@Serializable
class Obstacle private constructor (val from1: Position, val to1: Position, val from2: Position, val to2: Position) {
    constructor(array: List<Position>) : this(array[0], array[1], array[2], array[3])
    fun stepOverObstacle(from: Position, to: Position) : Boolean {
        if((from.first == from1.first && from.second == from1.second && to.first == to1.first && to.second == to1.second) ||
            (from.first == from2.first && from.second == from2.second && to.first == to2.first && to.second == to2.second) ||
            (from.first == to1.first && from.second == to1.second && to.first == from1.first && to.second == from1.second) ||
            (from.first == to2.first && from.second == to2.second && to.first == from2.first && to.second == from2.second))
                return true
        return false
    }
    val allString = listOf(from1.first, from2.first, to1.first, to2.first)
    val allColumns = listOf(from1.second, from2.second, to1.second, to2.second)
 }
@Serializable
data class Field(val width: Int, val height: Int, val position: Position, val opponentPosition: Position, val barriers: Set<Obstacle>)
@Serializable
data class Stats(val name: String, val points: Int)
@Serializable
data class JoinLobbyResponse(val DATA: LobbyInfo, val SUCCESS: Boolean)