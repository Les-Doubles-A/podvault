package com.podvault.app.ui.dictionary

import android.text.Html
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DictionarySection(
    val partOfSpeech: String,
    val definitions: List<String>
)

data class DictionaryEntry(
    val word: String,
    val sections: List<DictionarySection>
)

private object DictionaryClient {
    private val SKIP_HEADINGS = setOf(
        "étymologie", "prononciation", "voir aussi", "références",
        "anagrammes", "traductions", "synonymes", "antonymes",
        "dérivés", "apparentés étymologiques", "vocabulaire apparenté",
        "hyperonymes", "hyponymes", "homophones", "paronymes", "notes"
    )

    suspend fun lookup(rawWord: String): Result<DictionaryEntry> = withContext(Dispatchers.IO) {
        val cleaned = rawWord.trim()
        if (cleaned.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Mot vide"))

        val first = fetchPage(cleaned)
        if (first != null) return@withContext Result.success(first)

        val lower = cleaned.lowercase()
        if (lower != cleaned) {
            val second = fetchPage(lower)
            if (second != null) return@withContext Result.success(second)
        }

        Result.failure(NoSuchElementException("Aucune définition trouvée pour « $cleaned »."))
    }

    private fun fetchPage(word: String): DictionaryEntry? {
        val encoded = URLEncoder.encode(word, "UTF-8").replace("+", "%20")
        val url = URL(
            "https://fr.wiktionary.org/w/api.php?action=parse&prop=text&format=json&redirects=1&disableeditsection=1&page=$encoded"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "PodVault/0.4.0 (https://github.com/Les-Doubles-A/podvault)")
        }
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(body)
            if (root.has("error")) return null
            val parse = root.optJSONObject("parse") ?: return null
            val title = parse.optString("title", word)
            val html = parse.optJSONObject("text")?.optString("*") ?: return null
            val sections = parseWiktionaryHtml(html)
            if (sections.isEmpty()) null else DictionaryEntry(title, sections)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseWiktionaryHtml(html: String): List<DictionarySection> {
        // Isole la section Français si présente, sinon la première langue
        val h2Regex = Regex("<h2[^>]*>.*?</h2>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val h2Matches = h2Regex.findAll(html).toList()
        val langChunk = if (h2Matches.isEmpty()) {
            html
        } else {
            val frIndex = h2Matches.indexOfFirst { stripHtml(it.value).contains("Français", ignoreCase = true) }
            val idx = if (frIndex >= 0) frIndex else 0
            val start = h2Matches[idx].range.last + 1
            val end = if (idx + 1 < h2Matches.size) h2Matches[idx + 1].range.first else html.length
            html.substring(start, end)
        }

        val h3Regex = Regex("<h3[^>]*>(.*?)</h3>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val h3Matches = h3Regex.findAll(langChunk).toList()
        val results = mutableListOf<DictionarySection>()

        for (i in h3Matches.indices) {
            val heading = stripHtml(h3Matches[i].groupValues[1]).trim()
            if (heading.isEmpty() || SKIP_HEADINGS.any { heading.lowercase().startsWith(it) }) continue
            val start = h3Matches[i].range.last + 1
            val end = if (i + 1 < h3Matches.size) h3Matches[i + 1].range.first else langChunk.length
            val block = langChunk.substring(start, end)

            val olMatch = Regex("<ol[^>]*>(.*?)</ol>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(block)
                ?: continue
            val defs = extractTopLevelListItems(olMatch.groupValues[1])
                .map { item ->
                    val withoutNested = item
                        .replace(Regex("<ul[^>]*>.*?</ul>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                        .replace(Regex("<dl[^>]*>.*?</dl>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                        .replace(Regex("<ol[^>]*>.*?</ol>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                    stripHtml(withoutNested).replace(Regex("\\s+"), " ").trim()
                }
                .filter { it.length > 1 }
                .take(8)

            if (defs.isNotEmpty()) {
                results += DictionarySection(heading, defs)
            }
        }
        return results
    }

    private fun extractTopLevelListItems(olInner: String): List<String> {
        val items = mutableListOf<String>()
        var depth = 0
        var itemStart = -1
        var i = 0
        val lower = olInner.lowercase()
        while (i < olInner.length) {
            if (lower.startsWith("<li", i) && (i + 3 == olInner.length || olInner[i + 3] == '>' || olInner[i + 3].isWhitespace())) {
                val close = olInner.indexOf('>', i)
                if (close < 0) break
                if (depth == 0) itemStart = close + 1
                depth++
                i = close + 1
            } else if (lower.startsWith("</li>", i)) {
                depth--
                if (depth == 0 && itemStart >= 0) {
                    items += olInner.substring(itemStart, i)
                    itemStart = -1
                }
                i += 5
            } else {
                i++
            }
        }
        return items
    }

    private fun stripHtml(input: String): String =
        Html.fromHtml(input, Html.FROM_HTML_MODE_COMPACT).toString().trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf<DictionaryEntry?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recentWords by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun search(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        query = trimmed
        keyboard?.hide()
        isLoading = true
        errorMessage = null
        scope.launch {
            DictionaryClient.lookup(trimmed)
                .onSuccess { res ->
                    entry = res
                    recentWords = (listOf(res.word) + recentWords.filterNot { it.equals(res.word, ignoreCase = true) }).take(6)
                }
                .onFailure { err ->
                    entry = null
                    errorMessage = if (err is NoSuchElementException) {
                        err.message
                    } else {
                        "Connexion impossible. Vérifiez votre accès internet."
                    }
                }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dictionnaire") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Rechercher un mot…") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; entry = null; errorMessage = null }) {
                            Icon(Icons.Filled.Close, contentDescription = "Effacer")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(query) })
            )

            if (recentWords.isNotEmpty() && entry == null && !isLoading) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recentWords) { word ->
                        AssistChip(onClick = { search(word) }, label = { Text(word) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            when {
                isLoading -> {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                errorMessage != null -> {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                entry != null -> {
                    val res = entry!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item(key = "word") {
                            Text(
                                text = res.word,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        items(res.sections) { section ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = section.partOfSpeech,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    section.definitions.forEachIndexed { idx, def ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = "${idx + 1}.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = def,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "source") {
                            Text(
                                text = "Source : Wiktionnaire",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        text = "Tapez un mot pour afficher sa définition.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
