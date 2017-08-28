package io.github.kbiakov.codeview.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kbiakov.codeview.*
import io.github.kbiakov.codeview.Thread.async
import io.github.kbiakov.codeview.Thread.ui
import io.github.kbiakov.codeview.adapters.AbstractCodeAdapter.ViewHolderType.Companion.BordersCount
import io.github.kbiakov.codeview.adapters.AbstractCodeAdapter.ViewHolderType.Companion.LineStartIdx
import io.github.kbiakov.codeview.classifier.CodeClassifier
import io.github.kbiakov.codeview.classifier.CodeProcessor
import io.github.kbiakov.codeview.highlight.*
import java.util.*

/**
 * @class AbstractCodeAdapter
 *
 * Basic adapter for code view.
 *
 * @author Kirill Biakov
 */
abstract class AbstractCodeAdapter<T> : RecyclerView.Adapter<AbstractCodeAdapter.ViewHolder> {

    protected val context: Context
    protected var lines: List<String> = ArrayList() // items
    protected var droppedLines: List<String>? = null

    internal var options: Options

    private var footerEntities: HashMap<Int, List<T>> = HashMap()

    constructor(context: Context) {
        this.context = context
        this.options = Options(context)
        prepareCodeLines()
    }

    constructor(context: Context, code: String) {
        this.context = context
        this.options = Options(context, code)
        prepareCodeLines()
    }

    constructor(context: Context, options: Options) {
        this.context = context
        this.options = options
        prepareCodeLines()
    }

    /**
     * Split code content by lines. If listing must not be shown full, it shows
     * only necessary lines & the rest are dropped (and stores in named variable).
     */
    internal fun prepareCodeLines() {
        extractLines(options.code).apply {
            if (!options.shortcut || size <= options.maxLines) // limit is not reached, show full
                lines = this
            else slice(options.maxLines).apply {
                lines = linesToShow() + options.shortcutNote.toUpperCase()
                droppedLines = droppedLines()
            }
        }
    }

    // - Adapter interface

    /**
     * Update code.
     */
    internal fun updateCode(newCode: String) {
        options.code = newCode
        prepareCodeLines()
        notifyDataSetChanged()
    }

    /**
     * Update code with new Highlighter.
     */
    internal fun updateCode(newOptions: Options) {
        options = newOptions
        prepareCodeLines()
        notifyDataSetChanged()
    }

    /**
     * Add footer entity for code line.
     *
     * @param num Line number
     * @param entity Footer entity
     */
    fun addFooterEntity(num: Int, entity: T) {
        val notes = footerEntities[num] ?: ArrayList()
        footerEntities.put(num, notes + entity)
        notifyDataSetChanged() // TODO: replace with notifyItemInserted()
    }

    /**
     * Highlight code content.
     *
     * @param onReady Callback when content is highlighted
     */
    internal fun highlight(onReady: () -> Unit) {
        async {
            val language = options.language ?: classifyContent()
            highlighting(language, onReady)
        }
    }

    /**
     * Mapper from entity to footer view.
     *
     * @param context Context
     * @param entity Entity to setOptions view
     * @param isFirst Is first footer view
     * @return Footer view
     */
    abstract fun createFooter(context: Context, entity: T, isFirst: Boolean): View

    // - Helpers (for accessors)

    /**
     * Classify current code content.
     *
     * @return Classified language
     */
    private fun classifyContent(): String {
        val processor = CodeProcessor.getInstance(context)

        return if (processor.isTrained)
            processor.classify(options.code).get()
        else
            CodeClassifier.DEFAULT_LANGUAGE
    }

    /**
     * Highlight code content by language.
     *
     * @param language Language to highlight
     * @param onReady Callback
     */
    private fun highlighting(language: String, onReady: () -> Unit) {
        // TODO: highlight by 10 lines
        val code = CodeHighlighter.highlight(language, options.code, options.theme)
        updateContent(code, onReady)
    }

    /**
     * Return control to UI-thread when highlighted content is ready.
     * @param onUpdated Control callback
     */
    private fun updateContent(code: String, onUpdated: () -> Unit) {
        options.code = code
        prepareCodeLines()
        ui(onUpdated)
    }

    // - View holder

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val lineView = inflater.inflate(R.layout.item_code_line, parent, false)
        lineView.setBackgroundColor(options.theme.bgContent.color())

        val tvLineNum = lineView.findViewById(R.id.tv_line_num) as TextView
        tvLineNum.typeface = options.font
        tvLineNum.setTextColor(options.theme.numColor.color())
        tvLineNum.setBackgroundColor(options.theme.bgNum.color())

        val tvLineContent = lineView.findViewById(R.id.tv_line_content) as TextView
        tvLineContent.typeface = options.font

        val isLine = viewType == ViewHolderType.Line.viewType
        val lineViewHeight = if (isLine) R.dimen.line_height else R.dimen.line_border_height
        lineView.layoutParams.height = context.resources.getDimension(lineViewHeight).toInt()

        if (isLine) {
            val holder = LineViewHolder(lineView)
            holder.setIsRecyclable(false)
            return holder
        } else {
            return BorderViewHolder(lineView)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, pos: Int) {
        if (holder is LineViewHolder) {
            val lineNum = pos - LineStartIdx
            val lineCode = lines[lineNum]
            holder.mItem = lineCode

            options.lineClickListener?.let {
                holder.itemView.setOnClickListener {
                    options.lineClickListener?.onCodeLineClicked(lineNum, lineCode)
                }
            }
            setupLine(lineNum, lineCode, holder)
            displayLineFooter(lineNum, holder)
        }
    }

