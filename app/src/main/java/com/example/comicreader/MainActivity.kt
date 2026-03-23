package com.example.comicreader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.comicreader.ui.theme.ComicReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The main activity of the application, serving as the entry point.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComicReaderTheme {
                val navController = rememberNavController()
                ComicApp(navController)
                
                LaunchedEffect(intent) {
                    handleIntent(intent, navController)
                }
            }
        }
    }

    /**
     * Handles incoming intents, specifically for viewing comic files.
     *
     * @param intent The [Intent] to handle.
     * @param navController The [NavHostController] used for navigation.
     */
    private fun handleIntent(intent: Intent?, navController: NavHostController) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                val encodedUri = Uri.encode(uri.toString())
                navController.navigate("reader/$encodedUri")
            }
        }
    }
}

/**
 * The main composable representing the application's navigation structure.
 *
 * @param navController The [NavHostController] for managing navigation between screens.
 */
@Composable
fun ComicApp(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onComicClick = { uri ->
                    val encodedUri = Uri.encode(uri.toString())
                    navController.navigate("reader/$encodedUri")
                }
            )
        }
        composable(
            "reader/{comicUri}",
            arguments = listOf(navArgument("comicUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val comicUriStr = backStackEntry.arguments?.getString("comicUri") ?: ""
            val comicUri = Uri.parse(comicUriStr)
            
            ReaderScreen(
                uri = comicUri,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Composable screen for displaying the comic library.
 *
 * @param onComicClick Callback invoked when a comic is clicked, passing its [Uri].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onComicClick: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("comics_prefs", Context.MODE_PRIVATE) }
    
    var comics by remember { mutableStateOf<List<Comic>>(emptyList()) }
    var isLoadingLibrary by remember { mutableStateOf(true) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showSettingsDialog by remember { mutableStateOf(false) }
    var readingMode by remember { 
        mutableStateOf(prefs.getString("reading_mode", "horizontal") ?: "horizontal") 
    }

    var viewMode by remember { mutableStateOf(LibraryViewMode.ALL_COMICS) }
    var selectedGroupTitle by remember { mutableStateOf<String?>(null) }

    /**
     * Extracts the base title from a comic name.
     */
    fun getBaseTitle(name: String): String {
        val nameWithoutExt = name.substringBeforeLast('.')
        val regex = Regex("""\s*(#\s*\d+|(?<=\s)\d+|v\d+|vol\d+|volume\d+).*$""", RegexOption.IGNORE_CASE)
        val baseTitle = nameWithoutExt.replace(regex, "").trim()
        return if (baseTitle.isEmpty()) nameWithoutExt else baseTitle
    }

    val groupedComics = remember(comics) {
        comics.groupBy { getBaseTitle(it.name) }
            .map { (title, groupComics) ->
                ComicGroup(title, groupComics.sortedWith(compareBy(NaturalOrderComparator()) { it.name }))
            }
            .sortedBy { it.title }
    }

    /**
     * Updates the library list based on the provided set of [Uri] strings.
     */
    fun updateComics(uris: Set<String>) {
        scope.launch {
            isLoadingLibrary = true
            val comicList = withContext(Dispatchers.IO) {
                uris.map { uriString ->
                    val uri = Uri.parse(uriString)
                    val name = DocumentFile.fromSingleUri(context, uri)?.name
                               ?: uri.lastPathSegment ?: "Unknown"
                    Comic(uri = uri, name = name)
                }.sortedWith(compareBy(NaturalOrderComparator()) { it.name })
            }
            comics = comicList
            isLoadingLibrary = false
        }
    }

    LaunchedEffect(Unit) {
        val savedUris = prefs.getStringSet("comic_uris", emptySet()) ?: emptySet()
        updateComics(savedUris)
    }

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    val filteredComics = remember(searchQuery, comics) {
        if (searchQuery.isEmpty()) {
            comics
        } else {
            comics.filter { it.name.contains(searchQuery, ignoreCase = true) }
                .sortedBy { it.name.indexOf(searchQuery, ignoreCase = true) }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let { treeUri ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) {}

                        val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
                        val foundUris = mutableListOf<String>()
                        pickedDir?.listFiles()?.forEach { file ->
                            val name = file.name?.lowercase() ?: ""
                            if (name.endsWith(".cbz") || name.endsWith(".zip")) {
                                foundUris.add(file.uri.toString())
                            }
                        }

                        if (foundUris.isNotEmpty()) {
                            val currentUris = prefs.getStringSet("comic_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
                            currentUris.addAll(foundUris)
                            prefs.edit().putStringSet("comic_uris", currentUris).apply()
                            updateComics(currentUris)
                        }
                    }
                }
            }
        }
    )

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Settings") },
            text = {
                Column {
                    Text("Reading Direction", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            readingMode = "horizontal"
                            prefs.edit().putString("reading_mode", "horizontal").apply()
                        }
                    ) {
                        RadioButton(
                            selected = readingMode == "horizontal",
                            onClick = { 
                                readingMode = "horizontal"
                                prefs.edit().putString("reading_mode", "horizontal").apply()
                            }
                        )
                        Text("Horizontal Sliding")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            readingMode = "vertical"
                            prefs.edit().putString("reading_mode", "vertical").apply()
                        }
                    ) {
                        RadioButton(
                            selected = readingMode == "vertical",
                            onClick = { 
                                readingMode = "vertical"
                                prefs.edit().putString("reading_mode", "vertical").apply()
                            }
                        )
                        Text("Vertical Swiping")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    BackHandler(enabled = selectedGroupTitle != null || isSearching) {
        if (isSearching) {
            isSearching = false
            searchQuery = ""
        } else {
            selectedGroupTitle = null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Comic Reader",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.headlineSmall
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("All Comics") },
                    selected = viewMode == LibraryViewMode.ALL_COMICS && selectedGroupTitle == null,
                    onClick = {
                        scope.launch { drawerState.close() }
                        viewMode = LibraryViewMode.ALL_COMICS
                        selectedGroupTitle = null
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("Series") },
                    selected = viewMode == LibraryViewMode.SERIES && selectedGroupTitle == null,
                    onClick = {
                        scope.launch { drawerState.close() }
                        viewMode = LibraryViewMode.SERIES
                        selectedGroupTitle = null
                    },
                    icon = { Icon(Icons.Default.Collections, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showSettingsDialog = true
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            topBar = { 
                if (isSearching) {
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { isSearching = false },
                        active = true,
                        onActiveChange = { 
                            if (!it) {
                                isSearching = false
                                searchQuery = ""
                            }
                        },
                        placeholder = { Text("Search your comics...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { 
                                if (searchQuery.isNotEmpty()) searchQuery = "" else isSearching = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        },
                        colors = SearchBarDefaults.colors(
                            containerColor = Color.Transparent,
                        ),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(150.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(items = filteredComics, key = { it.uri.toString() }) { comic ->
                                    ComicItem(comic = comic, onClick = {
                                        isSearching = false
                                        searchQuery = ""
                                        onComicClick(comic.uri)
                                    })
                                }
                            }
                        }
                    }
                } else {
                    CenterAlignedTopAppBar(
                        title = { 
                            Text(
                                if (selectedGroupTitle != null) selectedGroupTitle!! else if (viewMode == LibraryViewMode.SERIES) "SERIES" else "COMIC VAULT", 
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            ) 
                        },
                        navigationIcon = {
                            if (selectedGroupTitle != null) {
                                IconButton(onClick = { selectedGroupTitle = null }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            } else {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { isSearching = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { folderLauncher.launch(null) },
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "Add Folder")
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (isLoadingLibrary) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (comics.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center).padding(32.dp)) {
                        Text("Your library is empty", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Add a folder containing .cbz files", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                    }
                } else {
                    if (viewMode == LibraryViewMode.ALL_COMICS) {
                        if (filteredComics.isEmpty()) {
                            Text("No comics match your search", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(150.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(items = filteredComics, key = { it.uri.toString() }) { comic ->
                                    ComicItem(comic = comic, onClick = { onComicClick(comic.uri) })
                                }
                            }
                        }
                    } else { // SERIES view mode
                        if (selectedGroupTitle == null) {
                            val filteredGroups = groupedComics.filter { it.title.contains(searchQuery, ignoreCase = true) }
                                .sortedBy { it.title.indexOf(searchQuery, ignoreCase = true) }
                            if (filteredGroups.isEmpty()) {
                                Text("No series match your search", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(150.dp),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(items = filteredGroups, key = { it.title }) { group ->
                                        SeriesItem(group = group, onClick = { selectedGroupTitle = group.title })
                                    }
                                }
                            }
                        } else {
                            val group = groupedComics.find { it.title == selectedGroupTitle }
                            val list = group?.comics ?: emptyList()
                            val filteredInGroup = if (searchQuery.isEmpty()) list else list.filter { it.name.contains(searchQuery, ignoreCase = true) }
                                .sortedBy { it.name.indexOf(searchQuery, ignoreCase = true) }

                            if (filteredInGroup.isEmpty()) {
                                Text("No comics match your search in this series", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(150.dp),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(items = filteredInGroup, key = { it.uri.toString() }) { comic ->
                                        ComicItem(comic = comic, onClick = { onComicClick(comic.uri) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Composable for displaying a single comic item in the library grid.
 *
 * @param comic The [Comic] data class representing the comic.
 * @param onClick Callback invoked when the comic item is clicked.
 */
@Composable
fun ComicItem(comic: Comic, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(comic.uri) {
        withContext(Dispatchers.IO) {
            thumbnail = ComicUtils.getThumbnail(context, comic.uri)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.7f)
            ) {
                Text(
                    text = comic.name,
                    color = Color.White,
                    modifier = Modifier.padding(6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.primary).align(Alignment.TopCenter))
        }
    }
}

/**
 * Composable for displaying a series (group of comics) in the library grid.
 */
@Composable
fun SeriesItem(group: ComicGroup, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(group.comics.firstOrNull()?.uri) {
        group.comics.firstOrNull()?.let { firstComic ->
            withContext(Dispatchers.IO) {
                thumbnail = ComicUtils.getThumbnail(context, firstComic.uri)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.8f
                )
            }

            // Stack effect simulation
            Box(modifier = Modifier.fillMaxSize().padding(4.dp).background(Color.White.copy(alpha = 0.1f), MaterialTheme.shapes.medium))
            
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                        text = "${group.comics.size} ISSUES",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = group.title,
                        color = Color.White,
                        modifier = Modifier.padding(4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * A zoomable and pannable image composable for viewing comic pages.
 *
 * @param bitmap The [Bitmap] to display.
 * @param onZoomChanged Callback invoked when the zoom state changes.
 * @param pageIndex The index of this page.
 * @param currentPage The index of the currently visible page in the pager.
 * @param onToggleUI Callback invoked to toggle the visibility of UI overlays.
 * @param readingMode The current reading mode ("horizontal" or "vertical").
 * @param modifier The [Modifier] for this composable.
 */
@Composable
fun ZoomableImage(
    bitmap: Bitmap,
    onZoomChanged: (Boolean) -> Unit,
    pageIndex: Int,
    currentPage: Int,
    onToggleUI: () -> Unit,
    readingMode: String,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val configuration = LocalConfiguration.current

    // Reset zoom when page changes
    LaunchedEffect(currentPage) {
        if (currentPage != pageIndex) {
            scale = 1f
            offset = Offset.Zero
            onZoomChanged(false)
        }
    }

    // Reset zoom on rotation
    LaunchedEffect(configuration.orientation) {
        scale = 1f
        offset = Offset.Zero
        onZoomChanged(false)
    }

    BoxWithConstraints(modifier = modifier) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()
        
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()
        
        val scaleFit = minOf(screenWidth / imageWidth, screenHeight / imageHeight)
        val actualImageWidth = imageWidth * scaleFit
        val actualImageHeight = imageHeight * scaleFit
        
        val maxOffsetX = maxOf(0f, (actualImageWidth * scale - screenWidth) / 2f)
        val maxOffsetY = maxOf(0f, (actualImageHeight * scale - screenHeight) / 2f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RectangleShape)
                .pointerInput(scale, offset, readingMode) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointersCount = event.changes.size
                            
                            if (pointersCount >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()

                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = newScale
                                onZoomChanged(scale > 1f)

                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                )
                                event.changes.forEach { it.consume() }
                            } else if (pointersCount == 1 && scale > 1f) {
                                val pan = event.calculatePan()
                                val oldOffset = offset

                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                )

                                val consumed = if (readingMode == "horizontal") {
                                    if (abs(pan.y) > abs(pan.x)) {
                                        true
                                    } else {
                                        val reachedEdgeX = (pan.x > 0 && oldOffset.x >= maxOffsetX) ||
                                                           (pan.x < 0 && oldOffset.x <= -maxOffsetX)
                                        !reachedEdgeX
                                    }
                                } else {
                                    if (abs(pan.x) > abs(pan.y)) {
                                        true
                                    } else {
                                        val reachedEdgeY = (pan.y > 0 && oldOffset.y >= maxOffsetY) ||
                                                           (pan.y < 0 && oldOffset.y <= -maxOffsetY)
                                        !reachedEdgeY
                                    }
                                }

                                if (consumed) {
                                    event.changes.forEach { it.consume() }
                                }
                            } else {
                                onZoomChanged(scale > 1f)
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onToggleUI() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                                onZoomChanged(false)
                            } else {
                                scale = 2.5f
                                onZoomChanged(true)
                            }
                        }
                    )
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
        }
    }
}

/**
 * Composable screen for reading a comic.
 *
 * @param uri The [Uri] of the comic file.
 * @param onBack Callback invoked to navigate back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(uri: Uri, onBack: () -> Unit) {
    val viewModel: ComicViewModel = viewModel()
    val pages by viewModel.currentComicPages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current
    var isZoomed by remember { mutableStateOf(false) }
    var isUIVisible by remember { mutableStateOf(true) }
    
    val prefs = remember { context.getSharedPreferences("comics_prefs", Context.MODE_PRIVATE) }
    val readingMode = remember { prefs.getString("reading_mode", "horizontal") ?: "horizontal" }

    val fileName = remember(uri) {
        try {
            DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: "Reading"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "Reading"
        }
    }

    LaunchedEffect(uri) {
        Log.d("ReaderScreen", "Loading comic: $uri")
        viewModel.loadComic(uri)
    }

    Scaffold(
        containerColor = Color.Black
    ) { padding ->
        val unused = padding // Suppress warning
        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (pages.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No pages found in comic.", color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { viewModel.loadComic(uri) }) {
                        Text("Retry")
                    }
                }
            } else {
                val pagerState = rememberPagerState(pageCount = { pages.size })

                if (readingMode == "horizontal") {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                        pageSpacing = 0.dp,
                        userScrollEnabled = true
                    ) { pageIndex ->
                        ReaderPage(
                            uri = uri,
                            entryName = pages[pageIndex],
                            viewModel = viewModel,
                            currentPage = pagerState.currentPage,
                            pageIndex = pageIndex,
                            readingMode = readingMode,
                            onZoomChanged = { isZoomed = it },
                            onToggleUI = { isUIVisible = !isUIVisible }
                        )
                    }
                } else {
                    VerticalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                        pageSpacing = 0.dp,
                        userScrollEnabled = true
                    ) { pageIndex ->
                        ReaderPage(
                            uri = uri,
                            entryName = pages[pageIndex],
                            viewModel = viewModel,
                            currentPage = pagerState.currentPage,
                            pageIndex = pageIndex,
                            readingMode = readingMode,
                            onZoomChanged = { isZoomed = it },
                            onToggleUI = { isUIVisible = !isUIVisible }
                        )
                    }
                }
                
                // Top Bar Overlay
                AnimatedVisibility(
                    visible = isUIVisible,
                    enter = slideInVertically(initialOffsetY = { -it }),
                    exit = slideOutVertically(targetOffsetY = { -it }),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    TopAppBar(
                        title = {
                            Text(
                                fileName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Black.copy(alpha = 0.8f)
                        )
                    )
                }

                // Bottom Progress Overlay
                AnimatedVisibility(
                    visible = isUIVisible,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it }),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Black.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${pagerState.currentPage + 1} / ${pages.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                                LinearProgressIndicator(
                                    progress = { (pagerState.currentPage + 1).toFloat() / pages.size },
                                    modifier = Modifier.width(100.dp).height(2.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = Color.DarkGray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Composable representing a single page in the comic reader.
 *
 * @param uri The [Uri] of the comic file.
 * @param entryName The name of the file entry for this page within the archive.
 * @param viewModel The [ComicViewModel] used for loading the page.
 * @param currentPage The index of the currently visible page.
 * @param pageIndex The index of this page.
 * @param readingMode The current reading mode ("horizontal" or "vertical").
 * @param onZoomChanged Callback invoked when the zoom state changes.
 * @param onToggleUI Callback invoked to toggle the visibility of UI overlays.
 */
@Composable
fun ReaderPage(
    uri: Uri,
    entryName: String,
    viewModel: ComicViewModel,
    currentPage: Int,
    pageIndex: Int,
    readingMode: String,
    onZoomChanged: (Boolean) -> Unit,
    onToggleUI: () -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf(false) }
    
    LaunchedEffect(entryName) {
        loadError = false
        bitmap = viewModel.getPageBitmap(uri, entryName)
        if (bitmap == null) loadError = true
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            ZoomableImage(
                bitmap = bitmap!!,
                modifier = Modifier.fillMaxSize(),
                pageIndex = pageIndex,
                currentPage = currentPage,
                readingMode = readingMode,
                onZoomChanged = onZoomChanged,
                onToggleUI = onToggleUI
            )
        } else if (loadError) {
            Text("Error loading page", color = Color.Red)
        } else {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}