package com.example.arassemblydemo

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Identity comes from the installed workflow and active registration, never an imported coordinate. */
internal data class JointContext(val owner: String?, val revision: String, val assembly: String,
    val actions: String, val task: String, val part: String, val ids: Set<Int>) {
    val key: String get() = MessageDigest.getInstance("SHA-256").digest(
        JSONArray(listOf(owner, revision, assembly, actions, task, part, ids.sorted())).toString().toByteArray()
    ).joinToString("") { "%02x".format(it) }
    fun isCurrent(context: Context): Boolean = LocalAccounts(context).current()?.id == owner &&
        context.getSharedPreferences("assembly-bindings-v1",0).getString("revision","unregistered") == revision
    companion object {
        fun load(context: Context, task: String, part: String): JointContext {
            val flow=JSONObject(context.assets.open("shelf-phone/workflow.json").bufferedReader().use { it.readText() })
            val tasks=flow.getJSONArray("tasks")
            val t=(0 until tasks.length()).map { tasks.getJSONObject(it) }.single { it.getString("id")==task }
            val members=t.getJSONArray("context")
            require((0 until members.length()).any { members.getString(it)==part }) { context.getString(R.string.joint_wrong_part) }
            val prefs=context.getSharedPreferences("assembly-bindings-v1",0)
            val bindings=JSONObject(prefs.getString("bindings","{}")!!)
            val values=bindings.optJSONArray(part) ?: error(context.getString(R.string.joint_no_tags))
            val ids=(0 until values.length()).map { values.getInt(it) }.toSet()
            require(ids.size in 3..8 && ids.all { it in 0..249 }) { context.getString(R.string.joint_need_tags) }
            return JointContext(LocalAccounts(context).current()?.id,prefs.getString("revision","unregistered")!!,
                flow.getString("assembly"),flow.getString("sourceSha256"),task,part,ids)
        }
    }
}

internal class JointReferenceStore(private val context: Context, private val scope: JointContext) {
    data class Saved(val reference: PlanarJointMapper.Reference, val cameraProfile: String, val image: File,
                     val record: File)
    private val directory=File(context.filesDir,"joint-references-v1").apply { mkdirs() }
    private val pointer=AtomicFile(File(directory,scope.key+".active"))
    fun load(): Saved? = runCatching {
        val name=pointer.openRead().bufferedReader().use { it.readText() }.trim()
        require(name.matches(Regex("[a-f0-9-]{36}\\.json")))
        val record=File(directory,name)
        require(record.length() in 1..100_000)
        val j=JSONObject(record.readText())
        require(j.getInt("schema")==1 && j.getString("scope")==scope.key)
        val tags=j.getJSONArray("tags")
        val ref=PlanarJointMapper.Reference(scope.part,j.getString("face"),scope.ids,j.getInt("width"),j.getInt("height"),
            (0 until tags.length()).map { i -> val tag=tags.getJSONObject(i);val corners=tag.getJSONArray("corners")
                PlanarJointMapper.Tag(tag.getInt("id"),(0 until corners.length()).map { k ->
                    val xy=corners.getJSONArray(k);PlanarJointMapper.XY(xy.getDouble(0),xy.getDouble(1)) }) },
            j.getJSONArray("target").let { PlanarJointMapper.XY(it.getDouble(0),it.getDouble(1)) })
        require(PlanarJointMapper.validateReference(ref).point!=null)
        val image=File(directory,name.removeSuffix(".json")+".jpg")
        require(image.isFile)
        Saved(ref,j.getString("cameraProfile"),image,record)
    }.getOrNull()

    /** New immutable record first; only switch the active reference after both files are complete. */
    fun save(frame: JointPhotoFrame, reference: PlanarJointMapper.Reference): Saved {
        require(scope.isCurrent(context)) { context.getString(R.string.joint_reg_changed) }
        require(PlanarJointMapper.validateReference(reference).point!=null)
        val base=UUID.randomUUID().toString()
        val image=File(directory,"$base.jpg"); val record=File(directory,"$base.json")
        try {
            image.outputStream().use { require(frame.bitmap.compress(Bitmap.CompressFormat.JPEG,96,it)) }
            val j=JSONObject().put("schema",1).put("scope",scope.key).put("task",scope.task).put("part",scope.part)
                .put("evidenceKind","USER_POINTED_REFERENCE_NOT_PHYSICAL_VALIDATION")
                .put("savedEpochMs",System.currentTimeMillis()).put("cameraProfile",profile(frame))
                .put("capture",frame.metadata()).put("face",reference.face)
                .put("width",reference.width).put("height",reference.height)
                .put("target",JSONArray(listOf(reference.target.x,reference.target.y)))
                .put("tags",JSONArray(reference.tags.map { tag -> JSONObject().put("id",tag.id)
                    .put("corners",JSONArray(tag.corners.map { JSONArray(listOf(it.x,it.y)) })) }))
            record.writeText(j.toString(2))
            require(scope.isCurrent(context)) { context.getString(R.string.joint_reg_changed) }
            val stream=pointer.startWrite()
            try { stream.write(record.name.toByteArray());pointer.finishWrite(stream) }
            catch(e:Exception){pointer.failWrite(stream);throw e}
            return Saved(reference,profile(frame),image,record)
        } catch(e:Exception) { image.delete();record.delete();throw e }
    }
    companion object {
        fun profile(frame: JointPhotoFrame): String = listOf(frame.cameraId,frame.sourceWidth,frame.sourceHeight,
            frame.cropLeft,frame.cropTop,frame.cropWidth,frame.cropHeight).joinToString(":")
        fun tags(frame: JointPhotoFrame, allowed: Set<Int>) = frame.markers.filter { it.id in allowed }.map { m ->
            PlanarJointMapper.Tag(m.id,(0..3).map { PlanarJointMapper.XY(m.points[2*it].toDouble(),m.points[2*it+1].toDouble()) }) }
    }
}