    override fun getItemCount() = lines.size + BordersCount // lines + borders

    override fun getItemViewType(pos: Int) = ViewHolderType.get(pos, itemCount)

    // - Helpers (for view holder)

    @SuppressLint("SetTextI18n")
    private fun setupLine(pos: Int, line: String, holder: ViewHolder) {
        holder.tvLineContent.text = html(line)
        holder.tvLineContent.setTextColor(options.theme.noteColor.color())

        if (options.shortcut && pos == MaxShortcutLines) {
            holder.tvLineNum.textSize = 10f
            holder.tvLineNum.text = context.getString(R.string.dots)
        } else {
            holder.tvLineNum.textSize = 12f
            holder.tvLineNum.text = "${pos + 1}"
        }
    }

    private fun displayLineFooter(pos: Int, holder: ViewHolder) {
        val entityList = footerEntities[pos]

        holder.llLineFooter.removeAllViews()

        entityList?.let {
            holder.llLineFooter.visibility = if (it.isNotEmpty()) View.VISIBLE else View.GONE

            it.forEachIndexed { idx, entity ->
                val footerView = createFooter(context, entity, idx == 0)
                holder.llLineFooter.addView(footerView)
            }
        }
    }

    companion object {
        private const val MaxShortcutLines = 6

        private fun Pair<List<String>, List<String>>.linesToShow() = first
        private fun Pair<List<String>, List<String>>.droppedLines() = second
    }

    // - ViewHolder

    enum class ViewHolderType(val viewType: Int) {
        Line(0), Border(1);

        companion object {
            const val LineStartIdx = 1
            const val BordersCount = 2

            fun Int.lineEndIdx() = this - BordersCount

            fun get(pos: Int, n: Int) = when (pos) {
                in LineStartIdx .. n.lineEndIdx() ->
                    ViewHolderType.Line.viewType
                else ->
                    ViewHolderType.Border.viewType
            }
        }
    }

    /**
     * View holder for code adapter.
     * Stores all views related to code line layout.
     */
    open class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvLineNum = itemView.findViewById(R.id.tv_line_num) as TextView
        val tvLineContent = itemView.findViewById(R.id.tv_line_content) as TextView
        val llLineFooter = itemView.findViewById(R.id.ll_line_footer) as LinearLayout

        var mItem: String? = null

        override fun toString() = "${super.toString()} '$mItem'"
    }

    class LineViewHolder(itemView: View) : ViewHolder(itemView)

    /**
     * View holder for padding.
     * Stores all views related to code line layout.
     */
    class BorderViewHolder(itemView: View) : ViewHolder(itemView)
}

/**
 * @class Options
 *
 * All options in one place.
 *
 * @param context Context
 * @param code Code content
 * @param language Programming language to highlight
 * @param theme Color theme
 * @param shadows Is border shadows needed?
 * @param maxLines Max lines to show (when limit is reached, rest is dropped)
 * @param shortcut Do you want to show shortcut of code listing?
 * @param shortcutNote When rest lines is dropped, note is shown as last string
 * @param lineClickListener Listener to code line clicks
 *
 * @author Kirill Biakov
 */
data class Options(
        val context: Context,
        var code: String = "",
        var language: String? = null,
        var theme: ColorThemeData = ColorTheme.DEFAULT.theme(),
        var font: Typeface = FontCache.get(context).getTypeface(context),
        var shadows: Boolean = false,
        var shortcut: Boolean = false,
        var shortcutNote: String = context.getString(R.string.show_all),
        var maxLines: Int = 0,
        var lineClickListener: OnCodeLineClickListener? = null) {

    fun withCode(code: String): Options {
        this.code = code
        return this
    }

    fun withCode(codeResId: Int): Options {
        this.code = context.getString(codeResId)
        return this
    }

    fun setCode(codeResId: Int) {
        withCode(codeResId)
    }

    fun withLanguage(language: String): Options {
        this.language = language
        return this
    }

    fun withTheme(theme: ColorThemeData): Options {
        this.theme = theme
        return this
    }

    fun withTheme(theme: ColorTheme): Options {
        this.theme = theme.theme()
        return this
    }

    fun setTheme(theme: ColorTheme) {
        withTheme(theme)
    }

    fun withFont(fontPath: String): Options {
        this.font = FontCache.get(context).getTypeface(context, fontPath)
        return this
    }

    fun withFont(font: Font): Options {
        this.font = FontCache.get(context).getTypeface(context, font)
        return this
    }

    fun setFont(fontPath: String) {
        withFont(fontPath)
    }

    fun setFont(font: Font) {
        withFont(font)
    }

    fun withShadows(): Options {
        this.shadows = true
        return this
    }

    fun withoutShadows(): Options {
        this.shadows = false
        return this
    }

    fun shortcut(maxLines: Int, shortcutNote: String): Options {
        this.shortcut = true
        this.maxLines = maxLines
        this.shortcutNote = shortcutNote
        return this
    }

    fun addCodeLineClickListener(listener: OnCodeLineClickListener): Options {
        this.lineClickListener = listener
        return this
    }

    fun removeLineClickListener(): Options {
        this.lineClickListener = null
        return this
    }

    companion object Default {
        fun get(context: Context) = Options(context)
    }
}
