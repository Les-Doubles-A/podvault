package com.podvault.app.ui.dictionary

import android.text.Html
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

data class DictionaryLookupResult(
    val entry: DictionaryEntry?,
    val correctedFrom: String? = null,
    val relatedSuggestions: List<String> = emptyList()
)

private object DictionaryClient {
    private val SKIP_HEADINGS = setOf(
        "étymologie", "prononciation", "voir aussi", "références",
        "anagrammes", "traductions", "synonymes", "antonymes",
        "dérivés", "apparentés étymologiques", "vocabulaire apparenté",
        "hyperonymes", "hyponymes", "homophones", "paronymes", "notes"
    )

    private const val USER_AGENT = "PodVault/0.4.3 (https://github.com/Les-Doubles-A/podvault)"

    suspend fun suggest(rawQuery: String): List<String> = withContext(Dispatchers.IO) {
        val cleaned = rawQuery.trim()
        if (cleaned.length < 2) return@withContext emptyList()
        fetchSuggestions(cleaned)
    }

    suspend fun lookup(rawWord: String): Result<DictionaryLookupResult> = withContext(Dispatchers.IO) {
        val cleaned = rawWord.trim()
        if (cleaned.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Mot vide"))

        try {
            val exact = fetchPage(cleaned)
                ?: cleaned.lowercase().takeIf { it != cleaned }?.let { fetchPage(it) }
            if (exact != null) {
                return@withContext Result.success(DictionaryLookupResult(entry = exact))
            }

            // Aucun match exact : on cherche les résultats les plus probables (faute de frappe, accent manquant...)
            val candidates = fetchSuggestions(cleaned)
            for (candidate in candidates.take(3)) {
                val found = fetchPage(candidate)
                if (found != null) {
                    val others = candidates.filterNot { it.equals(found.word, ignoreCase = true) }.take(5)
                    return@withContext Result.success(
                        DictionaryLookupResult(
                            entry = found,
                            correctedFrom = cleaned,
                            relatedSuggestions = others
                        )
                    )
                }
            }

            Result.success(DictionaryLookupResult(entry = null, relatedSuggestions = candidates.take(6)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun fetchSuggestions(query: String): List<String> {
        val results = LinkedHashSet<String>()
        val enc = encode(query)

        // 1. Autocomplétion rapide par préfixe (opensearch)
        runCatching {
            val body = httpGet("https://fr.wiktionary.org/w/api.php?action=opensearch&search=$enc&limit=6&namespace=0&format=json")
            if (body != null) {
                val arr = JSONArray(body)
                val titles = arr.optJSONArray(1)
                if (titles != null) {
                    for (i in 0 until titles.length()) {
                        val t = titles.optString(i).trim()
                        if (t.isNotEmpty()) results += t
                    }
                }
            }
        }

        // 2. Si peu de résultats (ex: faute d'orthographe ou accent oublié), recherche floue MediaWiki
        if (results.size < 4) {
            runCatching {
                val body = httpGet("https://fr.wiktionary.org/w/api.php?action=query&list=search&srsearch=$enc&srlimit=6&srnamespace=0&srinfo=suggestion&format=json")
                if (body != null) {
                    val qObj = JSONObject(body).optJSONObject("query")
                    val didYouMean = qObj?.optJSONObject("searchinfo")?.optString("suggestion").orEmpty().trim()
                    if (didYouMean.isNotEmpty()) {
                        // Place la correction orthographique en premier
                        val ordered = LinkedHashSet<String>()
                        ordered += didYouMean
                        ordered.addAll(results)
                        results.clear()
                        results.addAll(ordered)
                    }
                    val searchArr = qObj?.optJSONArray("search")
                    if (searchArr != null) {
                        for (i in 0 until searchArr.length()) {
                            val t = searchArr.optJSONObject(i)?.optString("title").orEmpty().trim()
                            if (t.isNotEmpty()) results += t
                        }
                    }
                }
            }
        }

        return results.take(6)
    }

    private fun fetchPage(word: String): DictionaryEntry? {
        val enc = encode(word)
        val body = httpGet(
            "https://fr.wiktionary.org/w/api.php?action=parse&prop=text&format=json&redirects=1&disableeditsection=1&page=$enc"
        ) ?: return null
        val root = JSONObject(body)
        if (root.has("error")) return null
        val parse = root.optJSONObject("parse") ?: return null
        val title = parse.optString("title", word)
        val html = parse.optJSONObject("text")?.optString("*") ?: return null
        val sections = parseWiktionaryHtml(html)
        return if (sections.isEmpty()) null else DictionaryEntry(title, sections)
    }

    private fun parseWiktionaryHtml(html: String): List<DictionarySection> {
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

    private val NON_TEXT_BLOCKS = Regex(
        "<(style|script|sup)[^>]*>.*?</\\1>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    // Html.fromHtml conserve le texte des balises <style> (styles des modèles du Wiktionnaire)
    // et des appels de note <sup> : ils apparaissaient au milieu des définitions.
    private fun stripHtml(input: String): String =
        Html.fromHtml(input.replace(NON_TEXT_BLOCKS, ""), Html.FROM_HTML_MODE_COMPACT)
            .toString()
            .replace(' ', ' ')
            .trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DictionaryLookupResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var liveSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    var recentWords by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var lookupJob by remember { mutableStateOf<Job?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    // Suggestions en direct pendant la frappe (façon moteur de recherche)
    LaunchedEffect(query, showSuggestions) {
        val trimmed = query.trim()
        if (!showSuggestions || trimmed.length < 2) {
            liveSuggestions = emptyList()
            return@LaunchedEffect
        }
        delay(220)
        liveSuggestions = runCatching { DictionaryClient.suggest(trimmed) }.getOrDefault(emptyList())
    }

    fun search(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        query = trimmed
        showSuggestions = false
        liveSuggestions = emptyList()
        keyboard?.hide()
        isLoading = true
        errorMessage = null
        // Une recherche plus récente remplace la précédente : sinon la réponse la plus lente
        // pouvait écraser le résultat du dernier mot demandé.
        lookupJob?.cancel()
        lookupJob = scope.launch {
            DictionaryClient.lookup(trimmed)
                .onSuccess { res ->
                    result = res
                    res.entry?.let { entry ->
                        recentWords = (listOf(entry.word) + recentWords.filterNot { it.equals(entry.word, ignoreCase = true) }).take(6)
                    }
                }
                .onFailure {
                    result = null
                    errorMessage = "Connexion impossible. Vérifiez votre accès internet."
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
                onValueChange = {
                    query = it
                    showSuggestions = true
                    if (it.isBlank()) {
                        result = null
                        errorMessage = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Rechercher un mot…") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            result = null
                            errorMessage = null
                            liveSuggestions = emptyList()
                            showSuggestions = false
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Effacer")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(query) })
            )

            // Liste déroulante des résultats les plus probables pendant la saisie
            if (showSuggestions && liveSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        liveSuggestions.forEachIndexed { idx, suggestion ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { search(suggestion) }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = suggestion,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (idx < liveSuggestions.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            } else if (recentWords.isNotEmpty() && result == null && !isLoading) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recentWords) { word ->
                        AssistChip(onClick = { search(word) }, label = { Text(word) })
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                result?.entry != null -> {
                    val res = result!!
                    val entry = res.entry!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (res.correctedFrom != null) {
                            item(key = "corrected") {
                                Text(
                                    text = "Résultat le plus proche pour « ${res.correctedFrom} »",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        item(key = "word") {
                            Text(
                                text = entry.word,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (res.relatedSuggestions.isNotEmpty()) {
                            item(key = "related") {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(res.relatedSuggestions) { alt ->
                                        AssistChip(onClick = { search(alt) }, label = { Text(alt) })
                                    }
                                }
                            }
                        }
                        items(entry.sections) { section ->
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
                result != null -> {
                    val alts = result!!.relatedSuggestions
                    Text(
                        text = "Aucune définition trouvée pour « ${query.trim()} ».",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (alts.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(alts) { alt ->
                                AssistChip(onClick = { search(alt) }, label = { Text(alt) })
                            }
                        }
                    }
                }
                else -> {
                    if (!showSuggestions || liveSuggestions.isEmpty()) {
                        Text(
                            text = "Tapez un mot pour voir les suggestions et sa définition.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
