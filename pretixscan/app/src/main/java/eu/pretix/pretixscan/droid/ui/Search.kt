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
import eu.pretix.pretixscan.droid.databinding.ItemSearchresultBinding
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale


interface SearchResultClickedInterface {
    fun onSearchResultClicked(res: TicketCheckProvider.SearchResult);
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

    val secrets = mutableListOf<String>()
    for (candidate in candidates) {
        val secret = candidate.secret ?: continue
        if (secret in excludeSecrets || secret in secrets) {
            continue
        }
        if (hasMatchingAnswer(candidate.json_data, upperQuery)) {
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

private fun hasMatchingAnswer(jsonData: String?, upperQuery: String): Boolean {
    if (jsonData == null) {
        return false
    }
    return try {
        val answers = JSONObject(jsonData).optJSONArray("answers") ?: return false
        (0 until answers.length()).any { i ->
            answers.getJSONObject(i).optString("answer")
                .uppercase(Locale.getDefault())
                .contains(upperQuery)
        }
    } catch (e: JSONException) {
        false
    }
}

class SearchListAdapter(private val results: List<TicketCheckProvider.SearchResult>, private val cb: SearchResultClickedInterface) : RecyclerView.Adapter<BindingHolder<ItemSearchresultBinding>>(), View.OnClickListener {
    override fun onBindViewHolder(holder: BindingHolder<ItemSearchresultBinding>,
                                  position: Int) {
        val item = results.get(position)
        holder.binding.res = item
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
