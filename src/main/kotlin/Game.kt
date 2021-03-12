import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.*
import kotlin.random.Random

enum class GameEndings{
    FIRST,
    SECOND,
    DRAW
}
enum class ObstacleDirection{
    HTL, HTR, HBL, HBR, VTL, VTR, VBL, VBR
}

class WinByTimeout(val gameEndings: GameEndings): Exception()

class Game(private val player1: Server.ConnectedClient,
           private val player2: Server.ConnectedClient,
           private val lobbyInfo: LobbyInfo) {

    private var field = generateRandomField()
    private var turns = 0
    private var log = ""

    suspend fun startGame(): GameEndings {
//        player1.communicator.addDataReceivedListener(::gameConversation)
//        player2.communicator.addDataReceivedListener(::gameConversation)
        log += "Игра между ${player1.name} и ${player2.name}"
        player1.communicator.sendData(Json.encodeToString(StartGameInfo(true,field.width,field.height,field.position.toList())))
        player2.communicator.sendData(Json.encodeToString(StartGameInfo(false,field.width,field.height,field.opponentPosition.toList())))
        val game = coroutineScope {
            async { playGame() }
        }
        return game.await()
    }

    private fun playGame() : GameEndings {
        var first = player1
        var second = player2
        var stillPlaying = true
        try {
            while (stillPlaying) {
                stillPlaying = makeTurn(first, second)
                val tmp = first
                first = second
                second = tmp
            }
        }
        catch (ex: WinByTimeout) {
            when(ex.gameEndings) {
                GameEndings.FIRST -> log += "Победил ${player1.name} так как игрок ${player2.name} не ответил вовремя"
                GameEndings.SECOND -> log += "Победил ${player2.name} так как игрок ${player1.name} не ответил вовремя"
                GameEndings.DRAW -> Unit
            }
            writeLog()
            return ex.gameEndings
        }
        with(field) {
            if(position.first == height - 1) {
                log += "Победил ${player1.name}"
                writeLog()
                return GameEndings.FIRST
            }
            else if(opponentPosition.first == 1) {
                log += "Победил ${player2.name}"
                writeLog()
                return GameEndings.SECOND
            }
        }
        log += "Ничья"
        writeLog()
        return GameEndings.DRAW
    }

    private fun makeTurn(first: Server.ConnectedClient, second: Server.ConnectedClient) : Boolean {
        log += if (first == player1) {
            Json.encodeToString(field)
        } else Json.encodeToString(swapPositions())

        val playersTurn = runBlocking {
            withTimeoutOrNull(120000) {
                runInterruptible {
                    val br = BufferedReader(InputStreamReader(first.communicator.socket.getInputStream()))
                    br.readLine()
                }
            }
        } ?: throw WinByTimeout(if (first == player1) GameEndings.SECOND else GameEndings.FIRST)
        field = Json.decodeFromString(playersTurn.removePrefix("SOCKET STEP "))
        if(isEnded(first)) return false
        field = swapPositions()
        second.communicator.sendData("SOCKET STEP " + Json.encodeToString(field))
        turns += 1
        return true
    }

    private fun swapPositions() : Field {
        with(field) {
            return Field(width, height, opponentPosition,position, barriers)
        }
    }

    private fun writeLog() {
        val file = File("logs/${player1.name}_vs_${player2.name}_${Calendar.getInstance()}")
        file.writeText(log)
    }

    private fun isEnded(whoseTurn: Server.ConnectedClient) : Boolean {
        if (turns >= MAX_TURNS) return true
        val player1Position = if (whoseTurn == player1) field.position else field.opponentPosition
        val player2Position = if (whoseTurn == player1) field.opponentPosition else field.position
        if(player1Position.first == field.height || player2Position.first == 1) return true
        return false
    }

    private fun generateRandomField() : Field {
        val random = Random(System.currentTimeMillis())
        val position1 = Pair(1, random.nextInt(1, lobbyInfo.width))
        val position2 = Pair(lobbyInfo.height, random.nextInt(1 , lobbyInfo.width))
        val barriers = mutableSetOf<Obstacle>()
        while (barriers.size != lobbyInfo.gameBarrierCount) {
            val x1 = random.nextInt(1, lobbyInfo.height)
            val y1 = random.nextInt(1, lobbyInfo.width)
            val newObstacle = randomObstacleFromCell(Position(x1, y1), random.nextInt(1,8))
            if(!isLegalObstacle(newObstacle,barriers)) continue
            if(!(isPathExists(position1) && isPathExists(position2))) continue
            barriers.add(newObstacle)
        }
        return Field(lobbyInfo.width, lobbyInfo.height, position1, position2, barriers)
    }

    private fun randomObstacleFromCell(position: Position, random: Int) : Obstacle {
        val (x1, y1) = position
        return Obstacle(
            when(ObstacleDirection.values()[random]){
                ObstacleDirection.HTL -> listOf(Position(x1, y1), Position(x1 + 1, y1), Position(x1, y1 - 1), Position(x1 + 1, y1 - 1))
                ObstacleDirection.HTR -> listOf(Position(x1, y1), Position(x1 + 1, y1), Position(x1, y1 + 1), Position(x1 + 1, y1 + 1))
                ObstacleDirection.HBL -> listOf(Position(x1, y1), Position(x1 - 1, y1), Position(x1, y1 - 1), Position(x1 - 1, y1 - 1))
                ObstacleDirection.HBR -> listOf(Position(x1, y1), Position(x1 - 1, y1), Position(x1, y1 + 1), Position(x1 - 1, y1 + 1))
                ObstacleDirection.VTL -> listOf(Position(x1, y1), Position(x1, y1 - 1), Position(x1 + 1, y1), Position(x1 + 1, y1 - 1))
                ObstacleDirection.VTR -> listOf(Position(x1, y1), Position(x1, y1 + 1), Position(x1 + 1, y1), Position(x1 + 1, y1 + 1))
                ObstacleDirection.VBL -> listOf(Position(x1, y1), Position(x1, y1 - 1), Position(x1 - 1, y1), Position(x1 - 1, y1 - 1))
                ObstacleDirection.VBR -> listOf(Position(x1, y1), Position(x1, y1 + 1), Position(x1 - 1, y1), Position(x1 - 1, y1 + 1))
        })
    }

    private fun isPathExists(position: Position) : Boolean {
        val goal = if(position.first == 1) lobbyInfo.height else 1
        val queue = LinkedList(mutableListOf(position))
        val visitedCells = mutableListOf(position)
        while (!queue.isEmpty()) {
            val current = queue.pop()
            expandMoves(current).forEach {
                if (it.first == goal) return true
                if (it != current && !visitedCells.contains(it)) {
                queue.push(it)
                visitedCells.add(it)
            }}
        }
        return false
    }

    private fun expandMoves(position: Position) : List<Position> {
        val moves = listOf(Position(position.first + 1, position.second), 
            Position(position.first, position.second + 1), 
            Position(position.first - 1, position.second), 
            Position(position.first, position.second - 1))
        return moves.filter { isLegalMove(position, it) }
    }

    private fun isLegalMove(currentPosition: Position, desiredPosition: Position) : Boolean {
        if (desiredPosition.first <= 0 || desiredPosition.first > lobbyInfo.height || desiredPosition.second <= 0 ||
                desiredPosition.second > lobbyInfo.width) return false
        if (field.barriers.any { it.stepOverObstacle(currentPosition, desiredPosition) }) return false
        return true
    }

    private fun isLegalObstacle(obstacle: Obstacle, obstacleSet: Set<Obstacle>) : Boolean {
        if (obstacle.allString.any { it <= 0 || it > lobbyInfo.height}) return false
        if (obstacle.allColumns.any { it < 0  || it >lobbyInfo.width}) return false
        if (obstacleSet.any { it.stepOverObstacle(obstacle.from1, obstacle.to1) ||
                    it.stepOverObstacle(obstacle.from2, obstacle.to2)})
            return false
        return true
    }
}