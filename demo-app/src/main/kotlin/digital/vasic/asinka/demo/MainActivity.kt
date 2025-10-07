package digital.vasic.asinka.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import digital.vasic.asinka.AsinkaClient
import digital.vasic.asinka.AsinkaConfig
import digital.vasic.asinka.discovery.DiscoveryEvent
import digital.vasic.asinka.events.AsinkaEvent
import digital.vasic.asinka.events.AsinkaEventReceiver
import digital.vasic.asinka.events.EventPriority
import digital.vasic.asinka.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var asinkaClient: AsinkaClient
    private val _discoveredServices = MutableStateFlow<List<String>>(emptyList())
    private val _syncedObjects = MutableStateFlow<List<SyncableObjectData>>(emptyList())
    private val _receivedEvents = MutableStateFlow<List<AsinkaEvent>>(emptyList())
    private val _connectionStatus = MutableStateFlow("Disconnected")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = AsinkaConfig(
            appId = "digital.vasic.asinka.demo",
            appName = "Asinka Demo",
            appVersion = "1.0.0",
            serverPort = 8888,
            exposedSchemas = listOf(
                ObjectSchema(
                    objectType = "Task",
                    version = "1.0",
                    fields = listOf(
                        FieldSchema("title", FieldType.STRING),
                        FieldSchema("description", FieldType.STRING, nullable = true),
                        FieldSchema("completed", FieldType.BOOLEAN)
                    )
                )
            )
        )

        asinkaClient = AsinkaClient.create(this, config)

        val eventReceiver = object : AsinkaEventReceiver() {
            override suspend fun handleEvent(event: AsinkaEvent) {
                _receivedEvents.value = _receivedEvents.value + event
            }
        }
        lifecycleScope.launch {
            asinkaClient.eventManager.registerEventReceiver(eventReceiver)
        }

        lifecycleScope.launch {
            asinkaClient.start()
            _connectionStatus.value = "Server Started"

            asinkaClient.syncManager.observeAllChanges().collect { change ->
                when (change) {
                    is digital.vasic.asinka.sync.SyncChange.Updated -> {
                        val data = change.obj as? SyncableObjectData
                        data?.let {
                            val current = _syncedObjects.value.toMutableList()
                            val index = current.indexOfFirst { it.objectId == data.objectId }
                            if (index != -1) {
                                current[index] = data
                            } else {
                                current.add(data)
                            }
                            _syncedObjects.value = current
                        }
                    }
                    is digital.vasic.asinka.sync.SyncChange.Deleted -> {
                        _syncedObjects.value = _syncedObjects.value.filter {
                            it.objectId != change.objectId
                        }
                    }
                }
            }
        }

        setContent {
            AsinkaDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        discoveredServices = _discoveredServices,
                        syncedObjects = _syncedObjects,
                        receivedEvents = _receivedEvents,
                        connectionStatus = _connectionStatus,
                        onStartDiscovery = { startDiscovery() },
                        onCreateTask = { title -> createTask(title) },
                        onSendEvent = { message -> sendEvent(message) }
                    )
                }
            }
        }
    }

    private fun startDiscovery() {
        lifecycleScope.launch {
            asinkaClient.discoveryManager.startDiscovery().collect { event ->
                when (event) {
                    is DiscoveryEvent.ServiceFound -> {
                        val serviceName = event.serviceInfo.serviceName
                        _discoveredServices.value = _discoveredServices.value + serviceName
                        asinkaClient.connect(event.serviceInfo.host, event.serviceInfo.port)
                        _connectionStatus.value = "Connected to $serviceName"
                    }
                    is DiscoveryEvent.ServiceLost -> {
                        _discoveredServices.value = _discoveredServices.value.filter { it != event.serviceName }
                    }
                    is DiscoveryEvent.Error -> {
                        _connectionStatus.value = "Error: ${event.message}"
                    }
                }
            }
        }
    }

    private fun createTask(title: String) {
        lifecycleScope.launch {
            val task = SyncableObjectData(
                objectId = "task-${System.currentTimeMillis()}",
                objectType = "Task",
                version = 1,
                fields = mutableMapOf(
                    "title" to title,
                    "description" to "Created from demo app",
                    "completed" to false
                )
            )
            asinkaClient.syncManager.registerObject(task)
        }
    }

    private fun sendEvent(message: String) {
        lifecycleScope.launch {
            val event = AsinkaEvent(
                eventType = "demo_message",
                data = mapOf("message" to message),
                priority = EventPriority.NORMAL
            )
            asinkaClient.eventManager.sendEvent(event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            asinkaClient.stop()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    discoveredServices: StateFlow<List<String>>,
    syncedObjects: StateFlow<List<SyncableObjectData>>,
    receivedEvents: StateFlow<List<AsinkaEvent>>,
    connectionStatus: StateFlow<String>,
    onStartDiscovery: () -> Unit,
    onCreateTask: (String) -> Unit,
    onSendEvent: (String) -> Unit
) {
    val services by discoveredServices.collectAsState()
    val objects by syncedObjects.collectAsState()
    val events by receivedEvents.collectAsState()
    val status by connectionStatus.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var taskTitle by remember { mutableStateOf("") }
    var eventMessage by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Asinka Demo (Асинка)") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (status.contains("Connected") || status.contains("Started"))
                            Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (status.contains("Connected") || status.contains("Started"))
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("Discovery", modifier = Modifier.padding(16.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("Objects", modifier = Modifier.padding(16.dp))
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text("Events", modifier = Modifier.padding(16.dp))
                }
            }

            when (selectedTab) {
                0 -> DiscoveryTab(services, onStartDiscovery)
                1 -> ObjectsTab(objects, taskTitle, { taskTitle = it }, onCreateTask)
                2 -> EventsTab(events, eventMessage, { eventMessage = it }, onSendEvent)
            }
        }
    }
}

@Composable
fun DiscoveryTab(services: List<String>, onStartDiscovery: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Button(
            onClick = onStartDiscovery,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Discovery")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Discovered Services:", style = MaterialTheme.typography.titleMedium)

        LazyColumn {
            items(services) { service ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = service,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun ObjectsTab(
    objects: List<SyncableObjectData>,
    taskTitle: String,
    onTitleChange: (String) -> Unit,
    onCreateTask: (String) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = taskTitle,
            onValueChange = onTitleChange,
            label = { Text("Task Title") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (taskTitle.isNotBlank()) {
                    onCreateTask(taskTitle)
                    onTitleChange("")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Task")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Synced Objects (${objects.size}):", style = MaterialTheme.typography.titleMedium)

        LazyColumn {
            items(objects) { obj ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("${obj.fields["title"]}", style = MaterialTheme.typography.titleSmall)
                        Text("ID: ${obj.objectId}", style = MaterialTheme.typography.bodySmall)
                        Text("Type: ${obj.objectType} v${obj.version}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun EventsTab(
    events: List<AsinkaEvent>,
    message: String,
    onMessageChange: (String) -> Unit,
    onSendEvent: (String) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = message,
            onValueChange = onMessageChange,
            label = { Text("Event Message") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (message.isNotBlank()) {
                    onSendEvent(message)
                    onMessageChange("")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Send, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Send Event")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Received Events (${events.size}):", style = MaterialTheme.typography.titleMedium)

        LazyColumn {
            items(events) { event ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Type: ${event.eventType}", style = MaterialTheme.typography.titleSmall)
                        Text("Message: ${event.data["message"]}", style = MaterialTheme.typography.bodyMedium)
                        Text("Priority: ${event.priority}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun AsinkaDemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(),
        content = content
    )
}