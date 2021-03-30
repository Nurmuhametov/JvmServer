import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.sql.*
import java.util.*
import kotlin.collections.ArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.system.exitProcess
//Класс сервера

const val MAX_TURNS = 60

fun log(msg : String) {
    println("[${Thread.currentThread().name}] $msg")
}

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
        var myLobby : Lobby? = null

        init{
            communicator.addDataReceivedListener(::dataReceived)
            communicator.start(communicatorsScope)
        }

        private fun dataReceived(data: String){
            val vls = data.split(Regex("(?<=[A-Z]) (?=\\{.+})"),limit = 2)
            if (vls.isNotEmpty()){
                println(data)
                when (vls[0]){
                    "CONNECTION" -> login(vls[1])
                    "DISCONNECT" -> disconnect()
                    "GET LOBBY" -> getLobby()
                    "POST LOBBY" -> postLobby(vls[1])
                    "GET RANDOMLOBBY" -> getRandomLobby()
                    "SOCKET JOINLOBBY" -> joinLobby(vls[1])
                    "SOCKET LEAVELOBBY" -> {
                        myLobby?.removePLayer(this)
                        communicator.sendData(Json.encodeToString(Message("OK")))
                    }
                    "GET STATS" -> getStats()
                    else -> {  }
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
            myLobby?.removePLayer(this)
            communicator.sendData(Json.encodeToString(Message("BYE")))
            communicator.stop()
            val id = if (name==null) "[" + socket.inetAddress.toString() + socket.port.toString() + "]"
            else name
            println("User $id disconnected")
            connectedClient.remove(this)
        }

        private fun getLobby() {
            if (name == null) {
                communicator.sendData(Json.encodeToString(Message("LOGIN FIRST")))
                return
            }
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
            if (name == null) {
                communicator.sendData(Json.encodeToString(Message("LOGIN FIRST")))
                return
            }
            val lobbyInfo = Json.decodeFromString<LobbyInfo>(data)
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
            updateLobbies()
        }

        private fun getRandomLobby() {
            if (name == null) {
                communicator.sendData(Json.encodeToString(Message("LOGIN FIRST")))
                return
            }
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
            if (name == null) {
                communicator.sendData(Json.encodeToString(Message("LOGIN FIRST")))
                return
            }
            if (myLobby != null) {
                myLobby?.removePLayer(this)
                //communicator.sendData(Json.encodeToString(Message("YOU LEFT PREVIOUS LOBBY")))
            }
            val lobbyID = Json.decodeFromString<LobbyID>(data)
            if (lobbyID.id != null) {
                communicator.sendData(Json.encodeToString(JoinLobbyResponse(LobbyInfo(null, -1, -1, -1, -1, "", -1), false)))
            }
            else {
                //TODO("Расписание матчей")
                val opponent = Schedule.sheet[name]?.get(0) ?: throw Exception("BlaBlaBla")
                val rs = stmt.executeQuery("SELECT * FROM lobbies WHERE name LIKE '%$name%' AND name LIKE '%$opponent%'")
                if(rs.next()) {
                    val lobbyInfo = LobbyInfo(
                        rs.getInt("ID").toString(),
                        rs.getInt("width"),
                        rs.getInt("height"),
                        rs.getInt("gameBarrierCount"),
                        rs.getInt("playerBarrierCount"),
                        rs.getString("name"),
                        rs.getInt("playersCount")
                    )
                    println("Player $name should go to ${lobbyInfo.name} lobby")
                    if (!lobbies[rs.getInt("ID")]?.isPlaying!!) {
                        communicator.sendData(Json.encodeToString(JoinLobbyResponse(lobbyInfo, true)))
                        lobbies[rs.getInt("ID")]?.addPlayer(this)
                    }
                }
            }
//            val lobbyID = Json.decodeFromString<LobbyID>(data)
//            val rs = connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE).executeQuery("SELECT * FROM lobbies WHERE ID = '${lobbyID.id}'")
//            if(rs.next()){
//                val lobbyInfo = LobbyInfo(
//                    rs.getInt("ID").toString(),
//                    rs.getInt("width"),
//                    rs.getInt("height"),
//                    rs.getInt("gameBarrierCount"),
//                    rs.getInt("playerBarrierCount"),
//                    rs.getString("name"),
//                    rs.getInt("playersCount")
//                )
//                if (!lobbies[rs.getInt("ID")]?.isPlaying!!) {
//                    communicator.sendData(Json.encodeToString(JoinLobbyResponse(lobbyInfo, true)))
//                    lobbies[rs.getInt("ID")]?.addPlayer(this)
//                }
//                else{
//                    communicator.sendData(Json.encodeToString(JoinLobbyResponse(LobbyInfo(lobbyID.id, -1, -1, -1, -1, "", -1), false)))
//                }
//            }
//            else {
//                communicator.sendData(Json.encodeToString(JoinLobbyResponse(LobbyInfo(null, -1, -1, -1, -1, "", -1), false)))
//            }
        }

        private fun getStats() {
            if (name == null) {
                communicator.sendData(Json.encodeToString(Message("LOGIN FIRST")))
                return
            }
            val rs = stmt.executeQuery("SELECT * FROM stats WHERE login='$name'")
            val statistic = mutableListOf<Stats>()
            while (rs.next()) {
                statistic.add(Stats(rs.getString("opponent"), rs.getInt("points")))
            }
            communicator.sendData(Json.encodeToString(statistic))
        }
    }

    inner class Lobby(private val lobbyInfo: LobbyInfo) {
        private var expectingPlayer: ConnectedClient? = null
        var isPlaying = false

        fun addPlayer(player : ConnectedClient) {
            println("player ${player.name} joined the lobby ${lobbyInfo.name}")
            if (expectingPlayer == null) {
                expectingPlayer = player
                expectingPlayer?.myLobby = this
            }
            else {
                isPlaying = true
                log("COROUTINE STARTING")
                Schedule.sheet[player.name]?.removeAt(0)
                Schedule.sheet[expectingPlayer!!.name]?.removeAt(0)
                stmt.execute("DELETE FROM lobbies WHERE ID = '${lobbyInfo._id}'")
                gamesScope.launch(Dispatchers.Unconfined) { playGame(expectingPlayer!!, player, lobbyInfo) }
                removePLayer(player)
                if (expectingPlayer!=null)
                    removePLayer(expectingPlayer!!)
                lobbies.remove(lobbyInfo._id?.toInt())
                updateLobbies()
            }
        }

        fun removePLayer(player: ConnectedClient) {
            if (player == expectingPlayer) {
                expectingPlayer = null
            }
            player.myLobby = null
        }
    }

    private val communicatorsScope = CoroutineScope(EmptyCoroutineContext)
    private val gamesScope = CoroutineScope(EmptyCoroutineContext)
    private val connectedClient = mutableListOf<ConnectedClient>() //список подключенных клиентов (онлайн)
    private val lobbies: MutableMap<Int, Lobby> = mutableMapOf()
    val communicationProcess : Job
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
        updateUsers()
        createSchedule()
        createGameResults()
        createLobbies()
        updateLobbies()

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
        GlobalScope.launch(Dispatchers.IO) {
            while (!stop) {
                val msg = readLine()
                if(msg == "exit") {
                    stop = true
                    serverSocket.close()
                }
                else println("For exit type \"exit\"")
            }
        }
    }

    private fun createGameResults() {
        try {
            stmt.execute("SELECT *  FROM game_results")
        }
        catch (ex: Exception) {
            stmt.execute("CREATE TABLE game_results ( `first` VARCHAR(20) NOT NULL , `second` VARCHAR(20) NOT NULL , `result` SET('first','second','draw') NOT NULL ) ENGINE = InnoDB;")
            stmt.execute("ALTER TABLE game_results ADD FOREIGN KEY (`first`) REFERENCES `user`(`login`) ON DELETE RESTRICT ON UPDATE RESTRICT; ALTER TABLE `game_results` ADD FOREIGN KEY (`second`) REFERENCES `user`(`login`) ON DELETE RESTRICT ON UPDATE RESTRICT;")
        }
    }

    private fun createLobbies() {
        stmt.execute("CREATE TABLE lobbies IF NOT EXISTS ( `ID` INT UNSIGNED NOT NULL AUTO_INCREMENT , `width` INT UNSIGNED NOT NULL , `height` INT UNSIGNED NOT NULL , `gameBarrierCount` INT UNSIGNED NOT NULL , `playerBarrierCount` INT UNSIGNED NOT NULL , `name` VARCHAR(100) NOT NULL , `playersCount` INT UNSIGNED NOT NULL , PRIMARY KEY (`ID`), UNIQUE `name` (`name`)) ENGINE = InnoDB")
        val random = Random(System.currentTimeMillis())
        for (userId in Schedule.users.indices) {
            for (opponentId in userId+1 until Schedule.users.size){
                repeat(Schedule.gamesToPlay) {
                    val lobbyInfo = LobbyInfo(
                        null,
                        random.nextInt(4, 10),
                        random.nextInt(4, 10),
                        random.nextInt(1, 5),
                        random.nextInt(1, 4),
                        "${Schedule.users[userId]}_vs_${Schedule.users[opponentId]}_$it",
                        2)
                    stmt.execute(
                        "INSERT INTO lobbies VALUES " +
                                "(null ," +
                                "${lobbyInfo.width}," +
                                "${lobbyInfo.height}," +
                                "${lobbyInfo.gameBarrierCount}," +
                                "${lobbyInfo.playerBarrierCount}," +
                                "'${lobbyInfo.name}'," +
                                "${lobbyInfo.players_count}) ON DUPLICATE KEY UPDATE `name` = '${lobbyInfo.name}'")
                }
            }
        }
    }

    private fun createSchedule() {
        val size = Schedule.users.size
        Schedule.users.forEach {
            Schedule.sheet[it] = ArrayList((size - 1) * Schedule.gamesToPlay)
        }
        repeat(size - 1) {
            Schedule.users.forEach {
                val index = Schedule.users.indexOf(it)
                val list = Schedule.sheet[it]
                repeat(Schedule.gamesToPlay) {
                    list?.add(Schedule.users[size - index - 1])
                }
            }
            val tmp = Schedule.users[1]
            for (j in 1 until size-1) {
                Schedule.users[j] = Schedule.users[j+1]
            }
            Schedule.users[size-1] = tmp
        }
    }

    private fun updateUsers() {
        stmt.execute("CREATE TABLE user IF NOT EXISTS ( `ID` INT UNSIGNED NOT NULL AUTO_INCREMENT , `login` VARCHAR(20) NOT NULL , PRIMARY KEY (`ID`), UNIQUE `login` (`login`)) ENGINE = InnoDB;")
        val users = File("build/resources/main/participants_list").readLines()
        users.forEach {
            stmt.execute("INSERT INTO user VALUES (null ,'$it') ON DUPLICATE KEY UPDATE `login` = '$it'")
        }
    }

    private fun updateLobbies() {
        val rs = stmt.executeQuery("SELECT * FROM lobbies")
        while(rs.next()){
            val lobbyInfo = LobbyInfo(
                rs.getInt("ID").toString(),
                rs.getInt("width"),
                rs.getInt("height"),
                rs.getInt("gameBarrierCount"),
                rs.getInt("playerBarrierCount"),
                rs.getString("name"),
                rs.getInt("playersCount")
            )
            try {
                lobbies[rs.getInt("ID")] = lobbies[rs.getInt("ID")] ?: Lobby(lobbyInfo)
            }
            catch (ex: IndexOutOfBoundsException) {
                lobbies[rs.getInt("ID")] = Lobby(lobbyInfo)
            }
        }
        println("Server has ${lobbies.size} lobbies at the time")
    }

    private fun acceptClient() {
        log("Ожидание подключения")
        val s = serverSocket.accept()
        log("Новый клиент подключен [${s.inetAddress}:${s.port}]")
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
        log("Game started")
        val sql = when(Game(first, second, lobbyInfo).startGame()) {
            GameEndings.FIRST ->
            {"INSERT INTO game_results (`first`, `second`, `result`) " +
                    "VALUES ('${first.name}', '${second.name}', 'first')"}
            GameEndings.SECOND ->
            {"INSERT INTO game_results (`first`, `second`, `result`) " +
                    "VALUES ('${first.name}', '${second.name}', 'second')"}
            GameEndings.DRAW ->
            { "INSERT INTO game_results (`first`, `second`, `result`) " +
                    "VALUES ('${first.name}', '${second.name}', 'draw')" }
        }
        stmt.executeQuery(sql)
    }
}