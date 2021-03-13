import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.sql.*
import java.util.*
import kotlin.random.Random
import kotlin.system.exitProcess
//Класс сервера

const val MAX_TURNS = 60

class Server private constructor(argsParser: ArgsParser){

     companion object {
        private var srv: Server? = null
        fun getInstance(argsParser: ArgsParser): Server {
            if (srv == null)
                srv = Server(argsParser)
            return srv as Server
        }
    }

    inner class ConnectedClient(private val socket: Socket){ //Класс подключенного клиента

        val communicator: Communicator = Communicator(socket)
        var name: String? = null
        var id = 0
        var myLobby : Lobby? = null

        init{
            communicator.addDataReceivedListener(::dataReceived)
            communicator.start()
        }

        private fun dataReceived(data: String){
            //Формат сообщений:  команда=данные
            val vls = data.split(Regex("(?<=[A-Z]) (?=\\{.+\\})"),limit = 2)
            println("Command: ${vls[0]}, data:")
            if (vls.isNotEmpty()){
                when (vls[0]){
                    "CONNECTION" -> login(vls[1])
                    "DISCONNECT" -> disconnect()
                    "GET LOBBY" -> getLobby()
                    "POST LOBBY" -> postLobby(vls[1])
                    "GET RANDOMLOBBY" -> getRandomLobby()
                    "SOCKET JOINLOBBY" -> joinLobby(vls[1])
//                    "SOCKET STEP" -> myLobby?.makeStep(this, vls[1])
                    "SOCKET LEAVELOBBY" -> {
                        myLobby?.removePLayer(this)
                        communicator.sendData(Json.encodeToString(Message("OK")))
                    }
                    "GET STATS" -> getStats()
                    else -> { println("Unknown command: ${vls[0]} from ${socket.inetAddress}") }
                }
            }
        }

        private fun login(data: String) {
            val loginInfo = Json.decodeFromString<LoginInfo>(data)
            val rs = stmt.executeQuery("SELECT * FROM ${dbName}.user WHERE login='${loginInfo.LOGIN}'")
            if (rs.next()) {
                name = rs.getString("login")
                communicator.sendData(Json.encodeToString(Message("LOGIN OK")))
            }
            else {
                communicator.sendData(Json.encodeToString(Message("LOGIN FAILED")))
            }
        }

        private fun disconnect() {
            communicator.sendData(Json.encodeToString(Message("BYE")))
            communicator.stop()
            println("User [${socket.inetAddress}:${socket.port}] disconnected")
        }

        private fun getLobby() {
            println("getting lobby")
            val rs = stmt.executeQuery("SELECT * FROM lobbies")
            val lobbies = mutableListOf<LobbyInfo>()
            while (rs.next()) {
                val lobbyInfo = LobbyInfo(
                    rs.getString("ID"),
                    rs.getInt("width"),
                    rs.getInt("height"),
                    rs.getInt("gameBarrierCount"),
                    rs.getInt("playerBarrierCount"),
                    rs.getString("name"),
                    rs.getInt("playersCount"))
                lobbies.add(lobbyInfo)
            }
            val getLobbyResponse = GetLobbyResponse(lobbies, lobbies.isNotEmpty())
            communicator.sendData(Json.encodeToString(getLobbyResponse))
        }

        private fun postLobby(data: String) {
            val lobbyInfo = Json.decodeFromString<LobbyInfo>(data)
//            val rs = stmt.executeQuery("""
//                INSERT INTO lobbies (width, height, gameBarrierCount, playerBarrierCount, name, playersCount)
//                VALUES (${lobbyInfo.players_count}, ${lobbyInfo.height},${lobbyInfo.gameBarrierCount},${lobbyInfo.playerBarrierCount},${lobbyInfo.name}),${lobbyInfo.players_count} """)
            val rs = connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE).executeQuery(
                "SELECT * FROM lobbies"
            )
            with(rs){
                moveToInsertRow()
                updateInt("width", lobbyInfo.width)
                updateInt("height", lobbyInfo.height)
                updateInt("gameBarrierCount", lobbyInfo.gameBarrierCount)
                updateInt("playerBarrierCount", lobbyInfo.playerBarrierCount)
                updateString("name", lobbyInfo.name)
                updateInt("playersCount", lobbyInfo.players_count)
                try {
                    insertRow()
                }
                catch (ex: SQLIntegrityConstraintViolationException) {
                    println("Попытка создать лобби с уже существующим именем, ошибка")
                }
            }
            val rss = stmt.executeQuery("SELECT ID FROM lobbies WHERE name='${lobbyInfo.name}'")
            if (rss.next()){
                communicator.sendData(Json.encodeToString(LobbyID(rss.getInt("ID").toString())))
                lobbies[rss.getInt("ID")] = Lobby(lobbyInfo)
            }
            else {
                communicator.sendData(Json.encodeToString(Message("POST LOBBY FAILED")))
            }
        }

        private fun getRandomLobby() {
            val rs = stmt.executeQuery("SELECT ID FROM lobbies")
            rs.last()
            val size = rs.row
            var number = Random(System.currentTimeMillis()).nextInt(1,size)
            rs.beforeFirst()
            while (number >= 0) {
                rs.next()
                number -= 1
            }
            communicator.sendData(Json.encodeToString(LobbyID(rs.getInt("ID").toString())))
        }

