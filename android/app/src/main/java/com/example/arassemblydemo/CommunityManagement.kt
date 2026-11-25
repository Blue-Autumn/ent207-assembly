package com.example.arassemblydemo

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.*

internal object CommunityManagement {
    fun show(host: Activity) {
        val ui=CommunityViews(host)
        val catalog=CommunityCatalog.get(host)
        val status=catalog.status()
        val dialog=Dialog(host)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root=ui.column().apply {
            setPadding(ui.d(20),ui.d(17),ui.d(20),ui.d(12));background=ui.bg(ui.paper,24)
        }
        val bar=LinearLayout(host).apply {gravity=Gravity.CENTER_VERTICAL}
        bar.addView(ui.label(ui.str(R.string.community_manage),24,true),ui.weighted())
        bar.addView(ui.action(ui.str(R.string.community_done)){dialog.dismiss()})
        root.addView(bar)
        val form=ui.column().apply {setPadding(0,0,0,ui.d(20))}
        val scroll=ScrollView(host).apply {addView(form); isVerticalScrollBarEnabled=false}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))

        fun group(title:Int) {form.addView(ui.label(ui.str(title),17,true),ui.lp(18))}
        fun toggle(label:Int,value:Boolean)=Switch(host).apply {
            text=ui.str(label);setTextColor(ui.ink);textSize=14f;minHeight=ui.d(44)
            isChecked=value; form.addView(this,ui.lp())
        }
        fun inputRow(label:Int, options:List<String>, selected:Int):Spinner {
            val row=LinearLayout(host).apply {gravity=Gravity.CENTER_VERTICAL;minimumHeight=ui.d(44)}
            row.addView(ui.label(ui.str(label),14),ui.weighted())
            val spinner=Spinner(host).apply {
                contentDescription=ui.str(label)
                adapter=ArrayAdapter(host,android.R.layout.simple_spinner_dropdown_item,options)
                setSelection(selected.coerceAtLeast(0))
            }
            row.addView(spinner)
            form.addView(row)
            return spinner
        }
        val info=ui.label("",14,false,ui.muted)
        form.addView(info,ui.lp(12))
        group(R.string.community_settings)
        val auto=toggle(R.string.community_auto,status.auto)
        val monthsChoices=(listOf(1,3,6,12)+status.months).distinct().sorted()
        val months=inputRow(R.string.community_period,monthsChoices.map { ui.str(R.string.community_months,it) },monthsChoices.indexOf(status.months))
        val batches=(listOf(1,2,4,8,12,16,24)+status.batch).distinct().sorted()
        val batch=inputRow(R.string.community_batch,batches.map {it.toString()},batches.indexOf(status.batch))
        val styles=listOf("plain","balanced","playful")
        val style=inputRow(R.string.community_style,listOf(R.string.community_plain,R.string.community_balanced,R.string.community_playful).map{ui.str(it)},styles.indexOf(status.style))
        form.addView(ui.label(ui.str(R.string.community_brief),14,true),ui.lp(6))
        val brief=EditText(host).apply {
            setText(status.prompt);hint=ui.str(R.string.community_brief_hint);textSize=14f
            setMinLines(2);maxLines=4;filters=arrayOf(InputFilter.LengthFilter(480))
        }
        form.addView(brief)
        fun applySettings() {
            catalog.configure(brief.text.toString().trim(),monthsChoices[months.selectedItemPosition],
                auto.isChecked, styles[style.selectedItemPosition], batches[batch.selectedItemPosition])
        }
        form.addView(ui.action(ui.str(R.string.community_apply)){applySettings();Toast.makeText(host,R.string.community_settings_saved,Toast.LENGTH_SHORT).show()},ui.lp(6))
        val add=ui.action(ui.str(R.string.community_add_batch)){applySettings();catalog.generate()}
        form.addView(add,ui.lp(8))
        group(R.string.community_mod)
        val debug=toggle(R.string.community_debug,status.debug)
        debug.setOnCheckedChangeListener { _,checked->catalog.setDebug(checked) }
        fun reviewPicks(kept:Boolean) {
            val entries=if(kept)catalog.retained() else catalog.moderation()
            val title=if(kept)R.string.community_review_kept else R.string.community_review
            if(entries.isEmpty()){Toast.makeText(host,if(kept)R.string.community_kept_empty else R.string.community_nohidden,Toast.LENGTH_SHORT).show();return}
            val titles=entries.map { row ->
                val text=if(AssemblyLanguage.english(host))row.titleEn else row.titleZh
                if(row.reason.isBlank()) text else text+"\n"+row.reason
            }.toTypedArray()
            AlertDialog.Builder(host).setTitle(ui.str(title,entries.size)).setItems(titles){_,position ->
                AlertDialog.Builder(host).setMessage(titles[position]).setPositiveButton(if(kept)R.string.community_release else R.string.community_undo){_,_->
                    if(kept)catalog.release(entries[position]) else catalog.restore(entries[position])
                }
                    .setNegativeButton(R.string.community_cancel,null).show()
            }.setNegativeButton(R.string.community_done,null).show()
        }
        val kept=ui.action(""){reviewPicks(true)}
        form.addView(kept,ui.lp(6))
        val review=ui.action(""){reviewPicks(false)}
        form.addView(review,ui.lp(6))
        val replace=ui.action(ui.str(R.string.community_replace)){
            AlertDialog.Builder(host).setMessage(R.string.community_replace_confirm)
                .setPositiveButton(R.string.community_replace){_,_->applySettings();catalog.regenerate()}
                .setNegativeButton(R.string.community_cancel,null).show()
        }
        form.addView(replace,ui.lp(8))
        val clear=ui.action(ui.str(R.string.community_clear)){
            AlertDialog.Builder(host).setMessage(R.string.community_clean_confirm)
                .setPositiveButton(R.string.community_clean_go){_,_->catalog.clearGenerated()}
                .setNegativeButton(R.string.community_cancel,null).show()
        }
        clear.background=ui.bg(0xfff2e3de.toInt(),16)
        form.addView(clear,ui.lp(8))
        val update:()->Unit={
            val s=catalog.status()
            val feedback=when {
                s.busy -> R.string.community_busy
                s.lastError=="key" -> R.string.community_error_key
                s.lastError=="format" -> R.string.community_error_format
                s.lastError=="storage" -> R.string.community_error_storage
                s.lastError!=null -> R.string.community_error_offline
                else -> R.string.community_generated_status
            }
            val progress=if(s.requested>0) "\n"+ui.str(R.string.community_batch_progress,s.completed,s.requested,s.failed) else ""
            info.text=ui.str(R.string.community_generated,s.generatedCount)+"  ·  "+ui.str(feedback)+progress
            add.isEnabled=!s.busy;replace.isEnabled=!s.busy
            add.alpha=if(s.busy) .55f else 1f;replace.alpha=add.alpha
            review.text=ui.str(R.string.community_review,catalog.moderation().size)
            kept.text=ui.str(R.string.community_review_kept,catalog.retained().size)
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnDismissListener {catalog.removeListener(update)}
        root.isFocusableInTouchMode=true;root.requestFocus()
        dialog.show()
        val height=host.findViewById<android.view.View>(android.R.id.content)?.height?.takeIf{it>0} ?: host.resources.displayMetrics.heightPixels
        dialog.window?.setLayout(-1,(height*.87).toInt())
        catalog.addListener(update);update()
    }

    fun hidePost(host:Activity,post:CommunityPost)=hide(host,R.string.community_hide_post){reason ->
        CommunityCatalog.get(host).hidePost(post.id,reason)
        if(host is CommunityDetailActivity)host.finish()
    }
    fun hideSample(host:Activity,post:CommunityPost,index:Int) {
        val catalog=CommunityCatalog.get(host)
        val sample=post.samples.getOrNull(index) ?:return
        hide(host,R.string.community_hide_sample){reason ->
            val current=catalog.post(post.id)?.samples?.indexOfFirst {it.key==sample.key} ?: -1
            if(current>=0)catalog.hideSample(post.id,current,reason)
        }
    }
    private fun hide(host:Activity,label:Int,action:(String)->Unit) {
        val ui=CommunityViews(host)
        val input=EditText(host).apply {hint=ui.str(R.string.community_reason);maxLines=3;filters=arrayOf(InputFilter.LengthFilter(240))}
        val wrap=ui.column().apply {setPadding(ui.d(20),ui.d(6),ui.d(20),0);addView(input)}
        AlertDialog.Builder(host).setTitle(label).setView(wrap).setPositiveButton(R.string.community_hide){_,_->
            action(input.text.toString().trim());Toast.makeText(host,R.string.community_hidden_notice,Toast.LENGTH_SHORT).show()
        }.setNegativeButton(R.string.community_cancel,null).show()
    }
}

