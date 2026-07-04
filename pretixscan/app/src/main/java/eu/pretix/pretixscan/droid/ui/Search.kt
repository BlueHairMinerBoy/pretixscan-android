package eu.pretix.pretixscan.droid.ui


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import eu.pretix.libpretixsync.check.AsyncCheckProvider
import eu.pretix.libpretixsync.check.CheckException
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.libpretixsync.models.db.toModel
import eu.pretix.pretixscan.droid.databinding.ItemSearchresultBinding
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale


interface SearchResultClickedInterface {
    fun onSearchResultClicked(res: TicketCheckProvider.SearchResult);
}

/**
 * A search result together with a human-readable rendering of the answers given to the
 * questions of this position, for display in the search result list.
 */
data class SearchResultEntry(
    val result: TicketCheckProvider.SearchResult,
    val answers: String?,
)

/**
 * Loads the labels of all questions of the given events from the local database, keyed by the
 * question's server ID.
 */
fun loadQuestionLabels(application: PretixScan, events: Collection<String>): Map<Long, String> {
    val labels = mutableMapOf<Long, String>()
    for (event in events) {
        application.db.questionQueries.selectByEventSlug(event).executeAsList().forEach { q ->
            val serverId = q.server_id ?: return@forEach
            try {
                labels[serverId] = q.toModel().question
            } catch (e: Exception) {
                // Skip questions with broken JSON
            }
        }
    }
    return labels
}

/**
 * Renders the answers contained in a position's JSON as "Question: answer" lines, or null if
 * there are none.
 */
fun buildAnswersDisplay(position: JSONObject?, questionLabels: Map<Long, String>): String? {
    val answers = position?.optJSONArray("answers") ?: return null
    val lines = mutableListOf<String>()
    try {
        for (i in 0 until answers.length()) {
            val a = answers.getJSONObject(i)
            val answer = a.optString("answer")
            if (answer.isBlank() || answer == "null") {
                continue
            }
            val label = questionLabels[a.optLong("question")]
                ?: a.optString("question_identifier")
            lines.add("$label: $answer")
        }
    } catch (e: JSONException) {
        return null
    }
    return if (lines.isEmpty()) null else lines.joinToString("\n")
}

/**
 * Searches the locally synced order positions for ones where an answer to a question matches
 * the query, and returns them as regular search results. Positions whose secret is contained
 * in [excludeSecrets] are skipped, so results already found by the main search are not
 * duplicated.
 */
fun searchQuestionAnswers(
    application: PretixScan,
    conf: AppConfig,
    eventsAndCheckinLists: Map<String, Long>,
    query: String,
    excludeSecrets: Set<String>,
): List<TicketCheckProvider.SearchResult> {
    val upperQuery = query.uppercase(Locale.getDefault())
    if (upperQuery.length < 4 || eventsAndCheckinLists.isEmpty()) {
        return emptyList()
    }

    val candidates = application.db.scanOrderPositionQueries.searchAnswerCandidates(
        queryContains = "%$query%",
        events = eventsAndCheckinLists.keys.toList(),
        limit = 100L,
    ).executeAsList()

    val disabledQuestions = conf.searchDisabledQuestions
    val secrets = mutableListOf<String>()
    for (candidate in candidates) {
        val secret = candidate.secret ?: continue
        if (secret in excludeSecrets || secret in secrets) {
            continue
        }
        if (hasMatchingAnswer(candidate.json_data, upperQuery, disabledQuestions)) {
            secrets.add(secret)
            if (secrets.size >= 20) {
                break
            }
        }
    }
    if (secrets.isEmpty()) {
        return emptyList()
    }

    // Searching by secret re-uses all check-in list filtering (items, subevents) and result
    // building (check-in state, order status, …) of the regular offline search.
    val provider = AsyncCheckProvider(conf, application.db)
    val results = mutableListOf<TicketCheckProvider.SearchResult>()
    for (secret in secrets) {
        try {
            results.addAll(
                provider.search(eventsAndCheckinLists, secret, 1).filter { it.secret == secret }
            )
        } catch (e: CheckException) {
            // Local data is incomplete, e.g. the check-in list has not been synced yet
            break
        }
    }
    return results
}

private fun hasMatchingAnswer(jsonData: String?, upperQuery: String, disabledQuestions: Set<Long>): Boolean {
    if (jsonData == null) {
        return false
    }
    return try {
        val answers = JSONObject(jsonData).optJSONArray("answers") ?: return false
        (0 until answers.length()).any { i ->
            val a = answers.getJSONObject(i)
            a.optLong("question") !in disabledQuestions &&
                    a.optString("answer")
                        .uppercase(Locale.getDefault())
                        .contains(upperQuery)
        }
    } catch (e: JSONException) {
        false
    }
}

class SearchListAdapter(private val results: List<SearchResultEntry>, private val cb: SearchResultClickedInterface) : RecyclerView.Adapter<BindingHolder<ItemSearchresultBinding>>(), View.OnClickListener {
    override fun onBindViewHolder(holder: BindingHolder<ItemSearchresultBinding>,
                                  position: Int) {
        val item = results.get(position)
        holder.binding.res = item.result
        holder.binding.answers = item.answers
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BindingHolder<ItemSearchresultBinding> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemSearchresultBinding.inflate(inflater, parent, false)
        binding.getRoot().tag = binding
        binding.getRoot().setOnClickListener(this)
        return BindingHolder(binding)
    }

    override fun onClick(v: View) {
        val binding = v.tag as eu.pretix.pretixscan.droid.databinding.ItemSearchresultBinding
        if (binding.res != null) {
            cb.onSearchResultClicked(binding.res!!)
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }
}