        private fun joinLobby(data: String) {
            val lobbyID = Json.decodeFromString<LobbyID>(data)
            val rs = connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE).executeQuery("SELECT * FROM lobbies WHERE ID = '${lobbyID.id}'")
            if(rs.next()){
                val lobbyInfo = LobbyInfo(
                    rs.getString("ID"),
                    rs.getInt("width"),
                    rs.getInt("height"),
                    rs.getInt("gameBarrierCount"),
                    rs.getInt("playerBarrierCount"),
                    rs.getString("name"),
                    rs.getInt("playersCount")
                )
                if (!lobbies[rs.getInt("ID")]?.isPlaying!!) {
                    communicator.sendData(Json.encodeToString(JoinLobbyResponse(lobbyInfo, true)))
                    lobbies[rs.getInt("ID")]?.addPlayer(this)
                }
                else{
                    communicator.sendData(Json.encodeToString(JoinLobbyResponse(LobbyInfo(lobbyID.id, -1, -1, -1, -1, "", -1), false)))
                }
            }
            else {
                communicator.sendData(Json.encodeToString(JoinLobbyResponse(LobbyInfo(null, -1, -1, -1, -1, "", -1), false)))
            }
        }

        private fun getStats() {

        }

    }

    inner class Lobby(private val lobbyInfo: LobbyInfo) {
        private var expectingPlayer: ConnectedClient? = null
        var isPlaying = false

        fun addPlayer(player : ConnectedClient) {
            if (expectingPlayer == null) {
                expectingPlayer = player
                expectingPlayer?.myLobby = this
            }
            else {
                isPlaying = true
                GlobalScope.launch {
                    playGame(expectingPlayer!!, player, lobbyInfo)
                }
            }
        }

        fun removePLayer(player: ConnectedClient) {
            if (player == expectingPlayer) {
                expectingClient = null
            }
        }
    }

    private val connectedClient = mutableListOf<ConnectedClient>() //список подключенных клиентов (онлайн)
    private val lobbies: MutableMap<Int, Lobby> = mutableMapOf()
    private var expectingClient: ConnectedClient? = null
    private val communicationProcess : Job
    private val serverSocket: ServerSocket
    private val connection : Connection//соединение с mysql
    private val port: Int = argsParser.serverPort
    private val host = argsParser.mariaAddress
    private val dbPort = argsParser.mariaPort //порт, на котором напущена mariaDB
    private val dbName = argsParser.dbName //название БД в mariaDB
    private val stmt: Statement
    private var stop = false

    init{
        serverSocket = ServerSocket(port)
        val login : String
        val psw : String
        if (argsParser.loginFromFile) {
            login = argsParser.dbLogin
            psw = argsParser.dbPassword
        }
        else {
            print("Login: ")
            login = readLine() ?: argsParser.dbLogin
            print("Password: ")
            psw = readLine()  ?: argsParser.dbPassword
        }
        val connectionProperties = Properties()
        connectionProperties["user"] = login
        connectionProperties["password"] = psw
        connectionProperties["serverTimezone"] = "UTC"
        connectionProperties["autoReconnect"] = true
        try {
            connection = DriverManager.getConnection("jdbc:mariadb://$host:$dbPort/$dbName", connectionProperties)
        }
        catch (ex: SQLException) {
            ex.printStackTrace()
            println("Не найдена база данных. Дальнейшная работа невозможна.")
            exitProcess(1)
        }
        stmt = connection.createStatement()
        println("SERVER STARTED")
        println("For exit type \"exit\"")

        communicationProcess = GlobalScope.launch {
            try {
                while (!stop) {
                    acceptClient()
                }
            }
            catch (ex:SocketException) {
                println("SERVER STOPPED")
            }
            finally {
                connectedClient.forEach {
                    it.communicator.sendData(Json.encodeToString(Message("BYE")))
                    it.communicator.stop()
                    println("${it.name} have been disconnected")
                }
            }
        }

        GlobalScope.launch {
            while (!stop) {
                val msg = readLine()
                if(msg == "exit") {
                    stop = true
                    serverSocket.close()
                }
                else println("For exit type \"exit\"")
            }
        }
        runBlocking {
            communicationProcess.join()
        }
    }

    private fun acceptClient() {
        println("Ожидание подключения")
        val s = serverSocket.accept()
        println("Новый клиент подключен [${s.inetAddress}:${s.port}]")
        connectedClient.add(ConnectedClient(s))
    }

    private suspend fun playGame(player1: ConnectedClient, player2: ConnectedClient, lobbyInfo: LobbyInfo) {
        val random = Random(System.currentTimeMillis())
        val (first, second) = if (random.nextBoolean()) {
            Pair(player1, player2)
        }
        else {
            Pair(player2, player1)
        }
        val sql = when(Game(first, second, lobbyInfo).startGame()) {
            GameEndings.FIRST ->
            {"INSERT INTO ${dbName}.game_results (`first`, `second`, `result`) " +
                    "VALUES ('${first.id}', '${second.id}', 'win')"}
            GameEndings.SECOND ->
            {"INSERT INTO ${dbName}`game_results` (`first`, `second`, `result`) " +
                    "VALUES ('${first.id}', '${second.id}', 'lost')"}
            GameEndings.DRAW ->
            {"INSERT INTO ${dbName}`game_results` (`first`, `second`, `result`) " +
                    "VALUES ('${first.id}', '${second.id}', 'draw')"}
        }
        stmt.executeQuery(sql)
    }
}