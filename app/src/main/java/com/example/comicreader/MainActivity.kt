package com.example.comicreader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.comicreader.data.AnalyticsManager
import com.example.comicreader.data.AppDatabase
import com.example.comicreader.data.DuplicateManager
import com.example.comicreader.ui.AnalyticsScreen
import com.example.comicreader.ui.DuplicateManagerScreen
import com.example.comicreader.ui.theme.EikonixTheme
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
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("comics_prefs", Context.MODE_PRIVATE) }
            var isDarkMode by remember { 
                mutableStateOf(prefs.getBoolean("dark_mode", true)) 
            }
            
            val db = remember { AppDatabase.getDatabase(context) }
            val duplicateManager = remember { DuplicateManager(context, db.comicDao()) }
            val analyticsManager = remember { AnalyticsManager(db.comicDao()) }

            EikonixTheme(darkTheme = isDarkMode) {
                val navController = rememberNavController()
                ComicApp(navController, isDarkMode, analyticsManager, duplicateManager, onToggleDarkMode = {
                    isDarkMode = it
                    prefs.edit().putBoolean("dark_mode", it).apply()
                })
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
fun ComicApp(navController: NavHostController, isDarkMode: Boolean, analyticsManager: AnalyticsManager, duplicateManager: DuplicateManager, onToggleDarkMode: (Boolean) -> Unit) {
    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                isDarkMode = isDarkMode,
                onToggleDarkMode = onToggleDarkMode,
                onComicClick = { uri ->
                    val encodedUri = Uri.encode(uri.toString())
                    navController.navigate("reader/$encodedUri")
                },
                onNavigateToAnalytics = { navController.navigate("analytics") },
                onNavigateToDuplicates = { navController.navigate("duplicates") }
            )
        }
        composable(
            "reader/{comicUri}",
            arguments = listOf(navArgument("comicUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val comicUriStr = backStackEntry.arguments?.getString("comicUri") ?: ""
            val comicUri = Uri.parse(comicUriStr)
            ReaderScreen(uri = comicUri, isDarkMode = isDarkMode, analyticsManager = analyticsManager, onBack = { navController.popBackStack() })
        }
        composable("analytics") {
            AnalyticsScreen(analyticsManager)
        }
        composable("duplicates") {
            DuplicateManagerScreen(duplicateManager, emptyList())
        }
    }
}

