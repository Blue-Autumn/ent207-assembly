package com.example.arassemblydemo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class LocalPostComment(
    val id: String,
    val postId: String,
    val authorName: String,
    val text: String,
    val createdAt: Long
)

/** One phone-local profile. The original per-owner reaction sets are used in place. */
internal class PortfolioActivityStore(context: Context) {
    private val app = context.applicationContext
    private val account = LocalAccounts(app).current()
    private val owner = account?.id ?: "guest"
    private val saved = app.getSharedPreferences("portfolio-favorites-v1", Context.MODE_PRIVATE)
    private val liked = app.getSharedPreferences("portfolio-likes-v1", Context.MODE_PRIVATE)
    private val library = app.getSharedPreferences("portfolio-activity-v1-" + owner, Context.MODE_PRIVATE)
    private val published = app.getSharedPreferences("portfolio-public-comments-v1", Context.MODE_PRIVATE)

    fun savedIds(): Set<String> = synchronized(lock) {
        saved.getStringSet(owner, null)?.toSet() ?: PortfolioIdea.byKey.keys.toSet().also {
            saved.edit().putStringSet(owner, it).apply()
        }
    }
    fun likedIds(): Set<String> = synchronized(lock) { liked.getStringSet(owner, null).orEmpty().toSet() }
    fun isLiked(id: String) = id in likedIds()
    fun isSaved(id: String) = id in savedIds()
    fun localLikeCount(id: String) = synchronized(lock) {
        liked.all.values.count { it is Set<*> && id in it }
    }

    fun setLiked(post: CommunityPost, liked: Boolean) = setReaction(post, liked, false)
    fun setSaved(post: CommunityPost, saved: Boolean) = setReaction(post, saved, true)

    private fun setReaction(post: CommunityPost, enabled: Boolean, bookmark: Boolean) = synchronized(lock) {
        val ids = if (bookmark) savedIds() else likedIds()
        val prefs = if (bookmark) saved else liked
        if (enabled) retain(post)
        prefs.edit().putStringSet(owner, if (enabled) ids + post.id else ids - post.id).apply()
        if (!enabled) forgetIfUnused(post.id)
    }

    fun post(id: String): CommunityPost? {
        val catalog = CommunityCatalog.get(app)
        if (catalog.isHidden(id)) return null
        return catalog.post(id) ?: synchronized(lock) {
            library.getString("post:" + id, null)?.let { CommunityPost.fromJson(JSONObject(it)) }
        }
    }

    private fun retain(post: CommunityPost) {
        // Batch removal must also remove its sample discussions and counts from saved copies.
        val snapshot = post.copy(sampleLikes = 0, samples = emptyList()).toJson().toString()
        if (library.getString("post:" + post.id, null) != snapshot)
            library.edit().putString("post:" + post.id, snapshot).apply()
    }

    fun recordShelfView() = synchronized(lock) { recordVisit("shelf") }
    fun recordView(post: CommunityPost) = synchronized(lock) {
        retain(post)
        recordVisit(post.id)
    }

    fun history(): List<String> = synchronized(lock) {
        val ids = JSONArray(library.getString("recent", "[]"))
        List(ids.length()) { ids.getString(it) }
    }

    private fun recordVisit(id: String) {
        val old = history()
        if (old.firstOrNull() == id) return
        val recent = (listOf(id) + old.filterNot { it == id }).take(100)
        library.edit().putString("recent", JSONArray(recent).toString()).apply()
        if (old.size == 100 && old.last() !in recent) forgetIfUnused(old.last())
    }

    private fun decodeComments(raw: String?, authorFallback: String = ""): List<LocalPostComment> {
        val items = JSONArray(raw ?: "[]")
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            LocalPostComment(item.getString("id"), item.getString("post"), item.optString("author", authorFallback),
                item.getString("text"), item.getLong("time"))
        }
    }
    // Old profile-only notes stay private. Only explicit new submissions enter the shared discussion.
    private fun privateNotes() = decodeComments(library.getString("comments",null),account?.name.orEmpty())
    private fun ownPublished() = decodeComments(published.getString(owner,null))
    fun myComments(): List<LocalPostComment> = synchronized(lock) {
        (ownPublished() + privateNotes()).sortedByDescending { it.createdAt }
    }
    fun comments(id: String) = myComments().filter { it.postId == id }
    fun visibleComments(id: String): List<LocalPostComment> = synchronized(lock) {
        (published.all.values.filterIsInstance<String>().flatMap { decodeComments(it) } + privateNotes())
            .filter { it.postId == id }.sortedByDescending { it.createdAt }
    }
    fun ownsComment(id: String) = myComments().any { it.id == id }

    fun addComment(post: CommunityPost, text: String): LocalPostComment? = synchronized(lock) {
        val body = text.trim()
        if (body.length !in 1..1000) return@synchronized null
        val comment = LocalPostComment(UUID.randomUUID().toString(), post.id, account?.name.orEmpty(),
            body, System.currentTimeMillis())
        retain(post)
        published.edit().putString(owner,encodeComments(listOf(comment) + ownPublished())).apply()
        comment
    }

    fun deleteComment(commentId: String): Boolean = synchronized(lock) {
        val shared = ownPublished()
        var selected = shared.find { it.id == commentId }
        if (selected != null) {
            published.edit().putString(owner,encodeComments(shared.filterNot { it.id == commentId })).apply()
        } else {
            val notes = privateNotes()
            selected = notes.find { it.id == commentId } ?: return@synchronized false
            library.edit().putString("comments",encodeComments(notes.filterNot { it.id == commentId })).apply()
        }
        forgetIfUnused(selected.postId)
        true
    }

    private fun encodeComments(comments: List<LocalPostComment>): String {
        val json = JSONArray()
        comments.forEach { json.put(JSONObject().put("id", it.id).put("post", it.postId)
            .put("author", it.authorName).put("text", it.text).put("time", it.createdAt)) }
        return json.toString()
    }

    private fun forgetIfUnused(id: String) {
        if (id !in history() && id !in savedIds() && id !in likedIds() && comments(id).isEmpty())
            library.edit().remove("post:" + id).apply()
    }

    companion object { private val lock = Any() }
}
