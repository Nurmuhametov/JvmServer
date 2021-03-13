import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.net.SocketException

class Communicator(
    var socket: Socket
) {

    private var active: Boolean
    private var communicationProcess: Job? = null
    private var dataReceivedListeners = mutableListOf<(String)->Unit>()
    private val isAlive: Boolean
        get() = active && socket.isConnected
    init {
        active = true
    }

    fun addDataReceivedListener(l: (String)->Unit){
        dataReceivedListeners.add(l)
    }
    fun removeDataReceivedListener(l: (String)->Unit){
        dataReceivedListeners.remove(l)
    }

    private fun communicate(){
        while (isAlive){
            try{
                val value = receiveData()
                if (value!=null) {
                    dataReceivedListeners.forEach {
                        it.invoke(value)
                    }
                }
            } catch (e: SocketException){
                active = false
                if (!socket.isClosed) socket.close()
            }
        }
    }

    private fun receiveData(): String? {
        return if (isAlive) {
            try {
                val br = BufferedReader(InputStreamReader(socket.getInputStream()))
                br.readLine()
            } catch (e: SocketException) {
                active = false
                null
            }
        } else null
    }

    fun sendData(data: String){
        try {
            if (isAlive){
                val pw = PrintWriter(socket.getOutputStream())
                pw.println(data)
                pw.flush()
            }
        } catch (e: SocketException){
            active = false
        }
    }

    fun start(){
        if (communicationProcess?.isActive == true)
            stop()
        active = true
        communicationProcess = GlobalScope.launch {
            communicate()
        }
    }

    fun stop(){
        try{
            active = false
            socket.close()
            if (communicationProcess?.isActive == true){
                communicationProcess?.cancel()
            }
        } catch (e: CancellationException) {
             runBlocking {
                 communicationProcess?.join()
             }
        }
        finally {
            if (!socket.isClosed) socket.close()
        }
    }
}