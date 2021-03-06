import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
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
    private var channel = Channel<String>(1)

    suspend fun startGame(): GameEndings {
        log += "Игра между ${player1.name} и ${player2.name}\n"
        println("Игра между ${player1.name} и ${player2.name}")
        player1.communicator.addDataReceivedListener(::sendTurn)
        player2.communicator.addDataReceivedListener(::sendTurn)
        player1.communicator.sendData("SOCKET STARTGAME " + Json.encodeToString(StartGameInfo(true,field.width,field.height,field.position.toList(),field.opponentPosition.toList(),field.barriers)))
        player2.communicator.sendData("SOCKET STARTGAME " + Json.encodeToString(StartGameInfo(false,field.width,field.height,field.opponentPosition.toList(),field.position.toList(),field.barriers)))
        val game = coroutineScope {
            async { playGame() }
        }
        log("Game ended!")
        return game.await()
    }

    private suspend fun sendTurn(data : String) {
        channel.send(data)
        println("Sent: $data")
    }

    private suspend fun playGame() : GameEndings {
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
            player1.communicator.removeDataReceivedListener(::sendTurn)
            player2.communicator.removeDataReceivedListener(::sendTurn)
        }
        catch (ex: WinByTimeout) {
            when(ex.gameEndings) {
                GameEndings.FIRST -> {
                    log += "Победил ${player1.name} так как игрок ${player2.name} не ответил вовремя\n"
                    sendResults(player1, player2)
                    writeLog()
                }
                GameEndings.SECOND -> {
                    log += "Победил ${player2.name} так как игрок ${player1.name} не ответил вовремя\n"
                    sendResults(player2, player1)
                    writeLog()
                }
                GameEndings.DRAW -> Unit
            }
            writeLog()
            return ex.gameEndings
        }
        with(field) {
            if(position[0] == height - 1) {
                log += "Победил ${player1.name}\n"
                sendResults(player1, player2)
                writeLog()
                return GameEndings.FIRST
            }
            else if(opponentPosition[0] == 0) {
                log += "Победил ${player2.name}\n"
                sendResults(player2, player1)
                writeLog()
                return GameEndings.SECOND
            }
        }
        log += "Ничья\n"
        val endGameInfo = EndGameInfo("draw", field.width, field.height, field.position, field.opponentPosition, field.barriers)
        player1.communicator.sendData("SOCKET ENDGAME " + Json.encodeToString(endGameInfo))
        player2.communicator.sendData("SOCKET ENDGAME " + Json.encodeToString(endGameInfo))
        writeLog()
        return GameEndings.DRAW
    }

    private fun sendResults(winner: Server.ConnectedClient, loser: Server.ConnectedClient) {
        val endGameInfo = EndGameInfo("win", field.width, field.height, field.position, field.opponentPosition, field.barriers)
        winner.communicator.sendData("SOCKET ENDGAME " + Json.encodeToString(endGameInfo))
        endGameInfo.result = "lose"
        loser.communicator.sendData("SOCKET ENDGAME " + Json.encodeToString(endGameInfo))
    }

    private suspend fun makeTurn(first: Server.ConnectedClient, second: Server.ConnectedClient) : Boolean {
        log += if (first == player1) {
            Json.encodeToString(field)
        } else Json.encodeToString(swapPositions())
        log += "\n"
        val playersTurn = CoroutineScope(currentCoroutineContext()).async {
            withTimeoutOrNull(120000) {
                channel.receive()
            }
        }
        val turnResult = playersTurn.await() ?: throw WinByTimeout(if (first == player1) GameEndings.SECOND else GameEndings.FIRST)
        field = Json.decodeFromString(turnResult.removePrefix("SOCKET STEP "))
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
        val file = File("logs/${player1.name}_vs_${player2.name}_${Calendar.getInstance().time}")
        file.writeText(log)
    }

    private fun isEnded(whoseTurn: Server.ConnectedClient) : Boolean {
        if (turns >= MAX_TURNS) return true
        val player1Position = if (whoseTurn == player1) field.position else field.opponentPosition
        val player2Position = if (whoseTurn == player1) field.opponentPosition else field.position
        if(player1Position[0] == field.height - 1 || player2Position[0] == 0) return true
        return false
    }

    private fun generateRandomField() : Field {
        log("Generating field with width ${lobbyInfo.width}, height ${lobbyInfo.height} and ${lobbyInfo.gameBarrierCount} barriers")
        val random = Random(System.currentTimeMillis())
        val position1 = Pair(0, random.nextInt(0, lobbyInfo.width - 1))
        val position2 = Pair(lobbyInfo.height - 1, random.nextInt(0 , lobbyInfo.width) - 1)
        val barriers = mutableSetOf<Obstacle>()
        while (barriers.size != lobbyInfo.gameBarrierCount) {
            val x1 = random.nextInt(0, lobbyInfo.height - 1)
            val y1 = random.nextInt(0, lobbyInfo.width - 1)
            val newObstacle = randomObstacleFromCell(Position(x1, y1), random.nextInt(0,8))
            if(!isLegalObstacle(newObstacle,barriers)) continue
            val tempSet = mutableListOf<Obstacle>()
            tempSet.addAll(barriers)
            tempSet.add(newObstacle)
            if(!(isPathExists(position1, tempSet) && isPathExists(position2, tempSet))) continue
            barriers.add(newObstacle)
        }
        val newBarrier = barriers.map { it.toCast() }
        return Field(lobbyInfo.width, lobbyInfo.height, position1.toList(), position2.toList(), newBarrier.toSet())
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

    private fun isPathExists(position: Position, barriers: Iterable<Obstacle>) : Boolean {
        val goal = if(position.first == 1) lobbyInfo.height else 1
        val queue = LinkedList(mutableListOf(position))
        val visitedCells = mutableListOf(position)
        while (!queue.isEmpty()) {
            val current = queue.pop()
            expandMoves(current, barriers).forEach {
                if (it.first == goal) return true
                if (it != current && !visitedCells.contains(it)) {
                queue.push(it)
                visitedCells.add(it)
            }}
        }
        return false
    }

    private fun expandMoves(position: Position, barriers: Iterable<Obstacle>) : List<Position> {
        val moves = listOf(Position(position.first + 1, position.second), 
            Position(position.first, position.second + 1), 
            Position(position.first - 1, position.second), 
            Position(position.first, position.second - 1))
        return moves.filter { isLegalMove(position, it, barriers) }
    }

    private fun isLegalMove(currentPosition: Position, desiredPosition: Position, barriers: Iterable<Obstacle>) : Boolean {
        if (desiredPosition.first <= 0 || desiredPosition.first > lobbyInfo.height || desiredPosition.second <= 0 ||
                desiredPosition.second > lobbyInfo.width) return false
        if (barriers.any { it.stepOverObstacle(currentPosition, desiredPosition) }) return false
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