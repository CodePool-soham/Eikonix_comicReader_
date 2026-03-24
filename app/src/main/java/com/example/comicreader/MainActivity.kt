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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.comicreader.ui.theme.ComicReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

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

    private fun handleIntent(intent: Intent?, navController: NavHostController) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                val encodedUri = Uri.encode(uri.toString())
                navController.navigate("reader/$encodedUri")
            }
        }
    }
}

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
            ReaderScreen(uri = comicUri, onBack = { navController.popBackStack() })
        }
    }
}

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

    fun updateComics(uris: Set<String>) {
        scope.launch {
            isLoadingLibrary = true
            val comicList = withContext(Dispatchers.IO) {
                uris.mapNotNull { uriString ->
                    try {
                        val uri = Uri.parse(uriString)
                        val name = ComicUtils.getFileName(context, uri) ?: "Unknown"
                        Comic(uri = uri, name = name)
                    } catch (e: Exception) {
                        null
                    }
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
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let { treeUri ->
                scope.launch {
                    isLoadingLibrary = true
                    withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) {}

                        val foundItems = mutableListOf<Pair<Uri, String>>()
                        ComicUtils.scanDirectory(context, treeUri, foundItems)

                        if (foundItems.isNotEmpty()) {
                            val currentUris = prefs.getStringSet("comic_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
                            currentUris.addAll(foundItems.map { it.first.toString() })
                            prefs.edit().putStringSet("comic_uris", currentUris).apply()
                            
                            val newComicList = currentUris.map { uriStr ->
                                val u = Uri.parse(uriStr)
                                val name = foundItems.find { it.first == u }?.second 
                                           ?: ComicUtils.getFileName(context, u) ?: "Unknown"
                                Comic(uri = u, name = name)
                            }.sortedWith(compareBy(NaturalOrderComparator()) { it.name })
                            
                            withContext(Dispatchers.Main) {
                                comics = newComicList
                                isLoadingLibrary = false
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                isLoadingLibrary = false
                            }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            readingMode = "horizontal"
                            prefs.edit().putString("reading_mode", "horizontal").apply()
                        }
                    ) {
                        RadioButton(selected = readingMode == "horizontal", onClick = { 
                            readingMode = "horizontal"
                            prefs.edit().putString("reading_mode", "horizontal").apply()
                        })
                        Text("Horizontal")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            readingMode = "vertical"
                            prefs.edit().putString("reading_mode", "vertical").apply()
                        }
                    ) {
                        RadioButton(selected = readingMode == "vertical", onClick = { 
                            readingMode = "vertical"
                            prefs.edit().putString("reading_mode", "vertical").apply()
                        })
                        Text("Vertical")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            prefs.edit().remove("comic_uris").apply()
                            comics = emptyList()
                            showSettingsDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Clear Library", color = Color.White)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("Close") }
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
                Text("Comic Reader", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
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
                        onActiveChange = { if (!it) { isSearching = false; searchQuery = "" } },
                        placeholder = { Text("Search...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { if (searchQuery.isNotEmpty()) searchQuery = "" else isSearching = false }) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        },
                        colors = SearchBarDefaults.colors(containerColor = Color.Transparent),
                        tonalElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
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
                } else {
                    CenterAlignedTopAppBar(
                        title = { Text(selectedGroupTitle ?: if (viewMode == LibraryViewMode.SERIES) "SERIES" else "COMICS") },
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
                        }
                    )
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { folderLauncher.launch(null) }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "Add Folder")
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (isLoadingLibrary) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (comics.isEmpty()) {
                    Text("Library is empty. Add a folder.", modifier = Modifier.align(Alignment.Center))
                } else {
                    val displayComics = if (viewMode == LibraryViewMode.ALL_COMICS) {
                        filteredComics
                    } else if (selectedGroupTitle != null) {
                        groupedComics.find { it.title == selectedGroupTitle }?.comics ?: emptyList()
                    } else null

                    if (displayComics != null) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(items = displayComics, key = { it.uri.toString() }) { comic ->
                                ComicItem(comic = comic, onClick = { onComicClick(comic.uri) })
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(items = groupedComics, key = { it.title }) { group ->
                                SeriesItem(group = group, onClick = { selectedGroupTitle = group.title })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ComicItem(comic: Comic, onClick: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ComicViewModel = viewModel()
    var thumbnailFile by remember { mutableStateOf<File?>(null) }
    val isCompleted = remember(comic.uri) { viewModel.isCompleted(comic.uri) }
    
    LaunchedEffect(comic.uri) {
        withContext(Dispatchers.IO) {
            thumbnailFile = ComicUtils.getThumbnailFile(context, comic.uri)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailFile)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            if (isCompleted) {
                Icon(
                    Icons.Default.CheckCircle, null, tint = Color.Green,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).background(Color.Black.copy(0.4f), RectangleShape)
                )
            }

            Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color.Black.copy(alpha = 0.6f)) {
                Text(comic.name, color = Color.White, modifier = Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SeriesItem(group: ComicGroup, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnailFile by remember { mutableStateOf<File?>(null) }
    
    LaunchedEffect(group.comics.firstOrNull()?.uri) {
        group.comics.firstOrNull()?.let { firstComic ->
            withContext(Dispatchers.IO) {
                thumbnailFile = ComicUtils.getThumbnailFile(context, firstComic.uri)
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailFile)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.8f
            )
            Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color.Black.copy(alpha = 0.7f)) {
                Column(modifier = Modifier.padding(4.dp)) {
                    Text(group.title, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${group.comics.size} issues", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

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

    LaunchedEffect(currentPage) {
        if (currentPage != pageIndex) {
            scale = 1f
            offset = Offset.Zero
            onZoomChanged(false)
        }
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
                .pointerInput(scale, offset, readingMode) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointersCount = event.changes.size
                            if (pointersCount >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                scale = (scale * zoom).coerceIn(1f, 4f)
                                onZoomChanged(scale > 1f)
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                )
                                event.changes.forEach { it.consume() }
                            } else if (pointersCount == 1 && scale > 1f) {
                                val pan = event.calculatePan()
                                val oldX = offset.x
                                val oldY = offset.y
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                )
                                val consumed = if (readingMode == "horizontal") {
                                    abs(pan.y) > abs(pan.x) || (pan.x > 0 && oldX < maxOffsetX) || (pan.x < 0 && oldX > -maxOffsetX)
                                } else {
                                    abs(pan.x) > abs(pan.y) || (pan.y > 0 && oldY < maxOffsetY) || (pan.y < 0 && oldY > -maxOffsetY)
                                }
                                if (consumed) event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onToggleUI() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f; offset = Offset.Zero; onZoomChanged(false)
                            } else {
                                scale = 2.5f; onZoomChanged(true)
                            }
                        }
                    )
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(uri: Uri, onBack: () -> Unit) {
    val viewModel: ComicViewModel = viewModel()
    val pages by viewModel.currentComicPages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isZoomed by remember { mutableStateOf(false) }
    var isUIVisible by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var initialPageSet by remember { mutableStateOf(false) }
    
    val prefs = remember { context.getSharedPreferences("comics_prefs", Context.MODE_PRIVATE) }
    val readingMode = remember { prefs.getString("reading_mode", "horizontal") ?: "horizontal" }

    LaunchedEffect(uri) { viewModel.loadComic(uri) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (pages.isNotEmpty()) {
            val pagerState = rememberPagerState(initialPage = 0, pageCount = { pages.size })
            val lastRead = remember(uri) { viewModel.getLastReadPage(uri) }

            LaunchedEffect(pages) {
                if (!initialPageSet) {
                    if (lastRead > 0) pagerState.scrollToPage(lastRead)
                    initialPageSet = true
                }
            }

            LaunchedEffect(pagerState.currentPage) {
                if (initialPageSet) viewModel.saveLastReadPage(uri, pagerState.currentPage, pages.size)
            }

            if (readingMode == "horizontal") {
                HorizontalPager(state = pagerState, beyondViewportPageCount = 1, userScrollEnabled = !isZoomed) { pageIndex ->
                    ReaderPage(uri, pages[pageIndex], viewModel, pagerState.currentPage, pageIndex, readingMode, { isZoomed = it }, { isUIVisible = !isUIVisible })
                }
            } else {
                VerticalPager(state = pagerState, beyondViewportPageCount = 1, userScrollEnabled = !isZoomed) { pageIndex ->
                    ReaderPage(uri, pages[pageIndex], viewModel, pagerState.currentPage, pageIndex, readingMode, { isZoomed = it }, { isUIVisible = !isUIVisible })
                }
            }
            
            AnimatedVisibility(visible = isUIVisible, enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
                TopAppBar(
                    title = { Text(ComicUtils.getFileName(context, uri) ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                    actions = { IconButton(onClick = { showJumpDialog = true }) { Icon(Icons.Default.Menu, null, tint = Color.White) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(0.7f))
                )
            }

            AnimatedVisibility(visible = isUIVisible, enter = slideInVertically { it }, exit = slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Surface(color = Color.Black.copy(0.7f), modifier = Modifier.fillMaxWidth()) {
                    Text("${pagerState.currentPage + 1} / ${pages.size}", color = Color.White, modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center)
                }
            }

            if (showJumpDialog) {
                var jumpText by remember { mutableStateOf((pagerState.currentPage + 1).toString()) }
                AlertDialog(
                    onDismissRequest = { showJumpDialog = false },
                    title = { Text("Jump to Page") },
                    text = { TextField(value = jumpText, onValueChange = { jumpText = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
                    confirmButton = { TextButton(onClick = {
                        jumpText.toIntOrNull()?.let { p -> if (p in 1..pages.size) scope.launch { pagerState.scrollToPage(p - 1) } }
                        showJumpDialog = false
                    }) { Text("Go") } }
                )
            }
        }
    }
}

@Composable
fun ReaderPage(uri: Uri, entryName: String, viewModel: ComicViewModel, currentPage: Int, pageIndex: Int, readingMode: String, onZoomChanged: (Boolean) -> Unit, onToggleUI: () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.roundToPx() }

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(entryName) {
        bitmap = viewModel.getPageBitmap(uri, entryName, screenWidth, screenHeight)
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bitmap?.let {
            ZoomableImage(it, onZoomChanged, pageIndex, currentPage, onToggleUI, readingMode)
        } ?: CircularProgressIndicator()
    }
}