@Composable
fun SectionHeader(title: String, isDarkMode: Boolean) {
    val boxColor = if (isDarkMode) Color(0xFFE23636) else Color(0xFF0052CC)
    val textColor = Color.White
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = boxColor,
            shape = RoundedCornerShape(4.dp),
            shadowElevation = 4.dp
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = textColor,
                letterSpacing = 1.5.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    isDarkMode: Boolean, 
    onToggleDarkMode: (Boolean) -> Unit, 
    onComicClick: (Uri) -> Unit,
    onNavigateToAnalytics: () -> Unit,
    onNavigateToDuplicates: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: ComicViewModel = viewModel()
    val prefs = remember { context.getSharedPreferences("comics_prefs", Context.MODE_PRIVATE) }
    
    var comics by remember { mutableStateOf<List<Comic>>(emptyList()) }
    var isLoadingLibrary by remember { mutableStateOf(true) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showSettingsDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var selectedGroupTitle by remember { mutableStateOf<String?>(null) }

    var readingMode by remember { 
        mutableStateOf(prefs.getString("reading_mode", "horizontal") ?: "horizontal") 
    }

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

    val lastReadComics = remember(comics) {
        comics.filter { viewModel.getLastReadTime(it.uri) > 0 && !viewModel.isCompleted(it.uri) }
            .sortedByDescending { viewModel.getLastReadTime(it.uri) }
            .take(5)
    }

    val completedComics = remember(comics) {
        comics.filter { viewModel.isCompleted(it.uri) }
            .sortedByDescending { viewModel.getLastReadTime(it.uri) }
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
                    } catch (e: Exception) { null }
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

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let { treeUri ->
                scope.launch {
                    isLoadingLibrary = true
                    withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (e: Exception) {}
                        val foundItems = mutableListOf<Pair<Uri, String>>()
                        ComicUtils.scanDirectory(context, treeUri, foundItems)
                        if (foundItems.isNotEmpty()) {
                            val currentUris = prefs.getStringSet("comic_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
                            currentUris.addAll(foundItems.map { it.first.toString() })
                            prefs.edit().putStringSet("comic_uris", currentUris).apply()
                            updateComics(currentUris)
                        } else {
                            withContext(Dispatchers.Main) { isLoadingLibrary = false }
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
                        Text("Horizontal Sliding")
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
                        Text("Vertical Swiping")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            prefs.edit().remove("comic_uris").apply()
                            comics = emptyList()
                            showSettingsDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear Library", color = Color.White) }
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Close") } }
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
                Text("Comic Vault", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                HorizontalDivider()
                
                NavigationDrawerItem(
                    label = { Text(if (isDarkMode) "Light Mode" else "Dark Mode") },
                    selected = false,
                    onClick = { 
                        scope.launch { drawerState.close() }
                        onToggleDarkMode(!isDarkMode)
                    },
                    icon = { Icon(if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode, null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(
                    label = { Text("Analytics") },
                    selected = false,
                    onClick = { 
                        scope.launch { drawerState.close() }
                        onNavigateToAnalytics()
                    },
                    icon = { Icon(Icons.Default.Assessment, null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(
                    label = { Text("Duplicate Finder") },
                    selected = false,
                    onClick = { 
                        scope.launch { drawerState.close() }
                        onNavigateToDuplicates()
                    },
                    icon = { Icon(Icons.Default.ContentCopy, null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(label = { Text("Settings") }, selected = false, onClick = { scope.launch { drawerState.close() }; showSettingsDialog = true }, icon = { Icon(Icons.Default.Settings, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
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
                        placeholder = { Text("Search your comics...") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { IconButton(onClick = { if (searchQuery.isNotEmpty()) searchQuery = "" else isSearching = false }) { Icon(Icons.Default.Close, null) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(comics.filter { it.name.contains(searchQuery, true) }) { comic ->
                                SearchResultItem(comic = comic, onClick = { onComicClick(comic.uri) })
                            }
                        }
                    }
                } else {
                    CenterAlignedTopAppBar(
                        title = { Text(selectedGroupTitle ?: "COMIC VAULT", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary) },
                        navigationIcon = {
                            if (selectedGroupTitle != null) {
                                IconButton(onClick = { selectedGroupTitle = null }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            } else {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, null)
                                }
                            }
                        },
                        actions = { IconButton(onClick = { isSearching = true }) { Icon(Icons.Default.Search, null) } }
                    )
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { folderLauncher.launch(null) }, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Add, contentDescription = "Add Comics")
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (isLoadingLibrary) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (comics.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Your library is empty. Tap + to add comics.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    }
                } else if (selectedGroupTitle != null) {
                    val group = groupedComics.find { it.title == selectedGroupTitle }
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(group?.comics ?: emptyList()) { comic ->
                            SearchResultItem(comic = comic, onClick = { onComicClick(comic.uri) })
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.background)
                    ) {
                        // CONTINUE READING SECTION
                        if (lastReadComics.isNotEmpty()) {
                            SectionHeader(title = "CONTINUE READING", isDarkMode = isDarkMode)
                            val pagerState = rememberPagerState(pageCount = { lastReadComics.size })
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxWidth().height(280.dp),
                                contentPadding = PaddingValues(horizontal = 48.dp),
                                pageSpacing = 16.dp
                            ) { page ->
                                val comic = lastReadComics[page]
                                FeaturedComicItem(
                                    comic = comic,
                                    modifier = Modifier.graphicsLayer {
                                        val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                                        alpha = 1f - (abs(pageOffset) * 0.3f)
                                        scaleY = 1f - (abs(pageOffset) * 0.1f)
                                    },
                                    onClick = { onComicClick(comic.uri) }
                                )
                            }
                        }

                        // SERIES SECTION
                        SectionHeader(title = "SERIES", isDarkMode = isDarkMode)
                        Box(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(vertical = 16.dp)) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(groupedComics) { group ->
                                    SeriesCard(group = group, onClick = { selectedGroupTitle = group.title })
                                }
                            }
                        }

                        // ALL COMICS SECTION (Horizontal List)
                        SectionHeader(title = "RECENTLY ADDED", isDarkMode = isDarkMode)
                        Box(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)).padding(vertical = 16.dp)) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(comics.take(15)) { comic ->
                                    ComicCard(comic = comic, onClick = { onComicClick(comic.uri) })
                                }
                            }
                        }

                        // COMPLETED SECTION
                        if (completedComics.isNotEmpty()) {
                            SectionHeader(title = "COMPLETED", isDarkMode = isDarkMode)
                            Box(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(vertical = 16.dp)) {
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(completedComics) { comic ->
                                        ComicCard(comic = comic, onClick = { onComicClick(comic.uri) })
                                    }
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultItem(comic: Comic, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnailFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(comic.uri) {
        withContext(Dispatchers.IO) { thumbnailFile = ComicUtils.getThumbnailFile(context, comic.uri) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(modifier = Modifier.size(60.dp, 90.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(thumbnailFile).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(comic.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FeaturedComicItem(comic: Comic, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnailFile by remember { mutableStateOf<File?>(null) }
    
    LaunchedEffect(comic.uri) {
        withContext(Dispatchers.IO) { thumbnailFile = ComicUtils.getThumbnailFile(context, comic.uri) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            modifier = Modifier.weight(1f).aspectRatio(0.66f)
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(thumbnailFile).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.7f)), startY = 300f)))
                
                Text(
                    comic.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                )
            }
        }
    }
}

@Composable
fun ComicCard(comic: Comic, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnailFile by remember { mutableStateOf<File?>(null) }
    
    LaunchedEffect(comic.uri) {
        withContext(Dispatchers.IO) { thumbnailFile = ComicUtils.getThumbnailFile(context, comic.uri) }
    }

    Column(modifier = Modifier.width(120.dp).clickable(onClick = onClick)) {
        Card(
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(2.dp),
            modifier = Modifier.height(180.dp).fillMaxWidth()
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(thumbnailFile).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            comic.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun SeriesCard(group: ComicGroup, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnailFile by remember { mutableStateOf<File?>(null) }
    
    LaunchedEffect(group.comics.firstOrNull()?.uri) {
        group.comics.firstOrNull()?.let {
            withContext(Dispatchers.IO) { thumbnailFile = ComicUtils.getThumbnailFile(context, it.uri) }
        }
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.width(200.dp).height(120.dp).clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(thumbnailFile).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.6f
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f)))
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
                Text(group.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${group.comics.size} ISSUES", color = Color.White.copy(0.8f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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
fun ReaderScreen(uri: Uri, isDarkMode: Boolean, analyticsManager: AnalyticsManager, onBack: () -> Unit) {
    val view = LocalView.current
    
    // In ReaderScreen, we always want light icons (white) because the background is black.
    DisposableEffect(isDarkMode) {
        val window = (view.context as Activity).window
        val insetsController = WindowCompat.getInsetsController(window, view)
        
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        
        onDispose {
            // Restore status bar appearance based on the global theme
            insetsController.isAppearanceLightStatusBars = !isDarkMode
            insetsController.isAppearanceLightNavigationBars = !isDarkMode
        }
    }

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

    var sessionStartTime by remember { mutableLongStateOf(0L) }
    var startPage by remember { mutableIntStateOf(0) }

    LaunchedEffect(uri) { 
        viewModel.loadComic(uri) 
        sessionStartTime = System.currentTimeMillis()
    }

    DisposableEffect(uri) {
        onDispose {
            val duration = System.currentTimeMillis() - sessionStartTime
            if (duration > 5000) {
                val endPage = viewModel.getLastReadPage(uri)
                val totalReadInThisSession = abs(endPage - startPage)
                analyticsManager.trackSession(uri.toString(), sessionStartTime, System.currentTimeMillis(), totalReadInThisSession)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Alignment.Center.run { Modifier.align(this) })
        } else if (pages.isNotEmpty()) {
            val pagerState = rememberPagerState(initialPage = 0, pageCount = { pages.size })
            val lastRead = remember(uri) { viewModel.getLastReadPage(uri) }

            LaunchedEffect(pages) {
                if (!initialPageSet) {
                    if (lastRead > 0) {
                        pagerState.scrollToPage(lastRead)
                        startPage = lastRead
                    }
                    initialPageSet = true
                }
            }

            LaunchedEffect(pagerState.currentPage) {
                if (initialPageSet) {
                    viewModel.saveLastReadPage(uri, pagerState.currentPage, pages.size)
                }
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
